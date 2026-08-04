package com.ulab.agent.api.dto;

import java.util.List;

/**
 * What the panel's status bar reports.
 *
 * @param database    "up" or "down"
 * @param voiceServer state of the Python voice server ("not_built" until Phase 2)
 * @param llmProvider which language model provider is selected
 * @param llmKeyReady false while that provider's key is still a placeholder
 * @param placeholders keys still holding a PLACEHOLDER_ value, so the panel can
 *                     say how much setup is left
 */
public record HealthView(String database, String voiceServer, String llmProvider,
                         boolean llmKeyReady, List<String> placeholders) {
}
