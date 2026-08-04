package com.ulab.agent.domain.enums;

/** Who said a line in a call transcript. */
public enum MessageRole {
    /** The person who called. */
    CALLER,
    /** The AI agent. */
    AGENT,
    /** The app itself, e.g. "call transferred to a human". */
    SYSTEM
}
