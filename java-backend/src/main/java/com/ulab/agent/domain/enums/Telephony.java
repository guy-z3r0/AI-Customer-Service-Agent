package com.ulab.agent.domain.enums;

/** How the audio of a call reached the agent. */
public enum Telephony {
    /** Straight from a browser microphone over a WebSocket. Always available. */
    BROWSER,
    /** Through Twilio Voice. Only works once Twilio credentials are configured. */
    TWILIO
}
