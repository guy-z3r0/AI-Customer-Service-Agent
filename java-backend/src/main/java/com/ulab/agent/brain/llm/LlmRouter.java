package com.ulab.agent.brain.llm;

import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.services.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Decides which language model answers a call, and hands back a client for it.
 *
 * The choice is made twice over: a business may name its own provider and model
 * in its AI settings, and whatever it leaves blank falls through to the global
 * Settings page. Nothing is cached, so changing the provider in Settings takes
 * effect on the next call rather than the next restart.
 */
@Service
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private static final String GEMINI = "gemini";
    private static final String OPENAI = "openai";

    private static final double DEFAULT_TEMPERATURE = 0.7;

    private final ConfigService config;

    public LlmRouter(ConfigService config) {
        this.config = config;
    }

    /** Which provider, model and temperature this call should use. */
    public record Selection(String providerId, String model, double temperature) {
    }

    /** @param settings the business's AI settings, or null when it has none */
    public Selection select(AiSettings settings) {
        String providerId = resolveProviderId(
                settings == null ? null : settings.getProviderOverride());
        String model = firstFilled(settings == null ? null : settings.getModelOverride(),
                defaultModel(providerId));
        double temperature = settings == null || settings.getTemperature() == null
                ? DEFAULT_TEMPERATURE
                : settings.getTemperature().doubleValue();
        return new Selection(providerId, model, temperature);
    }

    /**
     * True when the chosen provider has a real key. A placeholder is the normal
     * state of a fresh install, so this is a question every call asks rather
     * than a failure.
     */
    public boolean isReady(Selection selection) {
        return !config.isPlaceholder(keyName(selection.providerId()));
    }

    public LlmProvider provider(Selection selection) {
        String key = config.getString(keyName(selection.providerId()), "");
        if (ConfigService.isPlaceholderValue(key)) {
            throw new IllegalStateException(
                    "No API key is set for " + selection.providerId());
        }
        return OPENAI.equals(selection.providerId())
                ? new OpenAiProvider(key)
                : new GeminiProvider(key);
    }

    // ------------------------------------------------------------ internals --

    private String resolveProviderId(String override) {
        String wanted = firstFilled(override, config.getString("llm_provider", GEMINI))
                .trim().toLowerCase(Locale.ROOT);
        if (GEMINI.equals(wanted) || OPENAI.equals(wanted)) return wanted;

        log.warn("'{}' is not a language model this app knows — using {}", wanted, GEMINI);
        return GEMINI;
    }

    private String defaultModel(String providerId) {
        return OPENAI.equals(providerId)
                ? config.getString("openai_model_default", "gpt-4o-mini")
                : config.getString("llm_model", "gemini-2.0-flash");
    }

    private static String keyName(String providerId) {
        return OPENAI.equals(providerId) ? "openai_api_key" : "gemini_api_key";
    }

    private static String firstFilled(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
