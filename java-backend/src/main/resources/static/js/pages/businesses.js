/*
 * Businesses — everyone the agent can answer for.
 *
 * This page owns the row: name, contact details, and which one is active.
 * Everything a call actually reads — what the business may say, who the agent
 * is, when it is open — lives one click away in the editor, because that is a
 * screen's worth of forms rather than a row's worth of fields.
 */

import { api } from '../api.js';
import { Form, button, confirm, dialog, element, emptyState, table, tagBadge } from '../components.js';
import { downloadLink, pickFile } from './business_transfer.js';

export async function renderBusinesses(host, ctx) {
    const businesses = await api.get('/api/businesses');
    const t = ctx.strings;

    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['businesses.title']));
    panel.appendChild(element('p', 'muted-body', t['businesses.note']));

    if (businesses.length === 0) {
        panel.appendChild(emptyState(t['businesses.empty'], t['businesses.new'],
            () => openForm(null, ctx)));
    } else {
        panel.appendChild(table(columns(ctx), businesses));
    }

    panel.appendChild(element('hr', 'divider'));
    panel.appendChild(actionRow(ctx, businesses));
    host.appendChild(panel);
}

/**
 * The actions are shown even with nothing in the table: an empty database is
 * exactly when somebody has a setup file and no business to put it beside.
 */
function actionRow(ctx, businesses) {
    const t = ctx.strings;
    const actions = element('div', 'row row--end');
    actions.appendChild(button(t['businesses.import'], 'ghost', () => runImport(ctx)));
    actions.appendChild(button(t['transfer.upload'], 'secondary', () => pickFile(ctx, businesses)));
    actions.appendChild(button(t['businesses.new'], 'primary', () => openForm(null, ctx)));
    return actions;
}

function columns(ctx) {
    const t = ctx.strings;
    return [
        { label: t['businesses.col_name'], value: (b) => b.name },
        { label: t['businesses.col_slug'], value: (b) => b.slug },
        { label: t['businesses.col_contact'], value: (b) => b.phone || b.email || '' },
        { label: t['businesses.col_kb'], numeric: true, value: (b) => b.kbEntryCount },
        { label: t['businesses.col_clients'], numeric: true, value: (b) => b.clientCount },
        { label: t['businesses.col_state'], render: (b) => stateCell(b, ctx) },
        { label: t['businesses.col_actions'], render: (b) => actionCell(b, ctx) }
    ];
}

/** The active business shows a badge; every other row offers to become active. */
function stateCell(business, ctx) {
    if (business.active) return tagBadge(ctx.strings['businesses.badge_active'], 'jade');
    return button(ctx.strings['businesses.activate'], 'ghost', () => activate(business, ctx));
}

function actionCell(business, ctx) {
    const row = element('div', 'row');
    row.appendChild(button(ctx.strings['businesses.open_editor'], 'secondary',
        () => ctx.goTo(`business_editor/${business.id}`)));
    row.appendChild(button(ctx.strings['common.edit'], 'ghost', () => openForm(business, ctx)));
    row.appendChild(downloadLink(business, ctx.strings));
    row.appendChild(button(ctx.strings['common.delete'], 'ghost', () => remove(business, ctx)));
    return row;
}

// ------------------------------------------------------------------- editing --

function openForm(business, ctx) {
    const t = ctx.strings;
    const form = new Form();
    form.text('name', t['businesses.field_name'], business ? business.name : '');
    form.text('phone', t['businesses.field_phone'], business ? business.phone : '');
    form.text('email', t['businesses.field_email'], business ? business.email : '');
    form.text('address', t['businesses.field_address'], business ? business.address : '');
    form.text('timezone', t['businesses.field_timezone'],
        business ? business.timezone : 'Asia/Dhaka');

    const close = dialog({
        title: t[business ? 'businesses.edit_title' : 'businesses.new_title'],
        body: form.node,
        actions: [
            { label: t['common.cancel'], kind: 'secondary', onClick: (dismiss) => dismiss() },
            { label: t['common.save'], kind: 'primary', onClick: () => save(business, form, ctx, close) }
        ]
    });
}

async function save(business, form, ctx, close) {
    form.clearErrors();
    const body = form.values();
    // The hours belong to the editor's own tab; sending nothing keeps them.
    try {
        const saved = business
            ? await api.put(`/api/businesses/${business.id}`, body)
            : await api.post('/api/businesses', body);
        close();
        ctx.toast(ctx.strings[business ? 'businesses.saved' : 'businesses.created']
            .replace('%s', saved.name), 'good');
        await ctx.reloadShell();
        await ctx.rerender();
    } catch (error) {
        form.showError('name', error.detail || error.message);
    }
}

/**
 * Deleting takes the knowledge, the customers and the call history with it —
 * the foreign keys cascade — so the confirmation says so rather than asking
 * whether you are sure.
 */
async function remove(business, ctx) {
    const agreed = await confirm({
        title: ctx.strings['businesses.delete_title'],
        line: ctx.strings['businesses.delete_line'].replace('%s', business.name),
        proceedLabel: ctx.strings['common.delete'],
        cancelLabel: ctx.strings['common.cancel'],
        destructive: true
    });
    if (!agreed) return;

    try {
        await api.del(`/api/businesses/${business.id}`);
        ctx.toast(ctx.strings['common.deleted'], 'good');
        await ctx.reloadShell();
        await ctx.rerender();
    } catch (error) {
        ctx.toastError(error);
    }
}

async function activate(business, ctx) {
    try {
        await api.post(`/api/businesses/${business.id}/activate`);
        ctx.toast(ctx.strings['businesses.activated'].replace('%s', business.name), 'good');
        await ctx.reloadShell();
        await ctx.rerender();
    } catch (error) {
        ctx.toastError(error);
    }
}

async function runImport(ctx) {
    try {
        const result = await api.post('/api/import/legacy');
        ctx.toast(ctx.strings['businesses.import_done']
            .replace('%s', result.imported.length)
            .replace('%s', result.skipped.length), 'good');
        await ctx.reloadShell();
        await ctx.rerender();
    } catch (error) {
        ctx.toastError(error);
    }
}
