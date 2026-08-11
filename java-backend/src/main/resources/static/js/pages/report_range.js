/*
 * Choosing what a report covers: which days, and which business.
 *
 * This is a dialog rather than a row of controls on the report itself because
 * the report is a document. It should print exactly as it was asked for, and a
 * page with a date picker still on it prints the date picker.
 *
 * The answer goes into the address — #/report/<from>/<to>/<business> — so a
 * report can be reloaded, kept in a browser tab, or sent to a colleague as a
 * link and come back the same report.
 */

import { api } from '../api.js';
import { dialog, element, Form } from '../components.js';

/** What stands in the address for a report covering every business. */
export const EVERY_BUSINESS = 'all';

// The same reach as CallReportService.DEFAULT_DAYS, so the dialog opens on the
// range the server would have chosen if nobody had been asked.
const DEFAULT_DAYS = 30;

export async function openReportRange(ctx) {
    const t = ctx.strings;
    const businesses = await api.get('/api/businesses');
    const today = new Date();

    const form = new Form();
    form.text('from', t['report.field_from'], isoDay(addDays(today, 1 - DEFAULT_DAYS)),
        { type: 'date' });
    form.text('to', t['report.field_to'], isoDay(today), { type: 'date' });
    form.select('businessId', t['report.field_business'], businessOptions(businesses, t),
        EVERY_BUSINESS);

    const body = element('div');
    body.appendChild(element('p', 'muted-body', t['report.dialog_note']));
    body.appendChild(form.node);

    dialog({
        title: t['report.dialog_title'],
        body,
        actions: [
            { label: t['common.cancel'], kind: 'secondary', onClick: (close) => close() },
            { label: t['report.make'], kind: 'primary', onClick: (close) => submit(form, t, close) }
        ]
    });
}

/**
 * The dialog closes only once the range makes sense, so a mistake is corrected
 * where it was made rather than on a report that came back empty.
 */
function submit(form, t, close) {
    form.clearErrors();
    const { from, to, businessId } = form.values();

    if (!from || !to) {
        form.showError(from ? 'to' : 'from', t['report.needs_a_day']);
        return;
    }
    // Both are yyyy-mm-dd, which compares as text exactly as it does as a date.
    if (from > to) {
        form.showError('from', t['report.bad_range']);
        return;
    }

    close();
    window.location.hash = `#/report/${from}/${to}/${businessId || EVERY_BUSINESS}`;
}

function businessOptions(businesses, t) {
    const every = { value: EVERY_BUSINESS, label: t['history.all_businesses'] };
    return [every].concat(businesses.map((business) => ({
        value: business.id,
        label: business.name
    })));
}

/**
 * A date as the day it is here, not the day it is in UTC.
 *
 * toISOString would hand back yesterday for anyone east of Greenwich after
 * six in the evening, which in Dhaka is most of a working day.
 */
function isoDay(date) {
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${date.getFullYear()}-${month}-${day}`;
}

function addDays(date, days) {
    const moved = new Date(date);
    moved.setDate(moved.getDate() + days);
    return moved;
}
