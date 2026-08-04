/*
 * Settings — every global value the app reads, editable while it runs.
 *
 * Two rules shape this page:
 *   - A key still holding its PLACEHOLDER_ stand-in wears a badge, so what is
 *     left to set up is visible at a glance.
 *   - A secret is never shown in full. Its field starts empty; leaving it empty
 *     on save keeps whatever is already stored.
 */

import { api } from '../api.js';
import { button, element, keyValueRow, tagBadge } from '../components.js';

const GROUP_ORDER = ['llm', 'voice', 'call', 'twilio', 'email', 'other'];

export async function renderSettings(host, ctx) {
    const entries = await api.get('/api/config');
    const t = ctx.strings;

    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['settings.title']));
    panel.appendChild(element('p', 'muted-body', t['settings.note']));

    const form = element('form');
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        save(form, entries, ctx);
    });

    GROUP_ORDER.forEach((group) => {
        const inGroup = entries.filter((entry) => entry.group === group);
        if (inGroup.length === 0) return;
        form.appendChild(element('div', 'section-header', t[`settings.group.${group}`]));
        const grid = element('div', 'key-value');
        inGroup.forEach((entry) => keyValueRow(grid, label(entry, ctx), field(entry, ctx)));
        form.appendChild(grid);
    });

    form.appendChild(element('hr', 'divider'));
    const actions = element('div', 'row row--end');
    actions.appendChild(button(t['settings.save'], 'primary', () => form.requestSubmit()));
    form.appendChild(actions);

    panel.appendChild(form);
    host.appendChild(panel);
}

/** The readable name, plus the amber badge while the value is a placeholder. */
function label(entry, ctx) {
    const wrap = element('span');
    wrap.appendChild(element('span', null, ctx.strings[`key.${entry.key}`] || entry.key));
    if (entry.placeholder) {
        wrap.appendChild(document.createTextNode(' '));
        wrap.appendChild(tagBadge(ctx.strings['settings.badge_placeholder'], 'gold'));
    }
    return wrap;
}

function field(entry, ctx) {
    const wrap = element('div');
    const input = element('input', 'text-field');
    input.name = entry.key;
    input.autocomplete = 'off';

    if (entry.secret && !entry.placeholder) {
        // The stored value is real, so the field shows only its mask and starts
        // empty — typing nothing is how you keep the credential you already have.
        input.value = '';
        input.placeholder = entry.value;
        input.title = ctx.strings['settings.secret_hint'];
        wrap.appendChild(input);
        return wrap;
    }

    input.value = entry.value;
    wrap.appendChild(input);
    return wrap;
}

async function save(form, entries, ctx) {
    const values = {};
    entries.forEach((entry) => {
        const input = form.elements[entry.key];
        if (!input) return;
        // A blank secret means "keep what is stored"; the server skips it too,
        // but sending nothing keeps the intent obvious in the request itself.
        if (entry.secret && input.value.trim() === '') return;
        values[entry.key] = input.value;
    });

    try {
        const result = await api.put('/api/config', values);
        const message = result.changed === 0
            ? ctx.strings['settings.no_changes']
            : ctx.strings['settings.saved'].replace('%s', result.changed);
        ctx.toast(message, result.changed === 0 ? 'info' : 'good');
        await ctx.rerender();
    } catch (error) {
        ctx.toastError(error);
    }
}
