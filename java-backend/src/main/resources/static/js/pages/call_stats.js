/*
 * The two numbers the Live Call page puts on screen about a call in progress:
 * how long it has been running, and how quickly the agent has been answering.
 *
 * Both are pure formatting of things the page already knows, which is why they
 * are here rather than on the page — nothing in this file touches the DOM, the
 * network or a call.
 */

/** How long a call has been running, as m:ss. */
export function formatElapsed(startedAt, endedAt) {
    if (!startedAt) return '—';
    // A finished call keeps the length it ended at rather than counting on.
    const seconds = Math.floor(((endedAt || Date.now()) - startedAt) / 1000);
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

/**
 * The middle reply time so far.
 *
 * The median rather than the mean, because one turn in a handful takes several
 * times as long as the rest, and an average of that describes neither.
 */
export function formatMedian(latencies) {
    if (latencies.length === 0) return '—';

    const sorted = [...latencies].sort((a, b) => a - b);
    const middle = Math.floor(sorted.length / 2);
    const median = sorted.length % 2
        ? sorted[middle]
        : Math.round((sorted[middle - 1] + sorted[middle]) / 2);
    return `${median} ms`;
}
