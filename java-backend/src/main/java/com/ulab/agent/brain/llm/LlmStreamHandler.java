package com.ulab.agent.brain.llm;

/**
 * What a provider calls back into while a reply is arriving.
 *
 * The whole layer is streaming rather than request/response because the caller
 * is waiting on a telephone. A model that takes three seconds to write four
 * sentences can still start speaking after half a second, and that difference
 * is the whole two-second budget.
 *
 * Exactly one of onDone or onError is called, and always last.
 */
public interface LlmStreamHandler {

    /** A fragment of the reply. Fragments are not word- or sentence-aligned. */
    void onTextDelta(String text);

    /**
     * The model asked for a tool instead of, or as well as, speaking.
     *
     * @param argumentsJson the arguments object as JSON text, never null
     */
    void onToolCall(String name, String argumentsJson);

    void onDone();

    void onError(Throwable error);
}
