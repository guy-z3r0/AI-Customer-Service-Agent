/*
 * The facts panel on the Live Call page: what this call is, who is on it, and
 * how it is going.
 *
 * Split out of live_call.js when that file passed the five hundred line cap.
 * The line it was split along is the one that was already there — everything
 * here reads the call and draws it, and nothing here dials, listens, speaks or
 * hangs up.
 *
 * Each function takes the call itself rather than a dozen arguments, because
 * the grid is a picture of that whole object and half of it changes on every
 * event.
 */

import { dropdown, keyValueRow, tagBadge } from '../components.js';
import { formatElapsed, formatMedian } from './call_stats.js';

/** The screening modes, in the order the operator's dropdown reads them. */
export const MODES = ['NEW_CUSTOMER', 'EXISTING_CUSTOMER', 'WRONG_NUMBER', 'COMPLEX_REQUEST'];

export const MODE_HUES = {
    NEW_CUSTOMER: 'azure',
    EXISTING_CUSTOMER: 'jade',
    WRONG_NUMBER: 'rose',
    COMPLEX_REQUEST: 'gold'
};

/** Rebuilds the whole grid. It is cheap, and there is exactly one of it. */
export function drawFacts(call) {
    const t = call.t;
    const business = call.ctx.activeBusiness;
    const grid = call.nodes.facts;

    grid.replaceChildren();
    keyValueRow(grid, t['livecall.state'], t[`livecall.state_${call.state}`]);
    keyValueRow(grid, t['livecall.business'], business ? business.name : '—');
    keyValueRow(grid, t['livecall.model'], modelFact(call));
    keyValueRow(grid, t['livecall.language'], languageLabel(call, call.language));
    keyValueRow(grid, t['livecall.caller'], call.caller || t['livecall.caller_unknown']);
    keyValueRow(grid, t['livecall.dial_as'], clientChooser(call));
    keyValueRow(grid, t['livecall.mode'], modeBadge(call));
    keyValueRow(grid, t['livecall.override_mode'], modeChooser(call));
    keyValueRow(grid, t['livecall.call_id'], call.callId || '—');
    keyValueRow(grid, t['livecall.duration'], formatElapsed(call.startedAt, call.endedAt));
    keyValueRow(grid, t['livecall.turns'], String(call.turns));
    keyValueRow(grid, t['livecall.median_latency'], formatMedian(call.latencies));
}

/**
 * Who to place the call as. Picking a customer is the difference between an
 * agent that has to ask who you are and one that greets you by name.
 */
function clientChooser(call) {
    const options = [{ value: '', label: call.t['livecall.dial_as_stranger'] }];
    call.clients.forEach((client) =>
        options.push({ value: client.clientCode, label: `${client.clientCode} — ${client.name}` }));

    const chooser = dropdown(options, call.dialAs, (code) => { call.dialAs = code; });
    chooser.disabled = call.isLive() || call.clients.length === 0;
    return chooser;
}

function modeBadge(call) {
    return tagBadge(modeLabel(call, call.mode), MODE_HUES[call.mode] || 'azure');
}

/** The operator overruling the agent. The server still refuses illegal moves. */
function modeChooser(call) {
    const chooser = dropdown(
        MODES.map((mode) => ({ value: mode, label: modeLabel(call, mode) })),
        call.mode,
        (mode) => call.changeMode(mode));
    chooser.disabled = !call.isLive();
    return chooser;
}

export function modeLabel(call, mode) {
    return call.t[`mode.${String(mode).toLowerCase()}`] || mode;
}

export function languageLabel(call, language) {
    return call.t[`language.${String(language).toLowerCase()}`] || language;
}

/** Which model would answer, and whether it has a key to answer with. */
function modelFact(call) {
    const health = call.ctx.health;
    if (!health) return '—';
    const state = health.llmKeyReady ? call.t['status.ready'] : call.t['status.needs_key'];
    return `${health.llmProvider} — ${state}`;
}
