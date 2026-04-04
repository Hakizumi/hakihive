type WsRequestType = "chat" | "stop" | "ping" | "audio_meta";

type WsRequest = {
    type: WsRequestType;
    text: string;
    sampleRate?: number;
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
    private readonly voiceBtnEl = this.mustGet<HTMLButtonElement>("voiceBtn");
    private readonly newChatBtnEl = this.mustGet<HTMLButtonElement>("newChatBtn");
    private readonly stopBtnEl = this.mustGet<HTMLButtonElement>("stopBtn");
    private readonly cidTextEl = this.mustGet<HTMLDivElement>("cidText");
    private readonly voiceStatusTextEl = this.mustGet<HTMLSpanElement>("voiceStatusText");

    private readonly sttSampleRate = 16000;
    private cid: string = this.loadOrCreateCid();
    private ws: WebSocket | null = null;
    private isSending = false;
    private isRecording = false;

    private assistantNode: HTMLDivElement | null = null;
    private assistantText = "";

    private liveUserNode: HTMLDivElement | null = null;
    private liveUserText = "";

    private mediaStream: MediaStream | null = null;
    private audioContext: AudioContext | null = null;
    private mediaSourceNode: MediaStreamAudioSourceNode | null = null;
    private scriptProcessorNode: ScriptProcessorNode | null = null;
    private monitorGainNode: GainNode | null = null;
    private resampleState: {
        ratio: number;
        sourceOffset: number;
        tail: Float32Array;
    } | null = null;

    constructor() {
        this.cidTextEl.textContent = this.cid;

        this.formEl.addEventListener("submit", (event) => {
            event.preventDefault();
            void this.handleSubmit();
        });

        this.newChatBtnEl.addEventListener("click", async () => {
            await this.stopVoiceRecording();
            this.closeSocketForSwitch();

            this.cid = this.createCid();
            localStorage.setItem("hakihive-cid", this.cid);
            this.cidTextEl.textContent = this.cid;
            this.messagesEl.innerHTML = "";

            this.assistantNode = null;
            this.assistantText = "";
            this.liveUserNode = null;
            this.liveUserText = "";

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

        this.voiceBtnEl.addEventListener("click", async () => {
            if (this.isRecording) {
                await this.stopVoiceRecording();
            } else {
                await this.startVoiceRecording();
            }
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
        this.setVoiceIdleUi();
    }

    private async handleSubmit(): Promise<void> {
        const text = this.inputEl.value.trim();
        if (!text || this.isSending || this.isRecording) {
            return;
        }

        if (!(await this.ensureSocketReady())) {
            return;
        }

        this.addMessage("user", text);
        this.inputEl.value = "";
        this.autoResizeInput();

        this.assistantNode = null;
        this.assistantText = "";
        this.liveUserNode = null;
        this.liveUserText = "";

        this.setSending(true);

        this.sendWsMessage({
            type: "chat",
            text
        });
    }

    private async startVoiceRecording(): Promise<void> {
        if (this.isRecording) {
            return;
        }

        if (!(await this.ensureSocketReady())) {
            return;
        }

        if (!navigator.mediaDevices?.getUserMedia) {
            this.addSystemMessage("Voice input is not supported in this browser.");
            return;
        }

        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: {
                    channelCount: 1,
                    echoCancellation: true,
                    noiseSuppression: true,
                    autoGainControl: true
                }
            });

            const AudioContextCtor = window.AudioContext || (window as typeof window & {
                webkitAudioContext?: typeof AudioContext;
            }).webkitAudioContext;

            if (!AudioContextCtor) {
                throw new Error("AudioContext is not supported in this browser.");
            }

            const audioContext = new AudioContextCtor();
            await audioContext.resume();

            const sourceNode = audioContext.createMediaStreamSource(stream);
            const processorNode = audioContext.createScriptProcessor(2048, 1, 1);
            const gainNode = audioContext.createGain();
            gainNode.gain.value = 0;

            this.resetResampler(audioContext.sampleRate, this.sttSampleRate);

            this.sendWsMessage({
                type: "audio_meta",
                text: "",
                sampleRate: this.sttSampleRate
            });

            processorNode.onaudioprocess = (event: AudioProcessingEvent) => {
                if (!this.isRecording || !this.ws || this.ws.readyState !== WebSocket.OPEN) {
                    return;
                }

                const channelData = event.inputBuffer.getChannelData(0);
                const resampled = this.resampleAudioChunk(channelData);
                const pcm16Buffer = this.convertFloat32ToPcm16Le(resampled);
                if (pcm16Buffer.byteLength > 0) {
                    this.ws.send(pcm16Buffer);
                }
            };

            sourceNode.connect(processorNode);
            processorNode.connect(gainNode);
            gainNode.connect(audioContext.destination);

            this.mediaStream = stream;
            this.audioContext = audioContext;
            this.mediaSourceNode = sourceNode;
            this.scriptProcessorNode = processorNode;
            this.monitorGainNode = gainNode;
            this.isRecording = true;

            this.formEl.classList.add("voice-mode");
            this.voiceBtnEl.classList.add("recording");
            this.voiceBtnEl.textContent = "■";
            this.voiceStatusTextEl.textContent = `Recording ${this.sttSampleRate} Hz PCM16LE audio...`;
            this.inputEl.disabled = true;
            this.sendBtnEl.disabled = true;
            this.stopBtnEl.disabled = false;

            this.assistantNode = null;
            this.assistantText = "";
            this.liveUserNode = null;
            this.liveUserText = "";
        } catch (error) {
            await this.stopVoiceRecording();
            this.addSystemMessage(`Voice start failed: ${this.formatError(error)}`);
        }
    }

    private async stopVoiceRecording(): Promise<void> {
        this.isRecording = false;
        this.resampleState = null;

        if (this.scriptProcessorNode) {
            this.scriptProcessorNode.disconnect();
            this.scriptProcessorNode.onaudioprocess = null;
            this.scriptProcessorNode = null;
        }

        if (this.monitorGainNode) {
            this.monitorGainNode.disconnect();
            this.monitorGainNode = null;
        }

        if (this.mediaSourceNode) {
            this.mediaSourceNode.disconnect();
            this.mediaSourceNode = null;
        }

        if (this.mediaStream) {
            for (const track of this.mediaStream.getTracks()) {
                track.stop();
            }
            this.mediaStream = null;
        }

        if (this.audioContext) {
            try {
                await this.audioContext.close();
            } catch {
                // ignore close error
            }
            this.audioContext = null;
        }

        this.setVoiceIdleUi();
        this.inputEl.disabled = this.isSending;
        this.sendBtnEl.disabled = this.isSending;
        this.stopBtnEl.disabled = !this.isSending;
    }

    private async ensureSocketReady(): Promise<boolean> {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            this.addSystemMessage("WebSocket not connected, reconnecting...");
            try {
                await this.connectWebSocket();
            } catch (error) {
                this.addSystemMessage(`WebSocket connection failed: ${this.formatError(error)}`);
                return false;
            }
        }

        return !!this.ws && this.ws.readyState === WebSocket.OPEN;
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

                const protocol = location.protocol === "https:" ? "wss:" : "ws:";
                const ws = new WebSocket(
                    `${protocol}//${location.host}/backend/websocket/chat?cid=${encodeURIComponent(this.cid)}`
                );

                ws.binaryType = "arraybuffer";
                this.ws = ws;

                ws.onopen = () => {
                    this.addSystemMessage("WebSocket connected.");
                    resolve();
                };

                ws.onmessage = (event) => {
                    if (typeof event.data === "string") {
                        this.handleWsMessage(event.data);
                    }
                };

                ws.onerror = () => {
                    reject(new Error("WebSocket error"));
                };

                ws.onclose = (event) => {
                    const wasSending = this.isSending;
                    this.ws = null;

                    if (wasSending) {
                        this.finishAssistant("\n\n[Connection closed]");
                        this.setSending(false);
                    }

                    if (this.isRecording) {
                        void this.stopVoiceRecording();
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

        if (data.cid && data.cid !== this.cid) {
            return;
        }

        switch (data.type) {
            case "connected":
                this.cid = data.cid;
                localStorage.setItem("hakihive-cid", this.cid);
                this.cidTextEl.textContent = this.cid;
                this.addSystemMessage(`Session connected: ${data.cid}`);
                break;

            case "pong":
                this.addSystemMessage("Pong received.");
                break;

            case "stopped":
                this.finishAssistant("\n\n[Generation stopped]");
                this.finalizeLiveUserBubble();
                this.setSending(false);
                if (this.isRecording) {
                    void this.stopVoiceRecording();
                }
                break;

            case "client_stop":
                this.finishAssistant("\n\n[Assistant interrupted]");
                this.setSending(false);
                break;

            case "error":
                this.finishAssistant(data.text ? `\n\n[Error] ${data.text}` : "\n\n[Error]");
                this.finalizeLiveUserBubble();
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
                this.showOrUpdateLiveUserBubble(data.text ?? "", true);
                break;

            case "stt_final":
                this.showOrUpdateLiveUserBubble(data.text ?? "", false);
                break;

            default:
                this.addSystemMessage(`Unhandled message type: ${(data as { type?: string }).type ?? "unknown"}`);
                break;
        }
    }

    private showOrUpdateLiveUserBubble(text: string, partial: boolean): void {
        if (!this.liveUserNode) {
            this.liveUserNode = this.addMessage("user", "", partial);
            const wrapper = this.liveUserNode.parentElement;
            if (wrapper) {
                wrapper.classList.add("live");
                const meta = wrapper.querySelector<HTMLElement>(".meta");
                if (meta) {
                    meta.textContent = partial ? "You · Listening" : "You";
                }
            }
        }

        this.liveUserText = text;
        this.liveUserNode.textContent = text || (partial ? "Listening..." : "");

        const wrapper = this.liveUserNode.parentElement;
        const meta = wrapper?.querySelector<HTMLElement>(".meta");
        if (meta) {
            meta.textContent = partial ? "You · Listening" : "You";
        }

        if (partial) {
            this.liveUserNode.classList.add("cursor");
            wrapper?.classList.add("live");
        } else {
            this.liveUserNode.classList.remove("cursor");
            wrapper?.classList.remove("live");
        }

        this.scrollToBottom();
    }

    private finalizeLiveUserBubble(): void {
        if (!this.liveUserNode) {
            return;
        }

        this.liveUserNode.classList.remove("cursor");
        const wrapper = this.liveUserNode.parentElement;
        wrapper?.classList.remove("live");
        const meta = wrapper?.querySelector<HTMLElement>(".meta");
        if (meta) {
            meta.textContent = "You";
        }
    }

    private sendStop(): void {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.sendWsMessage({
                type: "stop",
                text: "ignored"
            });
        }

        if (this.isRecording) {
            void this.stopVoiceRecording();
        }
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
        this.sendBtnEl.disabled = sending || this.isRecording;
        this.stopBtnEl.disabled = !sending && !this.isRecording;
        this.inputEl.disabled = sending || this.isRecording;
    }

    private scrollToBottom(): void {
        this.messagesEl.scrollTop = this.messagesEl.scrollHeight;
    }

    private autoResizeInput(): void {
        this.inputEl.style.height = "auto";
        this.inputEl.style.height = `${Math.min(this.inputEl.scrollHeight, 220)}px`;
    }

    private setVoiceIdleUi(): void {
        this.formEl.classList.remove("voice-mode");
        this.voiceBtnEl.classList.remove("recording");
        this.voiceBtnEl.textContent = "🎤";
        this.voiceStatusTextEl.textContent = "Voice input idle.";
    }

    private convertFloat32ToPcm16Le(input: Float32Array): ArrayBuffer {
        const output = new ArrayBuffer(input.length * 2);
        const view = new DataView(output);

        for (let i = 0; i < input.length; i += 1) {
            const sample = Math.max(-1, Math.min(1, input[i] ?? 0));
            const int16 = sample < 0 ? Math.round(sample * 0x8000) : Math.round(sample * 0x7fff);
            view.setInt16(i * 2, int16, true);
        }

        return output;
    }

    private resetResampler(inputSampleRate: number, targetSampleRate: number): void {
        this.resampleState = {
            ratio: inputSampleRate / targetSampleRate,
            sourceOffset: 0,
            tail: new Float32Array(0)
        };
    }

    private resampleAudioChunk(input: Float32Array): Float32Array {
        if (input.length === 0 || !this.resampleState) {
            return new Float32Array(0);
        }

        if (!this.audioContext || this.audioContext.sampleRate === this.sttSampleRate) {
            return input.slice();
        }

        const state = this.resampleState;
        const merged = new Float32Array(state.tail.length + input.length);
        merged.set(state.tail, 0);
        merged.set(input, state.tail.length);

        const ratio = state.ratio;
        const output: number[] = [];
        let sourceOffset = state.sourceOffset;

        while (sourceOffset + 1 < merged.length) {
            const leftIndex = Math.floor(sourceOffset);
            const rightIndex = leftIndex + 1;
            const weight = sourceOffset - leftIndex;
            const left = merged[leftIndex] ?? 0;
            const right = merged[rightIndex] ?? left;
            output.push(left + (right - left) * weight);
            sourceOffset += ratio;
        }

        const consumed = Math.min(Math.floor(sourceOffset), merged.length);
        state.tail = merged.slice(Math.max(0, consumed - 1));
        state.sourceOffset = sourceOffset - Math.max(0, consumed - 1);

        return new Float32Array(output);
    }

    private closeSocketForSwitch(): void {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.close(1000, "switch conversation");
        }
        this.ws = null;
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
