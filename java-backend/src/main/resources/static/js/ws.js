/*
 * The panel's live feed.
 *
 * One socket to /ws/live carries everything happening on a call — transcript
 * lines, live guesses, timings, the call starting and ending. It reconnects on
 * its own, because a panel that goes quiet after a hiccup looks exactly like a
 * panel watching a call where nobody is talking.
 */

const FIRST_RETRY_MS = 500;
const MAX_RETRY_MS = 10000;

export function connectLiveFeed(onEvent) {
    const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/live`;
    let socket = null;
    let retryMs = FIRST_RETRY_MS;
    let retryTimer = null;
    let stopped = false;

    function open() {
        socket = new WebSocket(url);

        socket.addEventListener('open', () => {
            retryMs = FIRST_RETRY_MS;
        });

        socket.addEventListener('message', (message) => {
            let event;
            try {
                event = JSON.parse(message.data);
            } catch (notJson) {
                return;
            }
            onEvent(event);
        });

        socket.addEventListener('close', scheduleRetry);
        socket.addEventListener('error', () => socket.close());
    }

    // Each failed attempt waits twice as long, up to a ceiling, so a backend
    // that is down for a while is not hammered.
    function scheduleRetry() {
        if (stopped || retryTimer) return;
        retryTimer = setTimeout(() => {
            retryTimer = null;
            open();
        }, retryMs);
        retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
    }

    open();

    return {
        close() {
            stopped = true;
            if (retryTimer) clearTimeout(retryTimer);
            if (socket) socket.close();
        }
    };
}
