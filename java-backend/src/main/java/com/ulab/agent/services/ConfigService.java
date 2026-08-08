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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

    /**
     * A bare hostname, optionally with a port. No scheme, no path, no quotes —
     * the media URL is interpolated into a TwiML attribute, and a value with a
     * quote in it would close that attribute and append verbs of its own.
     */
    private static final java.util.regex.Pattern HOSTNAME =
            java.util.regex.Pattern.compile("^[A-Za-z0-9.\\-]+(:\\d{1,5})?$");

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
     * @return what happened, so the panel can say when something did not stick
     */
    @Transactional
    public UpdateResult update(Map<String, String> submitted) {
        int changed = 0;
        List<String> rejected = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (Map.Entry<String, String> entry : submitted.entrySet()) {
            AppConfigEntry stored = repo.findById(entry.getKey()).orElse(null);
            if (stored == null) {
                // Never created — a key this app does not know is a typo, and
                // silently returning success for it is how a typo survives.
                unknown.add(entry.getKey());
                continue;
            }

            String incoming = entry.getValue() == null ? "" : entry.getValue().trim();
            if (shouldKeepStoredValue(entry.getKey(), stored.getValueJson(), incoming)) continue;
            if (!isAcceptable(entry.getKey(), incoming)) {
                log.warn("Refused a value for '{}' that does not look like one", entry.getKey());
                rejected.add(entry.getKey());
                continue;
            }

            String newJson = toJsonValue(stored.getValueJson(), incoming);
            if (newJson.equals(stored.getValueJson())) continue;

            stored.setValueJson(newJson);
            repo.save(stored);
            changed++;
        }
        if (changed > 0) log.info("Settings updated: {} value(s) changed", changed);
        return new UpdateResult(changed, rejected, unknown);
    }

    /**
     * @param rejected keys whose value was not the right shape for them
     * @param unknown  keys this app has never heard of, usually a typo
     */
    public record UpdateResult(int changed, List<String> rejected, List<String> unknown) {
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

    /**
     * Whether a submitted value is even the right shape for its key.
     *
     * Three of these settings are worth more than the rest to somebody who can
     * write them: the mail host decides where a transcript is delivered, the
     * media URL is interpolated into TwiML, and the credentials path is read
     * back through the health endpoint, which makes an arbitrary path a way of
     * asking whether a file exists. A blank is always allowed — that is how a
     * setting is cleared.
     */
    private static boolean isAcceptable(String key, String value) {
        if (value.isEmpty() || value.startsWith(PLACEHOLDER_PREFIX)) return true;

        return switch (key) {
            case "public_media_url", "smtp_host" -> HOSTNAME.matcher(value).matches();
            case "smtp_port" -> isPortNumber(value);
            case "gcp_credentials_path" -> isUnderSecrets(value);
            case "llm_provider" -> value.equals("gemini") || value.equals("openai");
            case "default_language" -> value.equals("en") || value.equals("bn");
            case "stt_provider", "tts_provider" ->
                    value.equals("auto") || value.equals("gcp") || value.equals("fallback");
            case "smtp_auth", "log_unsent_email_body" ->
                    value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false");
            default -> true;
        };
    }

    /**
     * The credentials file has to sit directly in a folder called "secrets".
     *
     * Whatever this points at is checked for existence by the voice server and
     * the answer is reported through /health, so an arbitrary path turns that
     * endpoint into a way of asking whether any file on the container exists.
     * Rejecting ".." was not enough on its own — /etc/shadow contains no dots
     * at all. Naming the folder covers both the "./secrets" a laptop uses and
     * the "/app/secrets" compose mounts, and nothing else.
     */
    private static boolean isUnderSecrets(String value) {
        String path = value.replace('\\', '/');
        if (path.contains("..")) return false;

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return false;
        return path.substring(0, lastSlash).endsWith("secrets");
    }

    private static boolean isPortNumber(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** Keeps a setting's existing JSON type so numbers do not turn into strings. */
    private static String toJsonValue(String storedJson, String incoming) {
        JsonElement stored = JsonParser.parseString(storedJson);
        if (stored.isJsonPrimitive() && stored.getAsJsonPrimitive().isNumber()) {
            try {
                // Assigned in two statements rather than with a conditional
                // expression on purpose: Java promotes the two branches of a
                // ternary to a common numeric type, so `cond ? Long : Double`
                // is a double either way — and a port of 465 was being stored
                // as 465.0.
                Number parsed;
                if (incoming.matches("-?\\d+")) parsed = Long.valueOf(incoming);
                else parsed = Double.valueOf(incoming);
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

    /**
     * What the panel is shown in place of a stored secret.
     *
     * The point of it is that an operator can tell which credential is in
     * there without the credential leaving the server. Showing the last four
     * characters does that, and is what most consoles do — but the last four
     * of twilio_account_sid are four characters of a public identifier, and
     * that is a meaningful fraction of one. A length and the first six hex
     * digits of the value's SHA-256 identify it just as well and give away
     * nothing: two different keys look different, the same key looks the same
     * every time, and the digest cannot be walked back to the key.
     *
     * It also has to be stable, because a secret field submitted still holding
     * this text means "leave what is stored alone" — see shouldKeepStoredValue.
     */
    private static String mask(String value) {
        return "•••• %d chars · #%s".formatted(value.length(), fingerprint(value));
    }

    private static String fingerprint(String value) {
        try {
            byte[] sum = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(6);
            for (int i = 0; i < 3; i++) hex.append("%02x".formatted(sum[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every Java platform.
            throw new IllegalStateException(impossible);
        }
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
        for (String key : List.of("smtp_host", "smtp_port", "smtp_auth", "smtp_username",
                "smtp_password", "smtp_from", "log_unsent_email_body")) groups.put(key, "email");
        return groups;
    }
}
