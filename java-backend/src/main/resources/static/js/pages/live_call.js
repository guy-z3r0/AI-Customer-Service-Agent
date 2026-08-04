/*
 * Live call — placing a call from this browser tab and watching it happen.
 *
 * Two connections are open during a call, on purpose:
 *   audio  goes straight to the voice server, so nothing sits between the
 *          microphone and the recogniser
 *   text   comes back from the Java backend's live feed, which is also what
 *          writes the transcript to the database
 *
 * The reply itself is written by the language model, in the Java backend,
 * against the active business's knowledge. This page never talks to a model and
 * never holds a word of the conversation — it only shows what arrives.
 */

import { api } from '../api.js';
import { AgentPlayer } from '../audio/player.js';
import { MicStream } from '../audio/mic_stream.js';
import { button, dropdown, element, keyValueRow, tagBadge } from '../components.js';
import { Transcript } from './live_transcript.js';

const MODE_HUES = {
    NEW_CUSTOMER: 'azure',
    EXISTING_CUSTOMER: 'jade',
    WRONG_NUMBER: 'rose',
    COMPLEX_REQUEST: 'gold'
};
const MODES = ['NEW_CUSTOMER', 'EXISTING_CUSTOMER', 'WRONG_NUMBER', 'COMPLEX_REQUEST'];

export async function renderLiveCall(host, ctx) {
    const call = new LiveCall(host, ctx);
    ctx.onLiveEvent((event) => call.onLiveEvent(event));
    ctx.onLeave(() => call.hangUp('left_page'));
    await call.loadClients();
    call.draw();
}

class LiveCall {

    constructor(host, ctx) {
        this.host = host;
        this.ctx = ctx;
        this.t = ctx.strings;

        this.callId = null;
        this.socket = null;
        this.mic = null;
        this.player = null;
        this.state = 'idle';
        this.latencies = [];
        this.turns = 0;
        this.mode = MODES[0];
        this.language = 'EN';
        this.caller = null;
        this.dialAs = '';
        this.clients = [];
        this.transcript = null;
        this.nodes = {};
    }

    // ------------------------------------------------------------- drawing --

    draw() {
        this.host.replaceChildren();
        this.host.appendChild(this.buildDialPanel());
        this.host.appendChild(this.buildTranscriptPanel());
        this.refreshFacts();
        this.refreshButtons();
    }

    buildDialPanel() {
        const panel = element('div', 'panel');
        panel.appendChild(element('div', 'section-header', this.t['livecall.title']));
        panel.appendChild(element('p', 'muted-body', this.t['livecall.note']));

        const actions = element('div', 'row');
        this.nodes.start = button(this.t['livecall.start'], 'primary', () => this.dial());
        this.nodes.twilio = button(this.t['livecall.start_twilio'], 'secondary', () => {});
        this.nodes.twilio.disabled = true;
        this.nodes.twilio.title = this.t['livecall.twilio_disabled'];
        this.nodes.end = button(this.t['livecall.end'], 'destructive', () => this.hangUp('hangup'));
        actions.append(this.nodes.start, this.nodes.twilio, this.nodes.end);
        panel.appendChild(actions);

        panel.appendChild(element('hr', 'divider'));
        this.nodes.facts = element('div', 'key-value');
        panel.appendChild(this.nodes.facts);
        return panel;
    }

    buildTranscriptPanel() {
        const panel = element('div', 'panel');
        panel.appendChild(element('div', 'section-header', this.t['livecall.transcript']));
        const region = element('div', 'scroll-region transcript');
        this.transcript = new Transcript(region, this.t);
        this.transcript.showEmpty();
        panel.appendChild(region);
        return panel;
    }

    refreshFacts() {
        const business = this.ctx.activeBusiness;
        const grid = this.nodes.facts;
        grid.replaceChildren();
        keyValueRow(grid, this.t['livecall.state'], this.t[`livecall.state_${this.state}`]);
        keyValueRow(grid, this.t['livecall.business'], business ? business.name : '—');
        keyValueRow(grid, this.t['livecall.model'], this.modelFact());
        keyValueRow(grid, this.t['livecall.language'], this.languageLabel(this.language));
        keyValueRow(grid, this.t['livecall.caller'], this.caller || this.t['livecall.caller_unknown']);
        keyValueRow(grid, this.t['livecall.dial_as'], this.clientChooser());
        keyValueRow(grid, this.t['livecall.mode'], this.modeBadge());
        keyValueRow(grid, this.t['livecall.override_mode'], this.modeChooser());
        keyValueRow(grid, this.t['livecall.call_id'], this.callId || '—');
        keyValueRow(grid, this.t['livecall.turns'], String(this.turns));
        keyValueRow(grid, this.t['livecall.median_latency'], this.medianLatency());
    }

    /** The active business's customers, so a call can be placed as one of them. */
    async loadClients() {
        const business = this.ctx.activeBusiness;
        if (!business) return;
        try {
            this.clients = await api.get(`/api/businesses/${business.id}/clients`);
        } catch (error) {
            // Not being able to list customers is no reason to block a call.
            this.clients = [];
        }
    }

    /**
     * Who to place the call as. Picking a customer is the difference between an
     * agent that has to ask who you are and one that greets you by name.
     */
    clientChooser() {
        const options = [{ value: '', label: this.t['livecall.dial_as_stranger'] }];
        this.clients.forEach((client) =>
            options.push({ value: client.clientCode, label: `${client.clientCode} — ${client.name}` }));

        const chooser = dropdown(options, this.dialAs, (code) => { this.dialAs = code; });
        chooser.disabled = this.isLive() || this.clients.length === 0;
        return chooser;
    }

    modeBadge() {
        return tagBadge(this.modeLabel(this.mode), MODE_HUES[this.mode] || 'azure');
    }

    /** The operator overruling the agent. The server still refuses illegal moves. */
    modeChooser() {
        const chooser = dropdown(
            MODES.map((mode) => ({ value: mode, label: this.modeLabel(mode) })),
            this.mode,
            (mode) => this.changeMode(mode));
        chooser.disabled = !this.isLive();
        return chooser;
    }

    modeLabel(mode) {
        return this.t[`mode.${String(mode).toLowerCase()}`] || mode;
    }

    languageLabel(language) {
        return this.t[`language.${String(language).toLowerCase()}`] || language;
    }

    /** Which model would answer, and whether it has a key to answer with. */
    modelFact() {
        const health = this.ctx.health;
        if (!health) return '—';
        const state = health.llmKeyReady ? this.t['status.ready'] : this.t['status.needs_key'];
        return `${health.llmProvider} — ${state}`;
    }

    isLive() {
        return this.state !== 'idle' && this.state !== 'ended';
    }

    refreshButtons() {
        this.nodes.start.disabled = this.isLive();
        this.nodes.end.disabled = !this.isLive();
    }

    setState(state) {
        this.state = state;
        this.refreshFacts();
        this.refreshButtons();
    }

    // -------------------------------------------------------------- dialling --

    async dial() {
        if (!MicStream.isSupported()) {
            this.ctx.toast(this.t['livecall.unsupported'], 'bad');
            return;
        }
        if (this.ctx.health && this.ctx.health.voiceServer !== 'up') {
            this.ctx.toast(this.t['livecall.voice_down'], 'bad');
            return;
        }

        this.setState('connecting');
        this.latencies = [];
        this.turns = 0;
        this.mode = MODES[0];
        this.language = 'EN';
        this.caller = null;

        let started;
        try {
            started = await api.post('/api/call/start',
                { telephony: 'browser', clientCode: this.dialAs || null });
        } catch (error) {
            this.setState('idle');
            this.ctx.toastError(error);
            return;
        }

        this.callId = started.callId;
        this.transcript.clear();
        try {
            await this.openVoiceSocket(started);
            await this.startMicrophone();
        } catch (error) {
            this.ctx.toast(error.message === 'mic'
                ? this.t['livecall.mic_denied'] : String(error.message || error), 'bad');
            await this.hangUp('setup_failed');
            return;
        }
        this.setState('listening');
    }

    openVoiceSocket(started) {
        return new Promise((resolve, reject) => {
            const socket = new WebSocket(started.voiceUrl);
            socket.binaryType = 'arraybuffer';
            this.socket = socket;

            socket.addEventListener('open', () => {
                socket.send(JSON.stringify({ type: 'start', language: started.language }));
                resolve();
            });
            socket.addEventListener('message', (message) => this.onVoiceMessage(message));
            socket.addEventListener('error', () => reject(new Error(this.t['livecall.voice_down'])));
            socket.addEventListener('close', () => {
                if (this.state === 'listening' || this.state === 'speaking') {
                    this.ctx.toast(this.t['livecall.disconnected'], 'bad');
                    this.hangUp('voice_closed');
                }
            });
        });
    }

    async startMicrophone() {
        this.player = new AgentPlayer((speaking) => {
            if (this.mic) this.mic.setMuted(speaking);
        });
        this.mic = new MicStream((pcm) => this.sendAudio(pcm));
        try {
            await this.mic.start();
        } catch (refused) {
            throw new Error('mic');
        }
    }

    sendAudio(pcm) {
        if (this.socket && this.socket.readyState === WebSocket.OPEN) {
            this.socket.send(pcm.buffer);
        }
    }

    onVoiceMessage(message) {
        if (message.data instanceof ArrayBuffer) {
            if (this.player) this.player.play(message.data);
            return;
        }
        let control;
        try {
            control = JSON.parse(message.data);
        } catch (notJson) {
            return;
        }
        if (control.type === 'agent_state') {
            // The server decides when the agent holds the floor; the page and
            // the microphone follow it rather than guessing from the audio.
            if (this.mic) this.mic.setMuted(control.speaking);
            this.setState(control.speaking ? 'speaking' : 'listening');
        } else if (control.type === 'error') {
            this.ctx.toast(control.msg || control.code, 'bad');
        }
    }

    async hangUp(reason) {
        const wasLive = this.state === 'listening' || this.state === 'speaking'
            || this.state === 'connecting';
        if (this.socket) {
            if (this.socket.readyState === WebSocket.OPEN) {
                this.socket.send(JSON.stringify({ type: 'end', reason }));
            }
            this.socket.close();
            this.socket = null;
        }
        if (this.mic) { this.mic.stop(); this.mic = null; }
        if (this.player) { this.player.stop(); this.player = null; }

        if (wasLive && this.callId) {
            try {
                await api.post(`/api/call/${this.callId}/end`, { reason });
            } catch (error) {
                // The voice server reports the end as well, so a failure here
                // is not worth interrupting anyone over.
                console.warn('could not report the call end', error);
            }
        }
        if (this.nodes.start) this.setState('ended');
    }

    // ---------------------------------------------------------- live events --

    onLiveEvent(event) {
        if (event.callId && this.callId && event.callId !== this.callId) return;
        if (event.type === 'partial') this.transcript.showPartial(event.text);
        else if (event.type === 'line') this.addLine(event);
        else if (event.type === 'latency') this.addLatency(event);
        else if (event.type === 'mode_change') this.onModeChange(event);
        else if (event.type === 'language_change') this.onLanguageChange(event);
        else if (event.type === 'client_identified') this.onClientIdentified(event);
        else if (event.type === 'notice') this.ctx.toast(this.t[event.key] || event.key, 'warn');
        else if (event.type === 'call_ended') this.setState('ended');
    }

    onModeChange(event) {
        this.mode = event.toMode;
        // The opening classification is not a change; it is already in the facts.
        if (event.fromMode) {
            this.transcript.addNote(this.t['livecall.mode_changed'].replace('%s', this.modeLabel(event.toMode)),
                event.reason, MODE_HUES[event.toMode]);
        }
        this.refreshFacts();
    }

    onLanguageChange(event) {
        this.language = event.language;
        this.transcript.addNote(this.t['livecall.language_changed']
            .replace('%s', this.languageLabel(event.language)), null, 'violet');
        this.refreshFacts();
    }

    /** The agent worked out who is calling, or wrote a new record for them. */
    onClientIdentified(event) {
        this.caller = event.name;
        this.transcript.addNote(this.t['livecall.caller_found'].replace('%s', event.name),
            null, 'jade');
        this.refreshFacts();
    }

    /**
     * Something that happened to the call rather than something said on it, put
     * in the transcript where it happened so the two read in order.
     */
    addNote(label, detail, hue) {
        const row = element('div', 'list-row');
        row.appendChild(tagBadge(label, hue || 'violet'));
        if (detail) row.appendChild(element('span', 'list-row__text', detail));
        this.appendRow(row);
        this.scrollToEnd();
    }

    async changeMode(mode) {
        if (!this.callId || mode === this.mode) return;
        try {
            // The reason is written server-side, so no wording lives in this file.
            await api.post(`/api/call/${this.callId}/mode`, { mode });
        } catch (error) {
            this.ctx.toast(this.t['livecall.mode_refused'], 'bad');
            this.refreshFacts();  // put the chooser back where the call actually is
        }
    }

    addLine(event) {
        this.transcript.addLine(event);
        // The greeting is an agent line but not an exchange: it carries no turn
        // number, and counting it would make the first reply look like the second.
        if (event.role === 'AGENT' && event.turnSeq) {
            this.turns++;
            this.refreshFacts();
        }
    }

    addLatency(event) {
        this.latencies.push(event.totalMs);
        this.transcript.addTiming(event);
        this.refreshFacts();
    }

    medianLatency() {
        if (this.latencies.length === 0) return '—';
        const sorted = [...this.latencies].sort((a, b) => a - b);
        const middle = Math.floor(sorted.length / 2);
        const median = sorted.length % 2 ? sorted[middle]
            : Math.round((sorted[middle - 1] + sorted[middle]) / 2);
        return `${median} ms`;
    }
}
