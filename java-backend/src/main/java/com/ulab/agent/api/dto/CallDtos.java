package com.ulab.agent.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * The bodies the call endpoints exchange, kept together because they only make
 * sense as a set — one call's start, its lines and its end.
 */
public final class CallDtos {

    private CallDtos() {
    }

    /**
     * @param telephony  "browser" or "twilio"; anything else is treated as browser
     * @param language   "en" or "bn"; null means whatever Settings says
     * @param clientCode dial as a customer already on the records, so the call
     *                   opens knowing who it is with; null for an unknown caller
     */
    public record StartRequest(String telephony, String language, String clientCode) {
    }

    /**
     * @param voiceUrl the websocket the browser should open to send its
     *                 microphone audio — the panel never hard-codes this,
     *                 because the address differs between Docker and a laptop
     */
    public record StartView(UUID callId, String voiceUrl, String language, String telephony) {
    }

    /**
     * One finished line to write down. It is built by the brain rather than
     * posted over HTTP — the voice server reaches Java over the per-call
     * websocket now — so it carries no validation annotations.
     *
     * @param role      "caller", "agent" or "system"
     * @param turnSeq   which exchange of the call this belongs to, counted by
     *                  the brain; 0 for a line that is not part of a turn
     * @param tSttFinal epoch milliseconds when speech recognition finished
     * @param tLlmFirst epoch milliseconds of the model's first word
     * @param tTtsFirst epoch milliseconds the first audio byte went out
     */
    public record LineToStore(String role, String text, String language, String mode,
                              int turnSeq, Long tSttFinal, Long tLlmFirst, Long tTtsFirst) {

        public static LineToStore caller(String text, String language, String mode,
                                         int turnSeq, Long tSttFinal) {
            return new LineToStore("caller", text, language, mode, turnSeq, tSttFinal, null, null);
        }

        public static LineToStore agent(String text, String language, String mode, int turnSeq,
                                        Long tSttFinal, Long tLlmFirst, Long tTtsFirst) {
            return new LineToStore("agent", text, language, mode, turnSeq,
                    tSttFinal, tLlmFirst, tTtsFirst);
        }
    }

    /**
     * The operator overruling the agent's screening from the panel.
     *
     * @param mode   one of the CallMode names
     * @param reason why, in plain words; a default is written when it is left out
     */
    public record ModeRequest(@NotBlank String mode, String reason) {
    }

    public record EndRequest(String reason) {
    }
}
