package com.ulab.agent.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The shapes behind the business editor's six tabs.
 *
 * They live together because they are one screen's worth of forms — knowledge,
 * persona, escalation — and only mean anything as a set. Everything here is
 * plain text an owner types; nothing is derived and nothing is secret.
 */
public final class EditorDtos {

    private EditorDtos() {
    }

    /** @param question filled in only for FAQ entries; the rest carry it all in content */
    public record KbEntryView(UUID id, String kind, String question, String content, int sortOrder) {
    }

    public record KbUpsertRequest(
            @NotBlank String kind,
            @Size(max = 500) String question,
            @NotBlank @Size(max = 4000) String content,
            Integer sortOrder) {
    }

    /**
     * The agent's personality for one business.
     *
     * @param providerOverride null means "use whatever the Settings page says",
     *                         which is the normal state
     */
    public record AiSettingsView(String personaName, String roleDescription, String replyStyle,
                                 String greetingEn, String greetingBn, String providerOverride,
                                 String modelOverride, BigDecimal temperature,
                                 int maxHistoryTurns) {
    }

    public record AiSettingsRequest(
            @NotBlank @Size(max = 80) String personaName,
            @NotBlank @Size(max = 2000) String roleDescription,
            @NotBlank @Size(max = 2000) String replyStyle,
            @NotBlank @Size(max = 600) String greetingEn,
            @NotBlank @Size(max = 600) String greetingBn,
            @Size(max = 40) String providerOverride,
            @Size(max = 80) String modelOverride,
            @Min(0) @Max(2) BigDecimal temperature,
            @Min(2) @Max(60) Integer maxHistoryTurns) {
    }

    /** @param priority who is told first; 1 is first */
    public record EscalationView(UUID id, String name, String email, int priority) {
    }

    public record EscalationRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 200) String email,
            @Min(1) @Max(99) Integer priority) {
    }
}
