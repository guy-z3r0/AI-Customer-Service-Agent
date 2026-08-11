/*
 * Overview — the whole system on one screen.
 *
 * Three questions, in the order someone actually asks them: what does the
 * agent know, what can it do right now, and what has it been doing.
 *
 * The middle one is the important one. Every capability says plainly whether
 * it works, works in a reduced form, or is waiting on a credential — so the
 * limits of a demo are visible before the demo rather than during it.
 */

import { api } from '../api.js';
import { outcomeBadge } from '../call_outcome.js';
import { button, element, statTile, table, tagBadge } from '../components.js';

const LATENCY_TARGET_MS = 2000;

const STATE_HUE = { ready: 'jade', degraded: 'gold', off: 'rose' };
const HINT_FOR = {
    voice: 'dash.hint.voice',
    model: 'dash.hint.model',
    speech: 'dash.hint.speech'
};
const OPTIONAL = ['telephony', 'email'];

// The same colour for a screening mode wherever it appears — here, on the live
// call and in the history — so the four kinds of call are learned once.
const MODE_HUE = {
    NEW_CUSTOMER: 'azure',
    EXISTING_CUSTOMER: 'jade',
    WRONG_NUMBER: 'rose',
    COMPLEX_REQUEST: 'gold'
};

export async function renderDashboard(host, ctx) {
    const summary = await api.get('/api/metrics/summary');
    host.appendChild(buildStats(summary, ctx));
    host.appendChild(buildCapabilities(summary, ctx));
    host.appendChild(buildModes(summary, ctx));
    host.appendChild(buildRecentCalls(summary, ctx));
}

// -------------------------------------------------------------- the numbers --

function buildStats(summary, ctx) {
    const t = ctx.strings;
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['dash.title']));
    panel.appendChild(element('p', 'muted-body', t['dash.note']));

    const strip = element('div', 'stat-strip');
    strip.append(
        statTile(t['dash.businesses'], summary.businesses),
        statTile(t['dash.knowledge'], summary.knowledgeEntries),
        statTile(t['dash.customers'], summary.customers),
        statTile(t['dash.calls_total'], summary.callsTotal),
        statTile(t['dash.calls_today'], summary.callsToday),
        replyTile(t['dash.median_reply'], summary.medianReplyMs, t, true),
        replyTile(t['dash.slowest_tenth'], summary.slowestTenthReplyMs, t, false)
    );
    panel.appendChild(strip);
    return panel;
}

/**
 * How long the agent takes to answer: usually, and at its worst.
 *
 * Only the median may wear the accent — the contract allows exactly one
 * accented number on a screen, and this is the objective the whole design is
 * arranged around. It loses the accent the moment it goes over target, which is
 * the only honest way to show a target.
 *
 * @param headline true for the one tile allowed to use the accent
 */
function replyTile(label, milliseconds, t, headline) {
    if (milliseconds == null) {
        return statTile(label, t['dash.no_calls_yet'], { quiet: true });
    }
    return statTile(label, `${milliseconds} ms`,
        { accent: headline && milliseconds <= LATENCY_TARGET_MS });
}

// ------------------------------------------------------ how calls end up --

/**
 * The four kinds of call, and how many there have been of each.
 *
 * The bar is drawn as a share of the busiest kind rather than of the total, so
 * that with three calls on the system the picture still says something.
 */
function buildModes(summary, ctx) {
    const t = ctx.strings;
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['dash.modes']));

    const busiest = Math.max(1, ...summary.modes.map((entry) => entry.calls));
    const list = element('div');
    summary.modes.forEach((entry) => {
        const row = element('div', 'list-row');
        row.appendChild(tagBadge(t[`mode.${entry.mode.toLowerCase()}`] || entry.mode,
            MODE_HUE[entry.mode] || 'azure'));
        row.appendChild(element('span', 'list-row__text', entry.calls));
        row.appendChild(bar(entry.calls / busiest, MODE_HUE[entry.mode]));
        list.appendChild(row);
    });
    panel.appendChild(list);
    return panel;
}

/**
 * One bar. The width is a measurement and is set here; the colour is not, and
 * is chosen by class so that every colour on the panel still comes from the
 * stylesheet.
 */
function bar(share, hue) {
    const track = element('div', 'progress');
    const fill = element('div', `progress__fill progress__fill--${hue || 'azure'}`);
    fill.style.width = `${Math.round(share * 100)}%`;
    track.appendChild(fill);
    return track;
}

// --------------------------------------------------------- what works today --

function buildCapabilities(summary, ctx) {
    const t = ctx.strings;
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['dash.capabilities']));

    const list = element('div');
    summary.capabilities.forEach((capability) => {
        const row = element('div', 'list-row');
        row.appendChild(tagBadge(t[`dash.state_${capability.state}`],
            STATE_HUE[capability.state] || 'azure'));
        row.appendChild(element('span', 'list-row__text',
            t[`dash.cap.${capability.id}`] || capability.id));
        const hint = hintFor(capability, t);
        if (hint) row.appendChild(element('span', 'list-row__hint', hint));
        list.appendChild(row);
    });
    panel.appendChild(list);
    return panel;
}

function hintFor(capability, t) {
    if (capability.state === 'ready') return capability.detail || '';
    if (HINT_FOR[capability.id]) return t[HINT_FOR[capability.id]];
    if (OPTIONAL.includes(capability.id)) return t['dash.hint.optional'];
    return '';
}

// ------------------------------------------------------------ what happened --

function buildRecentCalls(summary, ctx) {
    const t = ctx.strings;
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['dash.recent']));

    if (summary.recentCalls.length === 0) {
        const empty = element('div', 'empty-state');
        empty.appendChild(element('div', 'empty-state__line', t['dash.empty']));
        empty.appendChild(button(t['dash.start_call'], 'primary', () => ctx.goTo('live_call')));
        panel.appendChild(empty);
        return panel;
    }

    panel.appendChild(table([
        { label: t['dash.col_started'], value: (call) => shortTime(call.startedAt) },
        { label: t['dash.col_business'], value: (call) => call.business },
        { label: t['dash.col_mode'], render: (call) => outcomeBadge(call, t) },
        { label: t['dash.col_turns'], numeric: true, value: (call) => call.turns },
        {
            label: t['dash.col_length'],
            numeric: true,
            // A call still running has no end time, and the server leaves the
            // field out rather than sending a null — so == null, not === null.
            value: (call) => call.durationSeconds == null
                ? t['call.still_running'] : `${call.durationSeconds}s`
        },
        {
            label: t['businesses.col_actions'],
            render: (call) => button(t['history.open'], 'ghost',
                () => { window.location.hash = `#/history/${call.id}`; })
        }
    ], summary.recentCalls));
    return panel;
}

function shortTime(isoString) {
    const when = new Date(isoString);
    return Number.isNaN(when.getTime()) ? isoString : when.toLocaleString();
}
