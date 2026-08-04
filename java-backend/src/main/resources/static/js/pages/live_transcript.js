/*
 * The scrolling record of one call, as it happens.
 *
 * It owns nothing but its own rows. Everything here is driven by events from
 * the backend's live feed, in the order they arrive, so what is on screen and
 * what is in the database are the same thing arriving twice.
 *
 * Notes — a change of screening or of language — go in the transcript rather
 * than beside it, because where they happened is the useful part.
 */

import { element, tagBadge } from '../components.js';

const ROLE_HUES = { CALLER: 'azure', AGENT: 'jade', SYSTEM: 'violet' };
const GOOD_LATENCY_MS = 2000;

export class Transcript {

    constructor(host, strings) {
        this.host = host;
        this.t = strings;
        this.partialRow = null;
    }

    /** The empty state: one line naming the space, no rows yet. */
    showEmpty() {
        this.host.replaceChildren(element('div', 'list-row muted-body', this.t['livecall.empty']));
        this.partialRow = null;
    }

    clear() {
        this.host.replaceChildren();
        this.partialRow = null;
    }

    /** The live guess while someone is still talking. One row, rewritten. */
    showPartial(text) {
        if (!this.partialRow) {
            this.partialRow = element('div', 'list-row list-row--partial');
            this.partialRow.appendChild(tagBadge(this.t['livecall.hearing'], 'gold'));
            this.partialRow.appendChild(element('span', 'list-row__text'));
            this.append(this.partialRow);
        }
        this.partialRow.lastChild.textContent = text;
        this.scrollToEnd();
    }

    addLine(event) {
        if (this.partialRow) { this.partialRow.remove(); this.partialRow = null; }

        const row = element('div', 'list-row');
        row.appendChild(tagBadge(this.t[`livecall.role_${event.role.toLowerCase()}`] || event.role,
            ROLE_HUES[event.role] || 'azure'));
        row.appendChild(element('span', 'list-row__text', event.text));
        row.dataset.seq = event.seq;
        // Only the agent's line is timed, so only it needs finding again later.
        if (event.turnSeq && event.role === 'AGENT') row.dataset.turn = event.turnSeq;
        this.append(row);
        this.scrollToEnd();
    }

    /**
     * Hangs a timing badge on the agent line the reading belongs to. The badge
     * shows the whole turn; hovering it splits that into the model's share and
     * the voice's, which is what tells you where a slow reply went wrong.
     */
    addTiming(event) {
        const row = this.rowForTurn(event.turnSeq);
        if (!row || row.dataset.timed) return;

        row.dataset.timed = '1';
        const badge = tagBadge(`${event.totalMs} ms`,
            event.totalMs <= GOOD_LATENCY_MS ? 'jade' : 'rose');
        if (event.llmMs !== undefined) {
            badge.title = this.t['livecall.stages']
                .replace('%s', event.llmMs).replace('%s', event.ttsMs);
        }
        row.appendChild(badge);
    }

    /** Something that happened to the call rather than something said on it. */
    addNote(label, detail, hue) {
        const row = element('div', 'list-row');
        row.appendChild(tagBadge(label, hue || 'violet'));
        if (detail) row.appendChild(element('span', 'list-row__text', detail));
        this.append(row);
        this.scrollToEnd();
    }

    // ------------------------------------------------------------ internals --

    rowForTurn(turnSeq) {
        const matched = this.host.querySelector(`.list-row[data-turn="${turnSeq}"]`);
        if (matched) return matched;
        const rows = this.host.querySelectorAll('.list-row[data-seq]');
        return rows[rows.length - 1];
    }

    append(row) {
        const placeholder = this.host.querySelector('.muted-body');
        if (placeholder) placeholder.remove();
        this.host.appendChild(row);
    }

    scrollToEnd() {
        this.host.scrollTop = this.host.scrollHeight;
    }
}
