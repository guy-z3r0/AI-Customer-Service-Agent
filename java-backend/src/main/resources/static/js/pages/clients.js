/*
 * Customers — who the agent recognises, and what it knows about them.
 *
 * Phone numbers and email addresses are encrypted in the database and arrive
 * here as plain text. A record whose contacts cannot be decrypted still shows
 * everything else and says so, because the alternative is a page that dies
 * whole the day somebody changes the PII key.
 */

import { api } from '../api.js';
import { Form, button, confirm, dialog, element, emptyState, table, tagBadge } from '../components.js';

export async function renderClients(host, ctx) {
    const business = ctx.activeBusiness;
    const t = ctx.strings;

    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['clients.title']));

    if (!business) {
        panel.appendChild(emptyState(t['clients.no_business'], t['soon.action'],
            () => ctx.goTo('businesses')));
        host.appendChild(panel);
        return;
    }

    const clients = await api.get(`/api/businesses/${business.id}/clients`);
    panel.appendChild(element('p', 'muted-body', t['clients.note']));

    if (clients.length === 0) {
        panel.appendChild(emptyState(t['clients.empty'], t['clients.new'],
            () => openForm(null, business, ctx)));
        host.appendChild(panel);
        return;
    }

    panel.appendChild(table(columns(ctx, business), clients));
    panel.appendChild(element('hr', 'divider'));

    const actions = element('div', 'row row--end');
    actions.appendChild(button(t['clients.new'], 'primary', () => openForm(null, business, ctx)));
    panel.appendChild(actions);
    host.appendChild(panel);
}

function columns(ctx, business) {
    const t = ctx.strings;
    return [
        { label: t['clients.col_code'], value: (c) => c.clientCode },
        { label: t['clients.col_name'], value: (c) => c.name },
        { label: t['clients.col_phone'], render: (c) => contactCell(c, c.phone, ctx) },
        { label: t['clients.col_email'], render: (c) => contactCell(c, c.email, ctx) },
        { label: t['clients.col_issues'], numeric: true, value: (c) => c.pastIssues.length },
        { label: t['businesses.col_actions'], render: (c) => actionCell(c, business, ctx) }
    ];
}

/**
 * A contact detail, or a badge saying why it is missing. Blank and unreadable
 * look the same in the data and mean very different things to whoever is
 * reading the page.
 */
function contactCell(client, value, ctx) {
    if (value) return element('span', null, value);
    if (client.contactReadable) return element('span', null, '');

    const badge = tagBadge('•••', 'gold');
    badge.title = ctx.strings['clients.contact_hidden'];
    return badge;
}

function actionCell(client, business, ctx) {
    const row = element('div', 'row');
    row.appendChild(button(ctx.strings['common.edit'], 'ghost',
        () => openForm(client, business, ctx)));
    row.appendChild(button(ctx.strings['common.delete'], 'ghost',
        () => remove(client, business, ctx)));
    return row;
}

// ------------------------------------------------------------------- editing --

async function openForm(client, business, ctx) {
    const t = ctx.strings;
    const suggested = client ? client.clientCode : await nextCode(business);

    const form = new Form();
    form.text('clientCode', t['clients.field_code'], suggested);
    form.text('name', t['clients.field_name'], client ? client.name : '');
    form.text('phone', t['clients.field_phone'], client ? client.phone : '');
    form.text('email', t['clients.field_email'], client ? client.email : '');
    form.text('notes', t['clients.field_notes'], client ? client.notes : '', { multiline: true });
    form.text('pastIssues', t['clients.field_issues'],
        client ? client.pastIssues.join('\n') : '',
        { multiline: true, hint: t['clients.issues_hint'] });

    const close = dialog({
        title: t[client ? 'clients.edit_title' : 'clients.new_title'],
        body: form.node,
        actions: [
            { label: t['common.cancel'], kind: 'secondary', onClick: (dismiss) => dismiss() },
            { label: t['common.save'], kind: 'primary', onClick: () => save(client, business, form, ctx, close) }
        ]
    });
}

async function nextCode(business) {
    try {
        return (await api.get(`/api/businesses/${business.id}/clients/next-code`)).clientCode;
    } catch (error) {
        return '';
    }
}

async function save(client, business, form, ctx, close) {
    form.clearErrors();
    const values = form.values();
    const body = {
        ...values,
        pastIssues: values.pastIssues.split('\n').map((line) => line.trim()).filter(Boolean)
    };

    try {
        const path = `/api/businesses/${business.id}/clients`;
        if (client) await api.put(`${path}/${client.id}`, body);
        else await api.post(path, body);
        close();
        ctx.toast(ctx.strings['common.saved'], 'good');
        await ctx.rerender();
    } catch (error) {
        // A 409 is always the code clashing with another customer's; anything
        // else is about the form as a whole and goes under the name.
        form.showError(error.status === 409 ? 'clientCode' : 'name', error.message);
    }
}

async function remove(client, business, ctx) {
    const agreed = await confirm({
        title: ctx.strings['clients.title'],
        line: ctx.strings['clients.delete_line'].replace('%s', client.name),
        proceedLabel: ctx.strings['common.delete'],
        cancelLabel: ctx.strings['common.cancel'],
        destructive: true
    });
    if (!agreed) return;

    try {
        await api.del(`/api/businesses/${business.id}/clients/${client.id}`);
        ctx.toast(ctx.strings['common.deleted'], 'good');
        await ctx.rerender();
    } catch (error) {
        ctx.toastError(error);
    }
}
