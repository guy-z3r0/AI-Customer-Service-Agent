package com.ulab.agent.api.dto;

/**
 * One setting as the panel sees it.
 *
 * @param key         the app_config key, also used as the form field name
 * @param group       which Settings section it belongs to (llm, voice, call, twilio, email)
 * @param value       the value to show — masked when the key is a secret
 * @param secret      true when the real value must never reach the browser
 * @param placeholder true while the value is still a PLACEHOLDER_ stand-in
 */
public record ConfigEntryView(String key, String group, String value,
                              boolean secret, boolean placeholder) {
}
