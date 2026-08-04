/*
 * The business editor — everything a call reads, on six tabs.
 *
 * Nothing here is copied anywhere. The prompt for the next call is assembled
 * from these rows at the moment it is placed, so a sentence changed on this
 * page is a sentence the agent says a minute later, with no restart and no
 * deploy. That is the whole point of the screen.
 */

import { api } from '../api.js';
import { Form, button, confirm, dialog, element, tabStrip } from '../components.js';

const KNOWLEDGE_TABS = [
    { id: 'about', kind: 'ABOUT' },
    { id: 'service', kind: 'SERVICE' },
    { id: 'policy', kind: 'POLICY' },
    { id: 'faq', kind: 'FAQ' }
];
const DAYS = ['sat', 'sun', 'mon', 'tue', 'wed', 'thu', 'fri'];

export async function renderBusinessEditor(host, ctx, params) {
    const editor = new Editor(host, ctx, params[0]);
    await editor.load();
    editor.draw();
}

class Editor {

    constructor(host, ctx, businessId) {
        this.host = host;
        this.ctx = ctx;
        this.t = ctx.strings;
        this.businessId = businessId;
        this.tab = 'about';
    }

    async load() {
        this.business = await api.get(`/api/businesses/${this.businessId}`);
        this.entries = await api.get(`/api/businesses/${this.businessId}/kb`);
        this.persona = await api.get(`/api/businesses/${this.businessId}/ai-settings`);
        this.contacts = await api.get(`/api/businesses/${this.businessId}/escalation`);
    }

    draw() {
        this.host.replaceChildren();
        const panel = element('div', 'panel');

        const heading = element('div', 'row');
        heading.appendChild(button(this.t['common.back'], 'ghost', () => this.ctx.goTo('businesses')));
        heading.appendChild(element('div', 'section-header', this.business.name));
        panel.appendChild(heading);
        panel.appendChild(element('p', 'muted-body', this.t['editor.note']));

        panel.appendChild(tabStrip(this.tabs(), this.tab, (id) => {
            this.tab = id;
            this.drawTab();
        }));

        this.body = element('div', 'scroll-region');
        panel.appendChild(this.body);
        this.host.appendChild(panel);
        this.drawTab();
    }

    tabs() {
        const named = (id) => ({ id, label: this.t[`editor.tab_${id}`] });
        return [...KNOWLEDGE_TABS.map((tab) => named(tab.id)), named('persona'), named('hours')];
    }

    drawTab() {
        this.body.replaceChildren();
        const knowledge = KNOWLEDGE_TABS.find((tab) => tab.id === this.tab);
        if (knowledge) this.drawKnowledge(knowledge.kind);
        else if (this.tab === 'persona') this.drawPersona();
        else this.drawHours();
    }

    async reload() {
        await this.load();
        this.drawTab();
    }

    // ---------------------------------------------------------- knowledge --

    /**
     * One section of the knowledge base. The order of the rows is the order the
     * model reads them in, so moving one up is an editorial act and gets its
     * own button rather than being buried in a form.
     */
    drawKnowledge(kind) {
        const mine = this.entries.filter((entry) => entry.kind === kind);
        if (mine.length === 0) {
            this.body.appendChild(element('div', 'list-row muted-body',
                this.t['editor.section_empty']));
        }
        mine.forEach((entry, index) =>
            this.body.appendChild(this.knowledgeRow(entry, index, mine.length)));

        const actions = element('div', 'row row--end');
        actions.appendChild(button(this.t['editor.add_entry'], 'primary',
            () => this.openEntry(null, kind)));
        this.body.appendChild(actions);
    }

    knowledgeRow(entry, index, total) {
        const row = element('div', 'list-row');
        const text = entry.question ? `${entry.question} — ${entry.content}` : entry.content;
        row.appendChild(element('span', 'list-row__text', text));

        if (index > 0) {
            row.appendChild(button(this.t['common.up'], 'ghost', () => this.move(entry, 'up')));
        }
        if (index < total - 1) {
            row.appendChild(button(this.t['common.down'], 'ghost', () => this.move(entry, 'down')));
        }
        row.appendChild(button(this.t['common.edit'], 'ghost', () => this.openEntry(entry, entry.kind)));
        row.appendChild(button(this.t['common.delete'], 'ghost', () => this.removeEntry(entry)));
        return row;
    }

    openEntry(entry, kind) {
        const form = new Form();
        if (kind === 'FAQ') {
            form.text('question', this.t['editor.field_question'], entry ? entry.question : '');
        }
        form.text('content', this.t['editor.field_content'], entry ? entry.content : '',
            { multiline: true });

        const close = dialog({
            title: this.t['editor.entry_title'],
            body: form.node,
            actions: [
                { label: this.t['common.cancel'], kind: 'secondary', onClick: (d) => d() },
                {
                    label: this.t['common.save'],
                    kind: 'primary',
                    onClick: () => this.saveEntry(entry, kind, form, close)
                }
            ]
        });
    }

    async saveEntry(entry, kind, form, close) {
        form.clearErrors();
        const body = { kind, ...form.values() };
        try {
            if (entry) await api.put(`/api/businesses/${this.businessId}/kb/${entry.id}`, body);
            else await api.post(`/api/businesses/${this.businessId}/kb`, body);
            close();
            this.ctx.toast(this.t['common.saved'], 'good');
            await this.reload();
        } catch (error) {
            form.showError('content', error.detail || error.message);
        }
    }

    async removeEntry(entry) {
        const agreed = await confirm({
            title: this.t['editor.entry_title'],
            line: this.t['editor.entry_delete_line'],
            proceedLabel: this.t['common.delete'],
            cancelLabel: this.t['common.cancel'],
            destructive: true
        });
        if (!agreed) return;
        await this.call(() => api.del(`/api/businesses/${this.businessId}/kb/${entry.id}`));
    }

    async move(entry, direction) {
        await this.call(() =>
            api.post(`/api/businesses/${this.businessId}/kb/${entry.id}/move`, { direction }));
    }

    // ------------------------------------------------------------- persona --

    drawPersona() {
        const form = new Form();
        form.text('personaName', this.t['editor.persona_name'], this.persona.personaName);
        form.text('roleDescription', this.t['editor.persona_role'], this.persona.roleDescription,
            { multiline: true });
        form.text('replyStyle', this.t['editor.persona_style'], this.persona.replyStyle,
            { multiline: true });
        form.text('greetingEn', this.t['editor.persona_greeting_en'], this.persona.greetingEn,
            { multiline: true });
        form.text('greetingBn', this.t['editor.persona_greeting_bn'], this.persona.greetingBn,
            { multiline: true });
        form.text('providerOverride', this.t['editor.persona_provider'],
            this.persona.providerOverride, { hint: this.t['editor.override_hint'] });
        form.text('modelOverride', this.t['editor.persona_model'], this.persona.modelOverride,
            { hint: this.t['editor.override_hint'] });
        form.text('temperature', this.t['editor.persona_temperature'], this.persona.temperature);
        form.text('maxHistoryTurns', this.t['editor.persona_history'],
            this.persona.maxHistoryTurns);

        this.body.appendChild(form.node);
        const actions = element('div', 'row row--end');
        actions.appendChild(button(this.t['common.save'], 'primary', () => this.savePersona(form)));
        this.body.appendChild(actions);
    }

    async savePersona(form) {
        form.clearErrors();
        try {
            await api.put(`/api/businesses/${this.businessId}/ai-settings`, form.values());
            this.ctx.toast(this.t['common.saved'], 'good');
            await this.reload();
        } catch (error) {
            form.showError('personaName', error.detail || error.message);
        }
    }

    // --------------------------------------------------- hours & handover --

    drawHours() {
        const stored = parseHours(this.business.hoursJson);
        const form = new Form();
        DAYS.forEach((day) => {
            const open = stored[day] ? stored[day].open : '';
            const close = stored[day] ? stored[day].close : '';
            form.text(`${day}_open`, `${this.t[`editor.day_${day}`]} — ${this.t['editor.hours_open']}`,
                open, { placeholder: '10:00' });
            form.text(`${day}_close`, `${this.t[`editor.day_${day}`]} — ${this.t['editor.hours_close']}`,
                close, { placeholder: '20:00' });
        });

        this.body.appendChild(element('div', 'section-header', this.t['editor.hours_title']));
        this.body.appendChild(element('p', 'muted-body', this.t['editor.hours_hint']));
        this.body.appendChild(form.node);

        const actions = element('div', 'row row--end');
        actions.appendChild(button(this.t['common.save'], 'primary', () => this.saveHours(form)));
        this.body.appendChild(actions);

        this.body.appendChild(element('hr', 'divider'));
        this.drawEscalation();
    }

    async saveHours(form) {
        const values = form.values();
        const hours = {};
        DAYS.forEach((day) => {
            const open = values[`${day}_open`];
            const close = values[`${day}_close`];
            // A day with nothing in it is stored as null, which the prompt
            // writes out as closed rather than leaving for the model to guess.
            hours[day] = open && close ? { open, close } : null;
        });

        try {
            await api.put(`/api/businesses/${this.businessId}`, {
                name: this.business.name,
                phone: this.business.phone,
                email: this.business.email,
                address: this.business.address,
                timezone: this.business.timezone,
                hoursJson: JSON.stringify(hours)
            });
            this.ctx.toast(this.t['common.saved'], 'good');
            await this.reload();
        } catch (error) {
            this.ctx.toastError(error);
        }
    }

    drawEscalation() {
        this.body.appendChild(element('div', 'section-header', this.t['editor.escalation_title']));
        if (this.contacts.length === 0) {
            this.body.appendChild(element('div', 'list-row muted-body',
                this.t['editor.escalation_empty']));
        }
        this.contacts.forEach((contact) => {
            const row = element('div', 'list-row');
            row.appendChild(element('span', 'list-row__text',
                `${contact.priority}. ${contact.name} — ${contact.email}`));
            row.appendChild(button(this.t['common.edit'], 'ghost', () => this.openContact(contact)));
            row.appendChild(button(this.t['common.delete'], 'ghost',
                () => this.call(() =>
                    api.del(`/api/businesses/${this.businessId}/escalation/${contact.id}`))));
            this.body.appendChild(row);
        });

        const actions = element('div', 'row row--end');
        actions.appendChild(button(this.t['editor.escalation_add'], 'secondary',
            () => this.openContact(null)));
        this.body.appendChild(actions);
    }

    openContact(contact) {
        const form = new Form();
        form.text('name', this.t['editor.escalation_name'], contact ? contact.name : '');
        form.text('email', this.t['editor.escalation_email'], contact ? contact.email : '');
        form.text('priority', this.t['editor.escalation_priority'], contact ? contact.priority : 1);

        const close = dialog({
            title: this.t['editor.escalation_title'],
            body: form.node,
            actions: [
                { label: this.t['common.cancel'], kind: 'secondary', onClick: (d) => d() },
                {
                    label: this.t['common.save'],
                    kind: 'primary',
                    onClick: () => this.saveContact(contact, form, close)
                }
            ]
        });
    }

    async saveContact(contact, form, close) {
        form.clearErrors();
        const path = `/api/businesses/${this.businessId}/escalation`;
        try {
            if (contact) await api.put(`${path}/${contact.id}`, form.values());
            else await api.post(path, form.values());
            close();
            this.ctx.toast(this.t['common.saved'], 'good');
            await this.reload();
        } catch (error) {
            form.showError('email', error.detail || error.message);
        }
    }

    // ------------------------------------------------------------ internals --

    /** Runs a change, says so, and redraws — the shape of most buttons here. */
    async call(action) {
        try {
            await action();
            this.ctx.toast(this.t['common.saved'], 'good');
            await this.reload();
        } catch (error) {
            this.ctx.toastError(error);
        }
    }
}

function parseHours(json) {
    try {
        const parsed = JSON.parse(json || '{}');
        return parsed && typeof parsed === 'object' ? parsed : {};
    } catch (notJson) {
        return {};
    }
}
