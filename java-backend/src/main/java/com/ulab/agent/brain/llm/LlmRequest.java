package com.ulab.agent.brain.llm;

import java.util.List;

/**
 * One question put to a language model, in a shape neither vendor uses.
 *
 * Providers translate this into their own wire format, which is what lets the
 * Settings page swap Gemini for OpenAI without anything above this layer
 * noticing.
 *
 * @param system      the standing instructions: persona, knowledge, language
 * @param messages    the conversation so far, oldest first
 * @param toolSchemas tool definitions as JSON text, provider-neutral; empty
 *                    until the tools arrive in Phase 4
 */
public record LlmRequest(String model, String system, List<Message> messages,
                         List<String> toolSchemas, double temperature) {

    /** @param role "user" for the caller, "assistant" for the agent */
    public record Message(String role, String text) {

        public boolean isAgent() {
            return "assistant".equals(role);
        }
    }
}
