type WsRequestType = "chat" | "stop" | "ping";

type WsRequest = {
    type: WsRequestType;
    cid: string;
    text: string;
};

type WsResponseType =
    | "connected"
    | "stopped"
    | "pong"
    | "error"
    | "assistant_start"
    | "assistant_text"
    | "assistant_finish"
    | "stt_partial"
    | "stt_final"
    | "client_stop";

type WsResponse = {
    type: WsResponseType;
    cid: string;
    text?: string;
};

class ChatApp {
    private readonly messagesEl = this.mustGet<HTMLDivElement>("messages");
    private readonly formEl = this.mustGet<HTMLFormElement>("chatForm");
    private readonly inputEl = this.mustGet<HTMLTextAreaElement>("messageInput");
    private readonly sendBtnEl = this.mustGet<HTMLButtonElement>("sendBtn");
    private readonly newChatBtnEl = this.mustGet<HTMLButtonElement>("newChatBtn");
    private readonly stopBtnEl = this.mustGet<HTMLButtonElement>("stopBtn");
    private readonly cidTextEl = this.mustGet<HTMLDivElement>("cidText");

    private cid: string = this.loadOrCreateCid();
    private ws: WebSocket | null = null;
    private isSending = false;

    private assistantNode: HTMLDivElement | null = null;
    private assistantText = "";

    private sttNode: HTMLDivElement | null = null;

    constructor() {
        this.cidTextEl.textContent = this.cid;

        this.formEl.addEventListener("submit", (event) => {
            event.preventDefault();
            void this.handleSubmit();
        });

        this.newChatBtnEl.addEventListener("click", async () => {
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                this.ws.close(1000, "switch conversation");
            }

            this.cid = this.createCid();
            localStorage.setItem("hakihive-cid", this.cid);
            this.cidTextEl.textContent = this.cid;
            this.messagesEl.innerHTML = "";

            this.assistantNode = null;
            this.assistantText = "";
            this.sttNode = null;

            this.addSystemMessage("Created new conversation.");

            try {
                await this.connectWebSocket();
            } catch (error) {
                this.addSystemMessage(`WebSocket connection failed: ${this.formatError(error)}`);
            }

            this.inputEl.focus();
        });

        this.stopBtnEl.addEventListener("click", () => {
            this.sendStop();
        });

        this.inputEl.addEventListener("keydown", (event) => {
            if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                void this.handleSubmit();
            }
        });

        this.autoResizeInput();
        this.inputEl.addEventListener("input", () => this.autoResizeInput());

        void this.connectWebSocket();
        this.addSystemMessage("Welcome to use Hakihive.💬");
    }

    private async handleSubmit(): Promise<void> {
        const text = this.inputEl.value.trim();
        if (!text || this.isSending) {
            return;
        }

        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            this.addSystemMessage("WebSocket not connected, reconnecting...");
            try {
                await this.connectWebSocket();
            } catch (error) {
                this.addSystemMessage(`WebSocket connection failed: ${this.formatError(error)}`);
                return;
            }
        }

        this.addMessage("user", text);
        this.inputEl.value = "";
        this.autoResizeInput();

        this.assistantNode = null;
        this.assistantText = "";
        this.sttNode = null;

        this.setSending(true);

        this.sendWsMessage({
            type: "chat",
            cid: this.cid,
            text
        });
    }

    private connectWebSocket(): Promise<void> {
        return new Promise((resolve, reject) => {
            try {
                if (this.ws) {
                    if (
                        this.ws.readyState === WebSocket.OPEN ||
                        this.ws.readyState === WebSocket.CONNECTING
                    ) {
                        resolve();
                        return;
                    }
                    this.ws = null;
                }

                const ws = new WebSocket(
                    `ws://localhost:11622/backend/websocket/chat?cid=${encodeURIComponent(this.cid)}`
                );

                this.ws = ws;

                ws.onopen = () => {
                    console.log("[ws] open");
                    this.addSystemMessage("WebSocket connected.");
                    resolve();
                };

                ws.onmessage = (event) => {
                    console.log("[ws] message:", event.data);
                    this.handleWsMessage(event.data);
                };

                ws.onerror = (event) => {
                    console.error("[ws] error:", event);
                };

                ws.onclose = (event) => {
                    console.warn("[ws] close:", {
                        code: event.code,
                        reason: event.reason,
                        wasClean: event.wasClean
                    });

                    const wasSending = this.isSending;
                    this.ws = null;

                    if (wasSending) {
                        this.finishAssistant("[Connection closed]");
                        this.setSending(false);
                    }

                    this.addSystemMessage(
                        `WebSocket disconnected. code=${event.code}, reason=${event.reason || "(empty)"}`
                    );
                };
            } catch (error) {
                reject(error);
            }
        });
    }

    private handleWsMessage(raw: string): void {
        let data: WsResponse;

        try {
            data = JSON.parse(raw) as WsResponse;
        } catch (error) {
            this.addSystemMessage(`Invalid WebSocket message: ${this.formatError(error)}`);
            return;
        }

        // Only handle the current session
        if (data.cid && data.cid !== this.cid) {
            return;
        }

        switch (data.type) {
            case "connected":
                this.addSystemMessage(`Session connected: ${data.cid}`);
                break;

            case "pong":
                this.addSystemMessage("Pong received.");
                break;

            case "stopped":
                this.finishAssistant("\n\n[Generation stopped]");
                this.setSending(false);
                break;

            case "client_stop":
                this.finishAssistant("\n\n[Client stopped]");
                this.setSending(false);
                break;

            case "error":
                this.finishAssistant(data.text ? `\n\n[Error] ${data.text}` : "\n\n[Error]");
                this.addSystemMessage(`Server error: ${data.text ?? "Unknown error"}`);
                this.setSending(false);
                break;

            case "assistant_start":
                this.assistantText = "";
                this.assistantNode = this.addMessage("assistant", "", true);
                break;

            case "assistant_text":
                if (!this.assistantNode) {
                    this.assistantText = "";
                    this.assistantNode = this.addMessage("assistant", "", true);
                }

                this.assistantText += data.text ?? "";
                this.assistantNode.textContent = this.assistantText;
                this.assistantNode.classList.add("cursor");
                this.scrollToBottom();
                break;

            case "assistant_finish":
                this.finishAssistant();
                this.setSending(false);
                break;

            case "stt_partial":
                this.showOrUpdateStt(data.text ?? "", true);
                break;

            case "stt_final":
                this.showOrUpdateStt(data.text ?? "", false);
                break;

            default:
                this.addSystemMessage(`Unhandled message type: ${(data as { type?: string }).type ?? "unknown"}`);
                break;
        }
    }

    private showOrUpdateStt(text: string, partial: boolean): void {
        if (!this.sttNode) {
            this.sttNode = this.addMessage("assistant", "", false);
            this.sttNode.parentElement?.classList.add("stt");
        }

        this.sttNode.textContent = partial ? `[STT partial] ${text}` : `[STT final] ${text}`;
        this.scrollToBottom();
    }

    private sendStop(): void {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            return;
        }

        this.sendWsMessage({
            type: "stop",
            cid: this.cid,
            text: "ignored"
        });
    }

    private sendPing(): void {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            return;
        }

        this.sendWsMessage({
            type: "ping",
            cid: this.cid,
            text: "ignored"
        });
    }

    private sendWsMessage(payload: WsRequest): void {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            throw new Error("WebSocket is not open");
        }
        this.ws.send(JSON.stringify(payload));
    }

    private finishAssistant(suffix = ""): void {
        if (!this.assistantNode) {
            return;
        }

        const finalText = `${this.assistantText}${suffix}`;
        this.assistantNode.textContent = finalText || "(empty response)";
        this.assistantNode.classList.remove("cursor");
        this.scrollToBottom();
    }

    private addMessage(role: "user" | "assistant", text: string, withCursor = false): HTMLDivElement {
        const wrapper = document.createElement("div");
        wrapper.className = `message ${role}`;

        const meta = document.createElement("span");
        meta.className = "meta";
        meta.textContent = role === "user" ? "You" : "Assistant";

        const body = document.createElement("div");
        body.textContent = text;

        if (withCursor) {
            body.classList.add("cursor");
        }

        wrapper.appendChild(meta);
        wrapper.appendChild(body);
        this.messagesEl.appendChild(wrapper);
        this.scrollToBottom();

        return body;
    }

    private addSystemMessage(text: string): void {
        const wrapper = document.createElement("div");
        wrapper.className = "message system";
        wrapper.textContent = text;
        this.messagesEl.appendChild(wrapper);
        this.scrollToBottom();
    }

    private setSending(sending: boolean): void {
        this.isSending = sending;
        this.sendBtnEl.disabled = sending;
        this.stopBtnEl.disabled = !sending;
        this.inputEl.disabled = sending;
    }

    private scrollToBottom(): void {
        this.messagesEl.scrollTop = this.messagesEl.scrollHeight;
    }

    private autoResizeInput(): void {
        this.inputEl.style.height = "auto";
        this.inputEl.style.height = `${Math.min(this.inputEl.scrollHeight, 220)}px`;
    }

    private loadOrCreateCid(): string {
        const stored = localStorage.getItem("hakihive-cid");
        if (stored && stored.trim()) {
            return stored;
        }
        const cid = this.createCid();
        localStorage.setItem("hakihive-cid", cid);
        return cid;
    }

    private createCid(): string {
        return `web-${crypto.randomUUID()}`;
    }

    private formatError(error: unknown): string {
        if (error instanceof Error) {
            return error.message;
        }
        return String(error);
    }

    private mustGet<T extends HTMLElement>(id: string): T {
        const element = document.getElementById(id);
        if (!element) {
            throw new Error(`Element not found: #${id}`);
        }
        return element as T;
    }
}

document.addEventListener("DOMContentLoaded", () => {
    new ChatApp();
});
