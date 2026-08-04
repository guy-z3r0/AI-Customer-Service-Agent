/*
 * Runs on the browser's audio thread, not the page's.
 *
 * The microphone arrives at whatever rate the sound card runs at — usually
 * 48 kHz — in blocks of 128 samples. Speech recognisers want 16 kHz. Doing the
 * conversion here means the page's main thread never touches audio, so a busy
 * screen cannot make the call stutter.
 *
 * Loaded by name from mic_stream.js; it is not an ES module and must not
 * import anything.
 */

const TARGET_RATE = 16000;
// About 20 ms of audio per message. Small enough to keep latency down, large
// enough that we are not posting hundreds of tiny messages a second.
const SAMPLES_PER_MESSAGE = 320;

class MicProcessor extends AudioWorkletProcessor {

    constructor() {
        super();
        // How many input samples make up one output sample.
        this.step = sampleRate / TARGET_RATE;
        this.carry = new Float32Array(0);
        this.offset = 0;      // fractional read position, kept across blocks
        this.pending = [];
    }

    process(inputs) {
        const channel = inputs[0] && inputs[0][0];
        if (!channel) return true;

        const buffer = new Float32Array(this.carry.length + channel.length);
        buffer.set(this.carry);
        buffer.set(channel, this.carry.length);

        let position = this.offset;
        while (Math.floor(position) + 1 < buffer.length) {
            const index = Math.floor(position);
            const fraction = position - index;
            this.pending.push(buffer[index] * (1 - fraction) + buffer[index + 1] * fraction);
            position += this.step;
        }

        // Keep the samples the next block still needs to interpolate against.
        const consumed = Math.floor(position);
        this.carry = buffer.slice(consumed);
        this.offset = position - consumed;

        if (this.pending.length >= SAMPLES_PER_MESSAGE) this.flush();
        return true;
    }

    flush() {
        const pcm = new Int16Array(this.pending.length);
        for (let i = 0; i < this.pending.length; i++) {
            const sample = Math.max(-1, Math.min(1, this.pending[i]));
            pcm[i] = Math.round(sample * 32767);
        }
        this.pending.length = 0;
        this.port.postMessage(pcm, [pcm.buffer]);
    }
}

registerProcessor('mic-processor', MicProcessor);
