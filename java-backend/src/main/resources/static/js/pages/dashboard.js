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
import { button, element, table, tagBadge } from '../components.js';

const LATENCY_TARGET_MS = 2000;

const STATE_HUE = { ready: 'jade', degraded: 'gold', off: 'rose' };
const HINT_FOR = { model: 'dash.hint.model', speech: 'dash.hint.speech' };
const OPTIONAL = ['telephony', 'email'];

export async function renderDashboard(host, ctx) {
    const summary = await api.get('/api/metrics/summary');
    host.appendChild(buildStats(summary, ctx));
    host.appendChild(buildCapabilities(summary, ctx));
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
        replyTile(summary, t)
    );
    panel.appendChild(strip);
    return panel;
}

function statTile(label, value) {
    const tile = element('div', 'stat-tile');
    tile.appendChild(element('div', 'stat-tile__label', label));
    tile.appendChild(element('div', 'stat-tile__value', value));
    return tile;
}

/**
 * The one number on the screen allowed to use the accent colour: how long the
 * agent takes to answer. It is the objective the whole design is arranged
 * around, so it is the number that gets to stand out.
 */
function replyTile(summary, t) {
    const tile = element('div', 'stat-tile');
    tile.appendChild(element('div', 'stat-tile__label', t['dash.median_reply']));

    if (summary.medianReplyMs == null) {
        tile.appendChild(element('div', 'stat-tile__value stat-tile__value--quiet',
            t['dash.no_calls_yet']));
        return tile;
    }
    const value = element('div', 'stat-tile__value', `${summary.medianReplyMs} ms`);
    if (summary.medianReplyMs <= LATENCY_TARGET_MS) value.classList.add('stat-tile__value--accent');
    tile.appendChild(value);
    return tile;
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
        { label: t['dash.col_mode'], value: (call) => readableMode(call.mode) },
        { label: t['dash.col_turns'], numeric: true, value: (call) => call.turns },
        {
            label: t['dash.col_length'],
            numeric: true,
            // A call still running has no end time, and the server leaves the
            // field out rather than sending a null — so == null, not === null.
            value: (call) => call.durationSeconds == null
                ? t['dash.in_progress'] : `${call.durationSeconds}s`
        }
    ], summary.recentCalls));
    return panel;
}

function shortTime(isoString) {
    const when = new Date(isoString);
    return Number.isNaN(when.getTime()) ? isoString : when.toLocaleString();
}

/** NEW_CUSTOMER reads better as "New customer" than as an enum name. */
function readableMode(mode) {
    if (!mode) return '';
    const words = mode.toLowerCase().replace(/_/g, ' ');
    return words.charAt(0).toUpperCase() + words.slice(1);
}
