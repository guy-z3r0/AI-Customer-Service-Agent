/*
 * The call report — many calls read at once, and the one page in this panel
 * that is meant to leave it.
 *
 * Everything here is arranged to survive being printed: the numbers first, the
 * breakdowns they came from next, the work the calls left behind, and last the
 * calls themselves so that every figure above can be checked against the rows
 * it was counted from. Nothing on the page can be clicked into, on purpose —
 * a document that only works while you are sitting in front of the app is not
 * a document. Following a call up is what the history page is for.
 *
 * The range and the business live in the address rather than in controls on
 * this page, because a control on the page would print as part of the report.
 */

import { api } from '../api.js';
import { outcomeBadge } from '../call_outcome.js';
import { button, element, emptyState, statTile, table, tagBadge } from '../components.js';
import { EVERY_BUSINESS, openReportRange } from './report_range.js';

const LATENCY_TARGET_MS = 2000;
const SECONDS_PER_HOUR = 3600;

// The same colour for a screening mode wherever it appears — here, on the
// dashboard, on the live call and in the history — so the kinds of call are
// learned once. NO_ANSWER is not a mode; it is what CallReportService reports
// for a call nobody spoke on.
const OUTCOME_HUE = {
    NEW_CUSTOMER: 'azure',
    EXISTING_CUSTOMER: 'jade',
    WRONG_NUMBER: 'rose',
    COMPLEX_REQUEST: 'gold',
    NO_ANSWER: 'rose'
};

const LANGUAGE_HUE = { EN: 'azure', BN: 'violet' };

export async function renderReport(host, ctx, params) {
    const [from, to, businessId] = params || [];
    const report = await api.get(`/api/calls/report${queryFor(from, to, businessId)}`);

    host.appendChild(buildHead(report, ctx));
    if (report.calls.length === 0) {
        host.appendChild(buildEmpty(ctx));
        return;
    }

    host.appendChild(countPanel(ctx.strings['report.outcomes'], report.outcomes,
        (entry) => outcomeName(entry.key, ctx.strings), (entry) => OUTCOME_HUE[entry.key]));
    host.appendChild(countPanel(ctx.strings['report.languages'], report.languages,
        (entry) => ctx.strings[`language.${entry.key.toLowerCase()}`] || entry.key,
        (entry) => LANGUAGE_HUE[entry.key]));
    host.appendChild(countPanel(ctx.strings['report.by_day'], report.byDay,
        (entry) => entry.key, () => null));
    host.appendChild(buildFollowUps(report, ctx));
    host.appendChild(buildCalls(report, ctx));
}

/**
 * A report asked for without a range is the last thirty days, which is the
 * server's own answer to the same question — so the address bar may be empty
 * and the page still means something.
 */
function queryFor(from, to, businessId) {
    const asked = [];
    if (from) asked.push(`from=${encodeURIComponent(from)}`);
    if (to) asked.push(`to=${encodeURIComponent(to)}`);
    if (businessId && businessId !== EVERY_BUSINESS) {
        asked.push(`businessId=${encodeURIComponent(businessId)}`);
    }
    return asked.length === 0 ? '' : `?${asked.join('&')}`;
}

// ------------------------------------------------------------ the document --

/**
 * What the report is of, and the numbers that answer it at a glance.
 *
 * The heading block says which business and which days before anything is
 * counted, because a printed page has no address bar to say so.
 */
function buildHead(report, ctx) {
    const t = ctx.strings;
    const panel = element('div', 'panel');

    panel.appendChild(element('div', 'section-header', t['report.title']));
    panel.appendChild(element('p', 'muted-body', t['report.note']));
    panel.appendChild(element('p', null, report.business || t['history.all_businesses']));
    panel.appendChild(element('p', 'muted-body',
        t['report.range'].replace('%s', report.from).replace('%s', report.to)));
    panel.appendChild(element('p', 'caption muted-body',
        t['report.generated'].replace('%s', shortTime(report.generatedAt))));

    panel.appendChild(buildNumbers(report.totals, t));
    panel.appendChild(buildActions(ctx));
    return panel;
}

function buildNumbers(totals, t) {
    const strip = element('div', 'stat-strip');
    strip.append(
        statTile(t['report.calls_total'], totals.calls),
        statTile(t['report.answered'], totals.answered),
        statTile(t['report.no_answer'], totals.noAnswer),
        statTile(t['report.escalated'], totals.escalated),
        statTile(t['report.written_up'], totals.summarised),
        statTile(t['report.callers'], totals.callersRecognised),
        statTile(t['report.talk_time'], formatTalkTime(totals.talkSeconds, t)),
        replyTile(t['report.median_reply'], totals.medianReplyMs, t, true),
        replyTile(t['report.slowest_tenth'], totals.slowestTenthReplyMs, t, false)
    );
    return strip;
}

/**
 * Only the median may wear the accent — the contract allows one accented
 * number on a screen — and it loses it the moment it goes over the two-second
 * objective, which is the only honest way to show a target.
 */
function replyTile(label, milliseconds, t, headline) {
    if (milliseconds == null) return statTile(label, t['dash.no_calls_yet'], { quiet: true });
    return statTile(label, `${milliseconds} ms`,
        { accent: headline && milliseconds <= LATENCY_TARGET_MS });
}

/** The three things to do with a report, and the only part of it that is not printed. */
function buildActions(ctx) {
    const t = ctx.strings;
    const actions = element('div', 'row no-print');
    actions.appendChild(button(t['report.back'], 'secondary', () => ctx.goTo('history')));
    actions.appendChild(button(t['report.change_range'], 'secondary',
        () => openReportRange(ctx).catch((error) => ctx.toastError(error))));
    actions.appendChild(button(t['report.print'], 'primary', () => window.print()));
    return actions;
}

function buildEmpty(ctx) {
    const t = ctx.strings;
    const panel = element('div', 'panel');
    panel.appendChild(emptyState(t['report.empty'], t['report.change_range'],
        () => openReportRange(ctx).catch((error) => ctx.toastError(error))));
    return panel;
}

// ---------------------------------------------------------- the breakdowns --

/**
 * One breakdown: a label, how many calls, and a bar.
 *
 * The bar is drawn as a share of the largest row rather than of the total, so
 * that a report covering three calls still says something. It is a picture of
 * the number beside it and carries no information of its own, which is why the
 * printed sheet drops it rather than printing an outline of nothing.
 *
 * @param hueOf a data-palette colour for the row, or null for a plain label
 */
function countPanel(title, entries, labelOf, hueOf) {
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', title));

    const busiest = Math.max(1, ...entries.map((entry) => entry.calls));
    const list = element('div');
    entries.forEach((entry) => {
        const hue = hueOf(entry);
        const row = element('div', 'list-row');
        row.appendChild(hue
            ? tagBadge(labelOf(entry), hue)
            : element('span', 'mono', labelOf(entry)));
        row.appendChild(element('span', 'list-row__text', entry.calls));
        row.appendChild(bar(entry.calls / busiest, hue || 'azure'));
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
    const fill = element('div', `progress__fill progress__fill--${hue}`);
    fill.style.width = `${Math.round(share * 100)}%`;
    track.appendChild(fill);
    return track;
}

// ------------------------------------------------------- what is left to do --

function buildFollowUps(report, ctx) {
    const t = ctx.strings;
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['report.follow_ups']));

    if (report.followUps.length === 0) {
        panel.appendChild(element('p', 'muted-body', t['report.no_follow_ups']));
        return panel;
    }

    const list = element('div');
    report.followUps.forEach((item) => {
        const row = element('div', 'list-row');
        row.appendChild(element('span', 'list-row__text', item.item));
        // Which call it came from. The business is named only when the report
        // covers more than one, where it is the part that tells them apart.
        row.appendChild(element('span', 'list-row__hint', report.business
            ? shortTime(item.startedAt)
            : `${item.business} — ${shortTime(item.startedAt)}`));
        list.appendChild(row);
    });

    panel.appendChild(list);
    return panel;
}

// ------------------------------------------------------------- every call --

function buildCalls(report, ctx) {
    const t = ctx.strings;
    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['report.calls']));
    panel.appendChild(table(callColumns(report, t), report.calls));
    return panel;
}

/**
 * The business column appears only in a report covering more than one. In a
 * single business's report it would be the same name on every row, which is a
 * column that costs width and says nothing.
 */
function callColumns(report, t) {
    const columns = [
        { label: t['history.col_started'], value: (call) => shortTime(call.startedAt) }
    ];
    if (!report.business) {
        columns.push({ label: t['history.col_business'], value: (call) => call.business });
    }

    return columns.concat([
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
        }
    ]);
}

// -------------------------------------------------------------- internals --

/** The fifth outcome is not a screening mode, so it is not named like one. */
function outcomeName(key, t) {
    return key === 'NO_ANSWER' ? t['outcome.no_answer'] : t[`mode.${key.toLowerCase()}`] || key;
}

/**
 * Time on calls as a duration rather than as a clock: 2h 14m is a length,
 * while 2:14:00 could be a time of day.
 */
function formatTalkTime(seconds, t) {
    const hours = Math.floor(seconds / SECONDS_PER_HOUR);
    const minutes = Math.round((seconds % SECONDS_PER_HOUR) / 60);
    return hours > 0
        ? t['report.hours_minutes'].replace('%s', hours).replace('%s', minutes)
        : t['report.minutes'].replace('%s', minutes);
}

function shortTime(isoString) {
    const when = new Date(isoString);
    return Number.isNaN(when.getTime()) ? isoString : when.toLocaleString();
}
