package com.ulab.agent.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.ulab.agent.api.dto.ConfigEntryView;
import com.ulab.agent.domain.AppConfigEntry;
import com.ulab.agent.repo.AppConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes the global settings held in the app_config table.
 *
 * Two ideas run through this class:
 *
 *  - Placeholders. Every credential ships as the literal text
 *    "PLACEHOLDER_SOMETHING". A feature that needs one checks
 *    {@link #isPlaceholder(String)} and switches itself off politely instead of
 *    failing, so the app always boots with no keys at all.
 *
 *  - Masking. Secret values are never sent to the browser in full. The panel
 *    gets a masked version, and a secret field left blank on save means
 *    "keep what is already stored".
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    /** Any value that starts with this is a stand-in, not a real credential. */
    public static final String PLACEHOLDER_PREFIX = "PLACEHOLDER_";

    /** Keys whose value must never leave the server in readable form. */
    private static final Set<String> SECRET_KEYS = Set.of(
            "gemini_api_key", "openai_api_key",
            "twilio_auth_token", "twilio_api_key_secret", "twilio_account_sid",
            "smtp_password");

    /** Which section of the Settings page a key belongs to. */
    private static final Map<String, String> KEY_GROUPS = buildKeyGroups();

    private final AppConfigRepository repo;

    public ConfigService(AppConfigRepository repo) {
        this.repo = repo;
    }

    // ---------------------------------------------------------------- reads --

    public String getString(String key, String fallback) {
        JsonElement value = rawValue(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        return value.getAsString();
    }

    public int getInt(String key, int fallback) {
        JsonElement value = rawValue(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsInt();
        } catch (NumberFormatException e) {
            log.warn("Setting '{}' is not a whole number; using {}", key, fallback);
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        JsonElement value = rawValue(key);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            return value.getAsDouble();
        } catch (NumberFormatException e) {
            log.warn("Setting '{}' is not a number; using {}", key, fallback);
            return fallback;
        }
    }

    /** True when the key is missing or still holds its PLACEHOLDER_ stand-in. */
    public boolean isPlaceholder(String key) {
        return isPlaceholderValue(getString(key, PLACEHOLDER_PREFIX + "MISSING"));
    }

    public static boolean isPlaceholderValue(String value) {
        return value == null || value.isBlank() || value.startsWith(PLACEHOLDER_PREFIX);
    }

    /** Every setting, ready for the panel: grouped, ordered, secrets masked. */
    public List<ConfigEntryView> listForPanel() {
        return repo.findAllByOrderByKeyAsc().stream()
                .map(this::toView)
                .sorted((a, b) -> Integer.compare(groupRank(a.group()), groupRank(b.group())))
                .toList();
    }

    // --------------------------------------------------------------- writes --

    /**
     * Saves the values the panel submitted. A secret arrives blank, or still
     * masked, when the operator did not retype it — those are skipped so the
     * stored credential survives an ordinary save.
     *
     * @return how many settings actually changed
     */
    @Transactional
    public int update(Map<String, String> submitted) {
        int changed = 0;
        for (Map.Entry<String, String> entry : submitted.entrySet()) {
            AppConfigEntry stored = repo.findById(entry.getKey()).orElse(null);
            if (stored == null) continue;  // unknown keys are ignored, not created

            String incoming = entry.getValue() == null ? "" : entry.getValue().trim();
            if (shouldKeepStoredValue(entry.getKey(), stored.getValueJson(), incoming)) continue;

            String newJson = toJsonValue(stored.getValueJson(), incoming);
            if (newJson.equals(stored.getValueJson())) continue;

            stored.setValueJson(newJson);
            repo.save(stored);
            changed++;
        }
        if (changed > 0) log.info("Settings updated: {} value(s) changed", changed);
        return changed;
    }

    // -------------------------------------------------------------- internals --

    private JsonElement rawValue(String key) {
        return repo.findById(key)
                .map(entry -> JsonParser.parseString(entry.getValueJson()))
                .orElse(null);
    }

    private ConfigEntryView toView(AppConfigEntry entry) {
        String plain = readable(entry.getValueJson());
        boolean secret = SECRET_KEYS.contains(entry.getKey());
        boolean placeholder = isPlaceholderValue(plain);
        String shown = (secret && !placeholder) ? mask(plain) : plain;
        return new ConfigEntryView(entry.getKey(), groupOf(entry.getKey()), shown, secret, placeholder);
    }

    /** A blank or unchanged-looking secret must not overwrite a real credential. */
    private boolean shouldKeepStoredValue(String key, String storedJson, String incoming) {
        if (!SECRET_KEYS.contains(key)) return false;
        if (incoming.isEmpty()) return true;
        String stored = readable(storedJson);
        return !isPlaceholderValue(stored) && incoming.equals(mask(stored));
    }

    /** Keeps a setting's existing JSON type so numbers do not turn into strings. */
    private static String toJsonValue(String storedJson, String incoming) {
        JsonElement stored = JsonParser.parseString(storedJson);
        if (stored.isJsonPrimitive() && stored.getAsJsonPrimitive().isNumber()) {
            try {
                Number parsed = incoming.matches("-?\\d+")
                        ? Long.valueOf(incoming)
                        : Double.valueOf(incoming);
                return new JsonPrimitive(parsed).toString();
            } catch (NumberFormatException e) {
                return storedJson;  // reject the edit rather than change the type
            }
        }
        if (stored.isJsonPrimitive() && stored.getAsJsonPrimitive().isBoolean()) {
            return new JsonPrimitive(Boolean.parseBoolean(incoming)).toString();
        }
        return new JsonPrimitive(incoming).toString();
    }

    /** Turns the stored JSON back into the plain text an operator typed in. */
    private static String readable(String json) {
        JsonElement value = JsonParser.parseString(json);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return value.getAsString();
        return value.toString();
    }

    private static String mask(String value) {
        if (value.length() <= 4) return "••••";
        return "••••" + value.substring(value.length() - 4);
    }

    private static String groupOf(String key) {
        return KEY_GROUPS.getOrDefault(key, "other");
    }

    private static int groupRank(String group) {
        return switch (group) {
            case "llm" -> 0;
            case "voice" -> 1;
            case "call" -> 2;
            case "twilio" -> 3;
            case "email" -> 4;
            default -> 5;
        };
    }

    private static Map<String, String> buildKeyGroups() {
        Map<String, String> groups = new LinkedHashMap<>();
        for (String key : List.of("llm_provider", "llm_model", "gemini_api_key",
                "openai_api_key", "openai_model_default")) groups.put(key, "llm");
        for (String key : List.of("gcp_credentials_path", "stt_provider", "tts_provider",
                "tts_voice_en", "tts_voice_bn", "tts_rate", "tts_volume",
                "default_language")) groups.put(key, "voice");
        for (String key : List.of("inactivity_warn_s", "inactivity_hangup_s")) groups.put(key, "call");
        for (String key : List.of("twilio_account_sid", "twilio_auth_token", "twilio_api_key_sid",
                "twilio_api_key_secret", "twilio_twiml_app_sid", "twilio_caller_number",
                "public_media_url")) groups.put(key, "twilio");
        for (String key : List.of("smtp_host", "smtp_port", "smtp_username", "smtp_password",
                "smtp_from")) groups.put(key, "email");
        return groups;
    }
}
