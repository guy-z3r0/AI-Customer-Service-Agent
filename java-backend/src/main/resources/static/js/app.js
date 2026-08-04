/*
 * The panel's shell: it loads the vocabulary, draws the rail, top bar and
 * status bar, and swaps pages when the address bar's hash changes.
 *
 * Pages get one argument — a context holding the translated strings and the
 * shell functions they may call — so no page reaches into the DOM outside the
 * area it owns.
 */

import { api } from './api.js';
import { element, emptyState, toast, toastError } from './components.js';
import { connectLiveFeed } from './ws.js';
import { renderBusinesses } from './pages/businesses.js';
import { renderDashboard } from './pages/dashboard.js';
import { renderBusinessEditor } from './pages/business_editor.js';
import { renderClients } from './pages/clients.js';
import { renderLiveCall } from './pages/live_call.js';
import { renderSettings } from './pages/settings.js';

const HEALTH_POLL_MS = 10000;

const ROUTES = [
    { id: 'dashboard', icon: '▦', render: renderDashboard },
    { id: 'live_call', icon: '◉', render: renderLiveCall },
    { id: 'businesses', icon: '▤', render: renderBusinesses },
    { id: 'clients', icon: '◍', render: renderClients },
    { id: 'history', icon: '◷', render: soonPage('soon.history') },
    { id: 'settings', icon: '⚙', render: renderSettings },
    // Reached from a business rather than from the rail, so it carries the id
    // it is editing in the hash: #/business_editor/<id>.
    { id: 'business_editor', hidden: true, render: renderBusinessEditor }
];

// The overview is the landing page: it answers "is this thing working" before
// anyone has to click anything.
const DEFAULT_ROUTE = 'dashboard';

const shell = {
    strings: {},
    businesses: [],
    activeBusinessId: null,
    health: null,
    // Set by whichever page is on screen; cleared before the next one renders.
    liveHandler: null,
    pageCleanup: null
};

const context = {
    get strings() { return shell.strings; },
    get activeBusiness() {
        return shell.businesses.find((business) => business.active) || null;
    },
    get health() { return shell.health; },
    toast,
    toastError: (error) => toastError(error, shell.strings),
    reloadShell: refreshBusinesses,
    rerender: () => renderCurrentRoute(),
    goTo: (routeId) => { window.location.hash = `#/${routeId}`; },
    // Pages that care about live call events register a handler here rather
    // than opening a second socket of their own.
    onLiveEvent: (handler) => { shell.liveHandler = handler; },
    onLeave: (cleanup) => { shell.pageCleanup = cleanup; }
};

start();

async function start() {
    shell.strings = await api.get('/api/lang?lang=en');
    document.title = shell.strings['app.title'];
    buildRail();
    document.getElementById('active-business-label').textContent = shell.strings['shell.active_business'];
    document.getElementById('active-business')
        .addEventListener('change', (event) => activateBusiness(event.target.value));

    window.addEventListener('hashchange', renderCurrentRoute);
    connectLiveFeed((event) => {
        if (shell.liveHandler) shell.liveHandler(event);
    });

    await refreshBusinesses();
    renderCurrentRoute();

    refreshHealth();
    setInterval(refreshHealth, HEALTH_POLL_MS);
}

// ------------------------------------------------------------------- shell --

function buildRail() {
    const rail = document.getElementById('rail');
    rail.appendChild(element('div', 'side-rail__brand', shell.strings['app.title']));
    ROUTES.filter((route) => !route.hidden).forEach((route) => {
        const item = element('button', 'rail-item');
        item.type = 'button';
        item.dataset.routeId = route.id;
        item.appendChild(element('span', 'rail-item__icon', route.icon));
        item.appendChild(element('span', 'rail-item__label', shell.strings[`nav.${route.id}`]));
        item.addEventListener('click', () => context.goTo(route.id));
        rail.appendChild(item);
    });
}

/** The hash is the route id, then whatever that page needs: #/route/arg/arg. */
function currentRoute() {
    const [id, ...params] = window.location.hash.replace(/^#\/?/, '').split('/');
    const route = ROUTES.find((candidate) => candidate.id === id)
        || ROUTES.find((candidate) => candidate.id === DEFAULT_ROUTE);
    return { route, params };
}

async function renderCurrentRoute() {
    // Let the outgoing page put its things away — a live call has a microphone
    // and two sockets open, and leaving the page must hang up.
    if (shell.pageCleanup) {
        try {
            shell.pageCleanup();
        } catch (error) {
            console.error('page cleanup failed', error);
        }
    }
    shell.pageCleanup = null;
    shell.liveHandler = null;

    const { route, params } = currentRoute();
    document.getElementById('page-title').textContent = shell.strings[`nav.${route.id}`];
    document.querySelectorAll('.rail-item').forEach((item) => {
        item.classList.toggle('rail-item--selected', item.dataset.routeId === route.id);
    });

    const host = document.getElementById('page');
    host.replaceChildren();
    try {
        await route.render(host, context, params);
    } catch (error) {
        context.toastError(error);
        host.replaceChildren(element('div', 'panel muted-body', shell.strings['error.load_failed']));
    }
}

/** Builds a page for a section that a later phase fills in. */
function soonPage(stringKey) {
    return (host, ctx) => {
        host.appendChild(emptyState(ctx.strings[stringKey], ctx.strings['soon.action'],
            () => ctx.goTo('businesses')));
    };
}

// -------------------------------------------------------- active business --

async function refreshBusinesses() {
    shell.businesses = await api.get('/api/businesses');
    const active = shell.businesses.find((business) => business.active);
    shell.activeBusinessId = active ? active.id : null;

    const dropdown = document.getElementById('active-business');
    dropdown.replaceChildren();
    if (!active) {
        const none = element('option', null, shell.strings['shell.no_active_business']);
        none.value = '';
        dropdown.appendChild(none);
    }
    shell.businesses.forEach((business) => {
        const option = element('option', null, business.name);
        option.value = business.id;
        option.selected = business.active;
        dropdown.appendChild(option);
    });
    dropdown.disabled = shell.businesses.length === 0;
}

async function activateBusiness(id) {
    if (!id || id === shell.activeBusinessId) return;
    try {
        const business = await api.post(`/api/businesses/${id}/activate`);
        toast(shell.strings['businesses.activated'].replace('%s', business.name), 'good');
        await refreshBusinesses();
        await renderCurrentRoute();
    } catch (error) {
        context.toastError(error);
        await refreshBusinesses();
    }
}

// -------------------------------------------------------------- status bar --

async function refreshHealth() {
    let health;
    try {
        health = await api.get('/api/health');
    } catch (error) {
        shell.health = null;
        setStatus(`${shell.strings['status.database']}: ${shell.strings['status.down']}`, '');
        return;
    }
    shell.health = health;

    const t = shell.strings;
    const parts = [
        `${t['status.database']}: ${t[`status.${health.database}`] || health.database}`,
        `${t['status.voice']}: ${t[`status.${health.voiceServer}`] || health.voiceServer}`,
        `${t['status.model']}: ${health.llmProvider} — ${health.llmKeyReady ? t['status.ready'] : t['status.needs_key']}`
    ];
    const left = parts.join('   ');
    const right = health.placeholders.length === 0
        ? ''
        : t['status.placeholders_left'].replace('%s', health.placeholders.length);
    setStatus(left, right);
}

function setStatus(left, right) {
    document.getElementById('status-left').textContent = left;
    document.getElementById('status-right').textContent = right;
}
