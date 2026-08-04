package com.ulab.agent.domain.enums;

/** The four sections a knowledge base entry can belong to. */
public enum KbKind {
    /** What the business is and where it is. */
    ABOUT,
    /** Something the business sells or does, usually with a price. */
    SERVICE,
    /** A rule the agent must not bend: warranty, refunds, deposits. */
    POLICY,
    /** A question customers ask often, paired with its answer. */
    FAQ
}
