/*
 * Call history — every call the agent has taken, and one of them in full.
 *
 * Two views behind one route. The list is at #/history; a single call is at
 * #/history/<id>, so a call can be linked to, reloaded, and shared with
 * somebody who was not watching when it happened.
 *
 * Nothing on this page writes anything. A transcript is a record, and the only
 * thing you can do to one here is read it or take a copy away.
 */

import { api } from '../api.js';
import { modeBadge, outcomeBadge } from '../call_outcome.js';
import { button, element, emptyState, keyValueRow, table, tagBadge } from '../components.js';

const ROLE_HUES = { CALLER: 'azure', AGENT: 'jade', SYSTEM: 'violet' };
const GOOD_LATENCY_MS = 2000;

export async function renderHistory(host, ctx, params) {
    const callId = params && params[0];
    if (callId) {
        await renderOneCall(host, ctx, callId);
        return;
    }
    await renderList(host, ctx);
}

// --------------------------------------------------------------- the list --

async function renderList(host, ctx) {
    const t = ctx.strings;
    const calls = await api.get('/api/calls');

    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['history.title']));
    panel.appendChild(element('p', 'muted-body', t['history.note']));

    if (calls.length === 0) {
        panel.appendChild(emptyState(t['history.empty'], t['dash.start_call'],
            () => ctx.goTo('live_call')));
        host.appendChild(panel);
        return;
    }

    panel.appendChild(table(listColumns(ctx), calls));
    host.appendChild(panel);
}

function listColumns(ctx) {
    const t = ctx.strings;
    return [
        { label: t['history.col_started'], value: (call) => shortTime(call.startedAt) },
        { label: t['history.col_business'], value: (call) => call.business },
        {
            label: t['history.col_caller'],
            value: (call) => call.caller || t['call.not_recognised']
        },
        { label: t['history.col_mode'], render: (call) => outcomeBadge(call, t) },
        { label: t['history.col_turns'], numeric: true, value: (call) => call.turns },
        {
            label: t['history.col_length'],
            numeric: true,
            value: (call) => call.durationSeconds == null
                ? t['call.still_running'] : `${call.durationSeconds}s`
        },
        {
            label: t['history.col_summary'],
            value: (call) => call.summarised ? t['history.summarised'] : t['history.not_summarised']
        },
        {
            label: t['businesses.col_actions'],
            render: (call) => button(t['history.open'], 'ghost',
                () => { window.location.hash = `#/history/${call.id}`; })
        }
    ];
}

// ------------------------------------------------------------- one call --

async function renderOneCall(host, ctx, callId) {
    const t = ctx.strings;
    const detail = await api.get(`/api/calls/${callId}`);

    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['history.detail_title']));
    panel.appendChild(buildFacts(detail, t));

    const actions = element('div', 'row');
    actions.appendChild(button(t['history.back'], 'secondary', () => ctx.goTo('history')));
    actions.appendChild(downloadLink(callId, t));
    panel.appendChild(actions);
    host.appendChild(panel);

    host.appendChild(buildSummary(detail, t));
    host.appendChild(buildScreening(detail, t));
    host.appendChild(buildTranscript(detail, t));
}

function buildFacts(detail, t) {
    const call = detail.call;
    const grid = element('div', 'key-value');
    keyValueRow(grid, t['history.col_started'], shortTime(call.startedAt));
    keyValueRow(grid, t['history.col_business'], call.business);
    keyValueRow(grid, t['history.col_caller'], call.caller || t['call.not_recognised']);
    keyValueRow(grid, t['history.col_mode'], outcomeBadge(call, t));
    keyValueRow(grid, t['livecall.language'], t[`language.${(call.language || 'EN').toLowerCase()}`]);
    keyValueRow(grid, t['history.col_turns'], call.turns);
    keyValueRow(grid, t['history.col_length'], call.durationSeconds == null
        ? t['call.still_running'] : `${call.durationSeconds}s`);
    keyValueRow(grid, t['history.reason'], detail.terminationReason || '');
    return grid;
}

/**
 * The download is an anchor rather than a button that fetches: the server
 * already names the file and marks it as an attachment, so letting the browser
 * do what it is good at leaves nothing to go wrong in between.
 */
function downloadLink(callId, t) {
    const link = element('a', 'button button--primary', t['history.export']);
    link.href = `/api/calls/${callId}/export`;
    link.setAttribute('download', '');
    return link;
}

function buildSummary(detail, t) {
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['history.summary']));

    if (!detail.summary) {
        panel.appendChild(element('p', 'muted-body', t['history.no_summary']));
        return panel;
    }

    panel.appendChild(element('p', null, detail.summary.text));
    const fields = Object.entries(detail.summary.structured || {});
    if (fields.length > 0) {
        panel.appendChild(element('div', 'section-header', t['history.structured']));
        const grid = element('div', 'key-value');
        fields.forEach(([name, value]) => keyValueRow(grid, readableWords(name), value));
        panel.appendChild(grid);
    }

    panel.appendChild(element('div', 'section-header', t['history.actions']));
    const items = detail.summary.actionItems || [];
    if (items.length === 0) {
        panel.appendChild(element('p', 'muted-body', t['history.no_actions']));
    } else {
        const list = element('div');
        items.forEach((item) => {
            const row = element('div', 'list-row');
            row.appendChild(element('span', 'list-row__text', item));
            list.appendChild(row);
        });
        panel.appendChild(list);
    }
    return panel;
}

function buildScreening(detail, t) {
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['history.screening']));

    const list = element('div');
    detail.transitions.forEach((step) => {
        const row = element('div', 'list-row');
        row.appendChild(modeBadge(step.toMode, t));
        row.appendChild(element('span', 'list-row__text', step.reason || ''));
        row.appendChild(element('span', 'list-row__hint', shortTime(step.at)));
        list.appendChild(row);
    });
    panel.appendChild(list);
    return panel;
}

function buildTranscript(detail, t) {
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['history.transcript']));

    const region = element('div', 'transcript scroll-region');
    detail.lines.forEach((line) => {
        const row = element('div', 'list-row');
        row.appendChild(tagBadge(t[`livecall.role_${line.role.toLowerCase()}`] || line.role,
            ROLE_HUES[line.role] || 'azure'));
        row.appendChild(element('span', 'list-row__text', line.text));
        if (line.replyMs != null) {
            row.appendChild(tagBadge(`${line.replyMs} ms`,
                line.replyMs <= GOOD_LATENCY_MS ? 'jade' : 'rose'));
        }
        region.appendChild(row);
    });
    panel.appendChild(region);
    return panel;
}

// ------------------------------------------------------------- internals --

function shortTime(isoString) {
    const when = new Date(isoString);
    return Number.isNaN(when.getTime()) ? isoString : when.toLocaleString();
}

/** mode_path reads better as "Mode path" than as a key from a JSON object. */
function readableWords(key) {
    const words = key.replace(/_/g, ' ');
    return words.charAt(0).toUpperCase() + words.slice(1);
}
