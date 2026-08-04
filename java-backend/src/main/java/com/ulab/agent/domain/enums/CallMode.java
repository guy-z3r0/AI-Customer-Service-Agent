package com.ulab.agent.domain.enums;

/**
 * The four situations a call can be in. A call starts in one of the first two
 * and can move to one of the last two while it is running.
 *
 * The rules about which move is allowed, and the wording the agent uses in each
 * mode, live in brain/CallModeMachine (Phase 4). This enum is only the label
 * that gets stored on a call and its transitions.
 */
public enum CallMode {
    NEW_CUSTOMER,
    EXISTING_CUSTOMER,
    WRONG_NUMBER,
    COMPLEX_REQUEST
}
