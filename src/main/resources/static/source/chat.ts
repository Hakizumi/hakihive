type ConversationRequest = {
    cid: string;
    message: string;
};

type ConversationResponse = {
    success: boolean;
    error?: string | null;
    errorCode?: number | null;
    message?: string | null;
};

type SSEEvent = {
    event: string;
    data: string;
};

class ChatApp {
    private readonly messagesEl = this.mustGet<HTMLDivElement>("messages");
    private readonly formEl = this.mustGet<HTMLFormElement>("chatForm");
    private readonly inputEl = this.mustGet<HTMLTextAreaElement>("messageInput");
    private readonly sendBtnEl = this.mustGet<HTMLButtonElement>("sendBtn");
    private readonly newChatBtnEl = this.mustGet<HTMLButtonElement>("newChatBtn");
    private readonly stopBtnEl = this.mustGet<HTMLButtonElement>("stopBtn");
    private readonly cidTextEl = this.mustGet<HTMLDivElement>("cidText");
    private readonly streamingSwitchEl = this.mustGet<HTMLInputElement>("streamingSwitch");

    private cid: string = this.loadOrCreateCid();
    private abortController: AbortController | null = null;
    private isSending = false;

    constructor() {
        this.cidTextEl.textContent = this.cid;

        this.formEl.addEventListener("submit", (event) => {
            event.preventDefault();
            void this.handleSubmit();
        });

        this.newChatBtnEl.addEventListener("click", () => {
            this.cid = this.createCid();
            localStorage.setItem("hakihive-cid", this.cid);
            this.cidTextEl.textContent = this.cid;
            this.messagesEl.innerHTML = "";
            this.addSystemMessage("Created new conversation.");
            this.inputEl.focus();
        });

        this.stopBtnEl.addEventListener("click", () => {
            this.abortCurrentRequest();
        });

        this.inputEl.addEventListener("keydown", (event) => {
            if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                void this.handleSubmit();
            }
        });

        this.autoResizeInput();
        this.inputEl.addEventListener("input", () => this.autoResizeInput());

        this.addSystemMessage("Welcome to use Hakihive.💬");
    }

    private async handleSubmit(): Promise<void> {
        const text = this.inputEl.value.trim();
        if (!text || this.isSending) {
            return;
        }

        this.addMessage("user", text);
        this.inputEl.value = "";
        this.autoResizeInput();

        const streaming = this.streamingSwitchEl.checked;
        if (streaming) {
            await this.sendStreaming(text);
        } else {
            await this.sendNonStreaming(text);
        }
    }

    private async sendNonStreaming(message: string): Promise<void> {
        this.setSending(true);
        try {
            const payload: ConversationRequest = {
                cid: this.cid,
                message
            };

            const response = await fetch("/backend/chat/nonstreaming", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }

            const json = (await response.json()) as ConversationResponse;

            if (!json.success) {
                throw new Error(json.error ?? "Unknown server error");
            }

            this.addMessage("assistant", json.message ?? "");
        } catch (error) {
            this.addSystemMessage(`Request failed：${this.formatError(error)}`);
        } finally {
            this.setSending(false);
        }
    }

    private async sendStreaming(message: string): Promise<void> {
        this.setSending(true);
        this.abortController = new AbortController();

        const assistantNode = this.addMessage("assistant", "", true);

        try {
            const payload: ConversationRequest = {
                cid: this.cid,
                message
            };

            const response = await fetch("/backend/chat/streaming", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                    "Accept": "text/event-stream"
                },
                body: JSON.stringify(payload),
                signal: this.abortController.signal
            });

            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }

            if (!response.body) {
                throw new Error("Response body is empty");
            }

            const reader = response.body.getReader();
            const decoder = new TextDecoder("utf-8");
            let buffer = "";
            let fullText = "";

            while (true) {
                const { value, done } = await reader.read();
                if (done) {
                    break;
                }

                buffer += decoder.decode(value, { stream: true });
                const parsed = this.extractSSEEvents(buffer);
                buffer = parsed.rest;

                for (const evt of parsed.events) {
                    if (!evt.data) {
                        continue;
                    }

                    const json = JSON.parse(evt.data) as ConversationResponse;

                    if (evt.event === "error" || !json.success) {
                        throw new Error(json.error ?? "Server returned an error");
                    }

                    if (evt.event === "delta" && json.message) {
                        fullText += json.message;
                        assistantNode.textContent = fullText;
                        assistantNode.classList.add("cursor");
                        this.scrollToBottom();
                    }
                }
            }

            assistantNode.classList.remove("cursor");

            if (!fullText) {
                assistantNode.textContent = "(empty response)";
            }
        } catch (error) {
            if ((error as DOMException)?.name === "AbortError") {
                assistantNode.classList.remove("cursor");
                assistantNode.textContent += "\n\n[Generation stopped]";
            } else {
                assistantNode.remove();
                this.addSystemMessage(`Request streaming failed：${this.formatError(error)}`);
            }
        } finally {
            this.abortController = null;
            this.setSending(false);
        }
    }

    private extractSSEEvents(raw: string): { events: SSEEvent[]; rest: string } {
        const normalized = raw.replace(/\r\n/g, "\n");
        const chunks = normalized.split("\n\n");

        if (chunks.length === 1) {
            return { events: [], rest: raw };
        }

        const complete = chunks.slice(0, -1);
        const rest = chunks[chunks.length - 1] ?? "";
        const events: SSEEvent[] = [];

        for (const chunk of complete) {
            const lines = chunk.split("\n");
            let event = "message";
            const dataLines: string[] = [];

            for (const line of lines) {
                if (line.startsWith("event:")) {
                    event = line.slice(6).trim();
                } else if (line.startsWith("data:")) {
                    dataLines.push(line.slice(5).trim());
                }
            }

            events.push({
                event,
                data: dataLines.join("\n")
            });
        }

        return { events, rest };
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

    private abortCurrentRequest(): void {
        if (this.abortController) {
            this.abortController.abort();
        }
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
