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
import { button, dropdown, element, keyValueRow, tagBadge } from '../components.js';

const GROUP_ORDER = ['llm', 'voice', 'call', 'twilio', 'email', 'other'];

// The two settings that name a voice. They get a menu of what is really
// installed rather than a blank box, because nobody can be expected to know
// what a voice on this machine is called.
const VOICE_KEYS = { tts_voice_en: 'en', tts_voice_bn: 'bn' };

export async function renderSettings(host, ctx) {
    const entries = await api.get('/api/config');
    const catalogue = await loadVoices();
    const t = ctx.strings;

    const panel = element('div', 'panel');
    panel.appendChild(element('div', 'section-header', t['settings.title']));
    panel.appendChild(element('p', 'muted-body', t['settings.note']));
    credentialWarnings(catalogue, t).forEach((line) => panel.appendChild(line));
    const warning = missingVoiceWarning(catalogue, t);
    if (warning) panel.appendChild(warning);

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
        inGroup.forEach((entry) => keyValueRow(grid, label(entry, ctx),
            field(entry, ctx, catalogue)));
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

/**
 * The voices this machine can really speak with, or nothing if the voice
 * server is down — in which case the fields stay the text boxes they were.
 */
async function loadVoices() {
    try {
        return await api.get('/api/config/voices');
    } catch (error) {
        return { voices: [], speaks: [], provider: 'unknown' };
    }
}

/**
 * Says so when a language has no voice at all.
 *
 * This is the difference between "Bangla is broken" and "this computer has no
 * Bangla voice installed", which are the same symptom and completely different
 * problems. A fresh Windows install has English voices only.
 */
function missingVoiceWarning(catalogue, t) {
    if (catalogue.voices.length === 0) return null;
    const missing = ['en', 'bn'].filter((code) => !catalogue.speaks.includes(code));
    if (missing.length === 0) return null;

    const line = element('p', 'inline-error');
    line.textContent = missing
        .map((code) => t['settings.no_voice_for'].replace('%s', t[`language.${code}`]))
        .join(' ');
    return line;
}

/**
 * Says when the Google key file is not where the setting points.
 *
 * A key sitting in the secrets folder under a slightly different name is the
 * single most common reason Bangla sounds wrong, and every part of the app is
 * built to degrade quietly around it — so without this the operator's evidence
 * that anything is missing is that the voice is English.
 */
function credentialWarnings(catalogue, t) {
    if (catalogue.credentials !== 'missing') return [];

    const lines = [element('p', 'inline-error', t['settings.credentials_missing'])];
    const nearby = (catalogue.nearMisses || []).join(', ');
    if (nearby) {
        lines.push(element('p', 'inline-error',
            t['settings.credentials_near_miss'].replace('%s', nearby)));
    }
    return lines;
}

function field(entry, ctx, catalogue) {
    if (VOICE_KEYS[entry.key] && catalogue.voices.length > 0) {
        return voiceField(entry, ctx, catalogue);
    }

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

/**
 * A menu of the voices that speak this setting's language.
 *
 * Voices for the other language are left out — picking an English voice to
 * read Bangla is exactly the mistake that makes a call sound broken. The
 * stored value is kept as an option even when it is not installed here, so
 * opening Settings on a machine without it does not silently discard it.
 */
function voiceField(entry, ctx, catalogue) {
    const language = VOICE_KEYS[entry.key];
    const suitable = catalogue.voices.filter((voice) => voice.language === language);

    const options = [{ value: '', label: defaultVoiceLabel(suitable, ctx) }];
    suitable.forEach((voice) => options.push({ value: voice.id, label: voice.name }));
    if (entry.value && !suitable.some((voice) => voice.id === entry.value)) {
        options.push({ value: entry.value, label: `${entry.value} — ${ctx.strings['settings.voice_absent']}` });
    }

    const select = dropdown(options, entry.value || '', () => {});
    select.name = entry.key;
    if (suitable.length === 0) select.title = ctx.strings['settings.no_voice_for']
        .replace('%s', ctx.strings[`language.${language}`]);

    const wrap = element('div');
    wrap.appendChild(select);
    return wrap;
}

/**
 * The empty option, named after the voice it would really produce.
 *
 * The voice server picks the first voice that speaks the language, so the panel
 * can say which one that is instead of leaving an operator to place a call and
 * listen. When there is none, the option says that rather than implying a
 * choice was made.
 */
function defaultVoiceLabel(suitable, ctx) {
    if (suitable.length === 0) return ctx.strings['settings.voice_none_installed'];
    return ctx.strings['settings.voice_default_named'].replace('%s', suitable[0].name);
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
        // A refused value is not a failed request, so it would otherwise look
        // like a successful save that quietly did nothing.
        const refused = [...(result.rejected || []), ...(result.unknown || [])];
        if (refused.length > 0) {
            ctx.toast(ctx.strings['settings.rejected'].replace('%s', refused.join(', ')), 'bad');
        }
        const message = result.changed === 0
            ? ctx.strings['settings.no_changes']
            : ctx.strings['settings.saved'].replace('%s', result.changed);
        ctx.toast(message, result.changed === 0 ? 'info' : 'good');
        await ctx.rerender();
    } catch (error) {
        ctx.toastError(error);
    }
}
