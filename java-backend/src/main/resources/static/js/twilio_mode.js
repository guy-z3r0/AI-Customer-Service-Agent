/*
 * The optional telephone half of the Live Call page.
 *
 * A Twilio call and a browser call are the same call once the audio is moving:
 * same voice server, same brain, same transcript arriving over the live feed.
 * The only difference is who carries the sound, and this module is the whole of
 * that difference — which is why nothing else on the page imports Twilio.
 *
 * The SDK is fetched from a CDN the first time somebody actually presses the
 * button. Loading three hundred kilobytes of telephony on every page view, for
 * a feature most installs never configure, would be the wrong trade.
 */

// Served from this app rather than from a CDN.
//
// Pinning the version stopped the package changing under us; it did nothing
// about the content delivery network serving something else, and this script
// runs in the same origin as every customer record. Vendoring settles both,
// and it removes a network dependency from a telephony feature — which is
// exactly the dependency you do not want failing halfway through a call.
//
// The file is @twilio/voice-sdk 2.18.3, verified to expose Twilio.Device with
// isSupported true. Replacing it means downloading a new version, checking the
// same, and changing this one line.
const SDK_URL = '/vendor/twilio-voice-2.18.3.min.js';

let sdkPromise = null;

/**
 * Loads the Twilio SDK once and hands back its Device class.
 *
 * The promise is kept rather than the result, so two presses in quick
 * succession wait on one download instead of starting two.
 */
function loadSdk() {
    if (sdkPromise) return sdkPromise;

    sdkPromise = new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = SDK_URL;
        script.async = true;
        script.onload = () => {
            if (window.Twilio && window.Twilio.Device) resolve(window.Twilio.Device);
            else reject(new Error('sdk_incomplete'));
        };
        script.onerror = () => {
            // Let the next press try again; a CDN that was unreachable once
            // is usually reachable a moment later.
            sdkPromise = null;
            reject(new Error('sdk_unreachable'));
        };
        document.head.appendChild(script);
    });
    return sdkPromise;
}

/**
 * One telephone call, from the panel's side.
 *
 * It owns the Twilio device and connection and nothing else. Everything the
 * operator sees during the call — transcript, screening, timings — arrives the
 * same way it does for a browser call, over the live feed.
 */
export class TwilioMode {

    constructor() {
        this.device = null;
        this.connection = null;
    }

    /** True when the backend has every Twilio setting it needs. */
    static async isConfigured() {
        try {
            await getJson('/api/twilio/token');
            return true;
        } catch (error) {
            return false;
        }
    }

    /**
     * Places the call.
     *
     * The call id goes out as a parameter, and comes back to the backend when
     * Twilio asks what to do with the call. That is what ties a telephone call
     * to the record this panel has already opened for it.
     *
     * @param onEnded called when the far end hangs up, however that happened
     */
    async dial(callId, onEnded) {
        const [Device, token] = await Promise.all([loadSdk(), fetchToken()]);
        if (!Device.isSupported) throw new Error('twilio_unsupported');

        this.device = new Device(token, { codecPreferences: ['opus', 'pcmu'] });
        this.device.on('error', (error) => console.warn('twilio device error', error));

        await this.device.register();
        this.connection = await this.device.connect({ params: { callId } });
        this.connection.on('disconnect', () => onEnded('twilio_disconnected'));
        this.connection.on('error', () => onEnded('twilio_error'));
    }

    /** Hangs up and gives the microphone back. Safe to call twice. */
    hangUp() {
        try {
            if (this.device) this.device.disconnectAll();
        } catch (error) {
            console.warn('could not disconnect the twilio device', error);
        }
        try {
            if (this.device) this.device.destroy();
        } catch (error) {
            console.warn('could not destroy the twilio device', error);
        }
        this.device = null;
        this.connection = null;
    }
}

// The two calls this module makes to our own backend. It does not import
// api.js, because a 409 here is an answer ("not configured yet") rather than
// the error that module turns everything into.

async function fetchToken() {
    const body = await getJson('/api/twilio/token');
    return body.token;
}

async function getJson(path) {
    const response = await fetch(path);
    if (!response.ok) throw new Error(String(response.status));
    return response.json();
}
