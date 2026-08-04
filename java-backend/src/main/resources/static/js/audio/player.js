/*
 * Playing the agent's voice.
 *
 * Audio arrives in chunks while it is still being synthesised, so each one is
 * scheduled to start exactly where the last one ends. Left to itself the
 * browser would play them as they arrived and leave audible seams between them.
 *
 * The player also reports when it is and is not speaking, which is what the
 * page uses to gate the microphone and to show the call's state.
 */

const SOURCE_RATE = 16000;
// A small cushion before the first chunk, so scheduling never lands in the past
// and produces a click.
const START_DELAY_S = 0.05;

export class AgentPlayer {

    constructor(onSpeakingChange) {
        this.onSpeakingChange = onSpeakingChange;
        this.context = new AudioContext();
        this.nextStartTime = 0;
        this.playing = 0;
    }

    /** Queues one chunk of 16 kHz mono PCM16. */
    play(pcmBytes) {
        const samples = new Int16Array(pcmBytes);
        if (samples.length === 0) return;

        const buffer = this.context.createBuffer(1, samples.length, SOURCE_RATE);
        const channel = buffer.getChannelData(0);
        for (let i = 0; i < samples.length; i++) channel[i] = samples[i] / 32768;

        const source = this.context.createBufferSource();
        source.buffer = buffer;
        source.connect(this.context.destination);

        const now = this.context.currentTime;
        if (this.nextStartTime < now) this.nextStartTime = now + START_DELAY_S;
        source.start(this.nextStartTime);
        this.nextStartTime += buffer.duration;

        this.playing++;
        if (this.playing === 1) this.onSpeakingChange(true);
        source.onended = () => {
            this.playing--;
            if (this.playing === 0) this.onSpeakingChange(false);
        };
    }

    /** Throws away anything not yet heard. Used when the call ends. */
    stop() {
        this.nextStartTime = 0;
        this.playing = 0;
        this.context.close();
        this.onSpeakingChange(false);
    }
}
