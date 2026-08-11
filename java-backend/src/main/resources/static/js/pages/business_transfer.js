/*
 * Moving a whole business in and out as a file.
 *
 * Setting one up is an afternoon of typing, and this is how that afternoon
 * travels — to another machine, into a backup, or to somebody starting from
 * yours instead of from an empty editor.
 *
 * It is its own module rather than more of businesses.js because it is a
 * different job from the one that page does: that page owns the row, this owns
 * the file.
 */

import { api } from '../api.js';
import { Form, dialog, element } from '../components.js';

/**
 * The download is an anchor, not a button that fetches: the server already
 * names the file and marks it as an attachment, so the browser is left to do
 * the part it is good at. Same arrangement as the call transcript download.
 */
export function downloadLink(business, t) {
    const link = element('a', 'button button--ghost', t['transfer.download']);
    link.href = `/api/businesses/${business.id}/export`;
    link.setAttribute('download', '');
    return link;
}

/** Opens the file picker, then the dialog, once a file has been chosen. */
export function pickFile(ctx, businesses) {
    const picker = element('input');
    picker.type = 'file';
    picker.accept = 'application/json,.json';
    picker.addEventListener('change', async () => {
        const file = picker.files && picker.files[0];
        if (file) await openImport(ctx, businesses, await file.text());
    });
    picker.click();
}

/**
 * What to do with the file: add it, or overwrite a business that exists.
 *
 * The document is parsed here rather than posted as text, so a file that is not
 * JSON at all is refused in front of the person who chose it instead of coming
 * back as a server error about a field they have never heard of.
 */
async function openImport(ctx, businesses, text) {
    const t = ctx.strings;
    let document_;
    try {
        document_ = JSON.parse(text);
    } catch (notJson) {
        ctx.toast(t['transfer.not_json'], 'bad');
        return;
    }

    const form = new Form();
    const modes = [{ value: 'add', label: t['transfer.mode_add'] }];
    // Replacing is only offered when there is something to replace, rather than
    // as a choice that can only end in the server refusing it.
    if (businesses.length > 0) modes.push({ value: 'replace', label: t['transfer.mode_replace'] });
    form.select('mode', t['transfer.mode'], modes, 'add');

    const body = element('div');
    body.appendChild(element('p', 'muted-body', t['transfer.note']));
    if (businesses.length > 0) {
        form.select('targetId', t['transfer.target'],
            businesses.map((business) => ({ value: business.id, label: business.name })),
            businesses[0].id);
    }
    body.appendChild(form.node);
    if (businesses.length > 0) {
        body.appendChild(element('p', 'caption muted-body', t['transfer.replace_warning']));
    }

    const close = dialog({
        title: t['transfer.title'],
        body,
        actions: [
            { label: t['common.cancel'], kind: 'secondary', onClick: (dismiss) => dismiss() },
            {
                label: t['common.save'],
                kind: 'primary',
                onClick: () => send(ctx, form, document_, close)
            }
        ]
    });
}

async function send(ctx, form, document_, close) {
    form.clearErrors();
    const values = form.values();
    try {
        const result = await api.post('/api/businesses/import', {
            mode: values.mode,
            // Only meaningful for a replace, and absent entirely when there is
            // nothing on this installation to replace.
            targetId: values.targetId || null,
            document: document_
        });
        close();
        ctx.toast(ctx.strings['transfer.imported']
            .replace('%s', result.name)
            .replace('%s', result.knowledgeEntries), 'good');
        await ctx.reloadShell();
        await ctx.rerender();
    } catch (error) {
        form.showError('mode', error.detail || error.message);
    }
}
