package com.ulab.agent.domain.enums;

/** The two languages the agent speaks. */
public enum Language {
    EN,
    BN;

    /** The two-letter code used in APIs, the panel and the voice server. */
    public String code() {
        return name().toLowerCase();
    }

    /** Reads a code or locale ("bn", "bn-BD"); anything unrecognised is English. */
    public static Language of(String raw) {
        return raw != null && raw.toLowerCase().startsWith("bn") ? BN : EN;
    }
}
