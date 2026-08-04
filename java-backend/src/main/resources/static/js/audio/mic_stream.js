/*
 * Getting the microphone onto the websocket.
 *
 * Browser echo cancellation is switched on, which removes most of the agent's
 * own voice from what the microphone hears. It is not enough on its own, so
 * the server also throws away anything that arrives while the agent is
 * speaking; this is the first of those two defences.
 */

const WORKLET_URL = 'js/audio/mic_worklet.js';

export class MicStream {

    constructor(onFrame) {
        this.onFrame = onFrame;
        this.context = null;
        this.track = null;
        this.node = null;
        this.muted = false;
    }

    static isSupported() {
        return Boolean(navigator.mediaDevices && navigator.mediaDevices.getUserMedia
            && window.AudioWorkletNode);
    }

    /** Asks for the microphone and starts sending frames. Throws if refused. */
    async start() {
        const stream = await navigator.mediaDevices.getUserMedia({
            audio: {
                echoCancellation: true,
                noiseSuppression: true,
                autoGainControl: true,
                channelCount: 1
            }
        });
        this.track = stream.getAudioTracks()[0];

        this.context = new AudioContext();
        await this.context.audioWorklet.addModule(WORKLET_URL);

        this.node = new AudioWorkletNode(this.context, 'mic-processor');
        this.node.port.onmessage = (message) => {
            if (!this.muted) this.onFrame(message.data);
        };

        this.context.createMediaStreamSource(stream).connect(this.node);
        // The worklet has no output. Connecting it to the speakers anyway is
        // what keeps some browsers from suspending it as an idle graph.
        this.node.connect(this.context.destination);
    }

    /** Stops frames leaving without dropping the microphone permission. */
    setMuted(muted) {
        this.muted = muted;
    }

    stop() {
        if (this.node) this.node.port.onmessage = null;
        if (this.track) this.track.stop();
        if (this.context) this.context.close();
        this.node = null;
        this.track = null;
        this.context = null;
    }
}
