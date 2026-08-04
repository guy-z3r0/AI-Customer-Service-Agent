/*
 * How a finished call is described in a table.
 *
 * The screening mode is what the agent decided about the caller, and on a call
 * where nobody ever spoke it decided nothing — it is still holding the mode the
 * call opened in. Reporting that as "New caller" is misleading: it reads as a
 * conversation with a new customer when in fact there was no conversation.
 *
 * So a call with no caller turns is reported as what it was — nobody answered —
 * while still saying who was dialled, because "the customer we rang did not
 * pick up" and "somebody rang and said nothing" are different events.
 *
 * This is worked out for display rather than stored. What the screening machine
 * decided is a fact about the call and stays in the database untouched; this is
 * a sentence about that fact.
 */

import { element, tagBadge } from './components.js';

const MODE_HUES = {
    NEW_CUSTOMER: 'azure',
    EXISTING_CUSTOMER: 'jade',
    WRONG_NUMBER: 'rose',
    COMPLEX_REQUEST: 'gold'
};

/**
 * The badge for one screening mode.
 *
 * Used where a mode really is the subject — the list of transitions a call went
 * through — rather than where the question is how the call turned out.
 */
export function modeBadge(mode, t) {
    if (!mode) return element('span', null, '');
    return tagBadge(t[`mode.${mode.toLowerCase()}`] || mode, MODE_HUES[mode] || 'azure');
}

/** True when the caller never said anything the agent could hear. */
export function nobodySpoke(call) {
    return (call.turns || 0) === 0;
}

/**
 * The badge for one call's outcome.
 *
 * @param call a CallListItem: turns, mode and caller are what matter here
 */
export function outcomeBadge(call, t) {
    if (nobodySpoke(call)) {
        return tagBadge(t[call.caller ? 'outcome.known_no_answer' : 'outcome.no_answer'], 'rose');
    }
    return modeBadge(call.mode, t);
}
