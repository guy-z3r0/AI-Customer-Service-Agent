package com.ulab.agent.brain.llm;

/**
 * One language model vendor, behind a door narrow enough that the rest of the
 * app cannot tell which one is on the other side.
 */
public interface LlmProvider {

    /** "gemini" or "openai" — the same word the Settings page stores. */
    String id();

    /**
     * Runs one turn and reports the reply as it arrives. Blocks until the reply
     * is finished or has failed, so callers run it off the socket thread.
     */
    void chatStream(LlmRequest request, LlmStreamHandler handler);
}
