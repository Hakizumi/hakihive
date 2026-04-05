class PcmCaptureProcessor extends AudioWorkletProcessor {
    process(
        inputs: Float32Array[][],
        outputs: Float32Array[][],
        parameters: Record<string, Float32Array>
    ): boolean {
        const input = inputs[0];
        const output = outputs[0];

        if (input && input[0]) {
            const channel = input[0];
            const cloned = new Float32Array(channel.length);
            cloned.set(channel);
            this.port.postMessage(cloned);
        }

        if (output && output[0]) {
            const outChannel = output[0];
            outChannel.fill(0);
        }

        return true;
    }
}

registerProcessor("pcm-capture-processor", PcmCaptureProcessor);
