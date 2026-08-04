/*
 * Builders for the contract's parts. Pages call these instead of writing their
 * own markup, which is what keeps every table, badge and toast identical.
 *
 * Text always arrives already translated — nothing here invents a word.
 */

const TOAST_LIFETIME_MS = 4000;
const MAX_TOASTS = 3;

export function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
}

export function button(label, kind, onClick) {
    const node = element('button', `button button--${kind}`, label);
    node.type = 'button';
    node.addEventListener('click', onClick);
    return node;
}

export function tagBadge(label, hue) {
    return element('span', `tag-badge tag-badge--${hue}`, label);
}

/**
 * A closed list of choices.
 *
 * @param options [{ value, label }] in the order they should be read
 */
export function dropdown(options, selected, onChange) {
    const node = element('select', 'dropdown');
    options.forEach((option) => {
        const item = element('option', null, option.label);
        item.value = option.value;
        item.selected = option.value === selected;
        node.appendChild(item);
    });
    node.addEventListener('change', (event) => onChange(event.target.value));
    return node;
}

/**
 * A table from column definitions and rows. A column may render a cell itself,
 * which is how action buttons and badges end up inside a row.
 *
 * @param columns [{ label, numeric?, render?(row) -> Node, value?(row) -> string }]
 */
export function table(columns, rows) {
    const wrap = element('div', 'table-wrap');
    const node = element('table', 'table');

    const headRow = element('tr');
    columns.forEach((column) => headRow.appendChild(element('th', null, column.label)));
    node.appendChild(element('thead')).appendChild(headRow);

    const body = element('tbody');
    rows.forEach((row) => {
        const tr = element('tr');
        columns.forEach((column) => {
            const td = element('td', column.numeric ? 'cell--number' : null);
            if (column.render) {
                td.appendChild(column.render(row));
            } else {
                const value = column.value(row);
                td.textContent = value === null || value === undefined ? '' : String(value);
                td.title = td.textContent;
            }
            tr.appendChild(td);
        });
        body.appendChild(tr);
    });
    node.appendChild(body);

    wrap.appendChild(node);
    return wrap;
}

/** The contract's empty state: one line naming the space, one primary action. */
export function emptyState(line, actionLabel, onAction) {
    const node = element('div', 'empty-state');
    node.appendChild(element('div', 'empty-state__line', line));
    if (actionLabel) node.appendChild(button(actionLabel, 'primary', onAction));
    return node;
}

/** A label-and-value pair. Either side may be plain text or a built node. */
export function keyValueRow(grid, label, value) {
    grid.appendChild(fill(element('div', 'key-value__label'), label));
    grid.appendChild(fill(element('div', 'key-value__value'), value));
}

function fill(cell, content) {
    if (content instanceof Node) cell.appendChild(content);
    else cell.textContent = content === null || content === undefined ? '' : String(content);
    return cell;
}

/** Horizontal tabs within a region. Draws itself and calls back on a change. */
export function tabStrip(tabs, selected, onSelect) {
    const strip = element('div', 'tab-strip');
    tabs.forEach((tab) => {
        const node = element('button', 'tab', tab.label);
        node.type = 'button';
        node.dataset.tabId = tab.id;
        if (tab.id === selected) node.classList.add('tab--selected');
        node.addEventListener('click', () => {
            strip.querySelectorAll('.tab').forEach((other) =>
                other.classList.toggle('tab--selected', other.dataset.tabId === tab.id));
            onSelect(tab.id);
        });
        strip.appendChild(node);
    });
    return strip;
}

// --------------------------------------------------------------------- dialog --

/**
 * Puts a dialog on the screen over a scrim.
 *
 * @param actions [{ label, kind, onClick(close) }] right-aligned, in order
 * @returns a function that closes it
 */
export function dialog({ title, body, actions }) {
    const scrim = element('div', 'dialog__scrim');
    const node = element('div', 'dialog');
    node.appendChild(element('div', 'dialog__title', title));

    const bodyNode = element('div', 'dialog__body');
    if (body instanceof Node) bodyNode.appendChild(body);
    else bodyNode.textContent = body || '';
    node.appendChild(bodyNode);

    const close = () => { document.removeEventListener('keydown', onKey); scrim.remove(); };
    const onKey = (event) => { if (event.key === 'Escape') close(); };
    document.addEventListener('keydown', onKey);

    const actionRow = element('div', 'dialog__actions');
    (actions || []).forEach((action) =>
        actionRow.appendChild(button(action.label, action.kind, () => action.onClick(close))));
    node.appendChild(actionRow);

    scrim.appendChild(node);
    document.body.appendChild(scrim);
    focusFirstField(node, actionRow);
    return close;
}

/** Two actions, one line of text, no fields. Resolves true if the user proceeds. */
export function confirm({ title, line, proceedLabel, cancelLabel, destructive }) {
    return new Promise((resolve) => {
        dialog({
            title,
            body: line,
            actions: [
                { label: cancelLabel, kind: 'secondary', onClick: (close) => { close(); resolve(false); } },
                {
                    label: proceedLabel,
                    kind: destructive ? 'destructive' : 'primary',
                    onClick: (close) => { close(); resolve(true); }
                }
            ]
        });
    });
}

/**
 * Focus lands on the first field, or — in a confirm — on the dismiss button,
 * never on the action that cannot be undone.
 */
function focusFirstField(node, actionRow) {
    const firstField = node.querySelector('.text-field, .dropdown');
    if (firstField) { firstField.focus(); return; }
    const dismiss = actionRow.querySelector('.button--secondary');
    if (dismiss) dismiss.focus();
}

// ----------------------------------------------------------------------- form --

/**
 * A form that knows its own fields: it builds them, reads them back, and can
 * put a server's complaint under the one it belongs to.
 */
export class Form {

    constructor() {
        this.node = element('form');
        this.controls = {};
        this.node.addEventListener('submit', (event) => event.preventDefault());
    }

    /** @param options { multiline, placeholder, hint, type } */
    text(name, label, value, options = {}) {
        const control = element(options.multiline ? 'textarea' : 'input',
            options.multiline ? 'text-field text-field--multiline' : 'text-field');
        control.name = name;
        control.value = value === null || value === undefined ? '' : String(value);
        if (options.placeholder) control.placeholder = options.placeholder;
        if (options.type && !options.multiline) control.type = options.type;
        return this.add(name, label, control, options.hint);
    }

    select(name, label, options, value) {
        return this.add(name, label, dropdown(options, value, () => {}));
    }

    add(name, label, control, hint) {
        const wrap = element('div', 'field');
        wrap.appendChild(element('div', 'key-value__label', label));
        wrap.appendChild(control);
        if (hint) wrap.appendChild(element('div', 'caption muted-body', hint));
        wrap.appendChild(element('div', 'inline-error'));

        this.controls[name] = control;
        this.node.appendChild(wrap);
        return control;
    }

    values() {
        const values = {};
        Object.entries(this.controls).forEach(([name, control]) => {
            values[name] = control.value.trim();
        });
        return values;
    }

    showError(name, message) {
        const control = this.controls[name];
        if (!control) return;
        control.classList.add('text-field--error');
        control.parentElement.querySelector('.inline-error').textContent = message;
    }

    clearErrors() {
        Object.values(this.controls).forEach((control) => {
            control.classList.remove('text-field--error');
            control.parentElement.querySelector('.inline-error').textContent = '';
        });
    }
}

export function toast(text, kind) {
    const stack = document.getElementById('toast-stack');
    while (stack.children.length >= MAX_TOASTS) stack.removeChild(stack.firstChild);

    const node = element('div', 'toast');
    node.appendChild(element('span', `toast__dot toast__dot--${kind || 'info'}`));
    node.appendChild(element('span', 'toast__text', text));
    stack.appendChild(node);
    setTimeout(() => node.remove(), TOAST_LIFETIME_MS);
}

/**
 * Shows a failed request. The thrown message is either a translation key, when
 * the browser could not reach the server at all, or a sentence the server
 * already wrote for a person to read.
 */
export function toastError(error, strings) {
    toast(strings[error.message] || error.message, 'bad');
}
