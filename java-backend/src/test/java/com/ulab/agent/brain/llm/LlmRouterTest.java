package com.ulab.agent.brain.llm;

import com.ulab.agent.domain.AiSettings;
import com.ulab.agent.services.ConfigService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Swapping the provider in Settings has to change which API answers the next
 * call, and a key that was never filled in has to be a polite no rather than a
 * crash. Both are decided here.
 */
class LlmRouterTest {

    @Test
    void settingsDecideWhenTheBusinessHasNoOpinion() {
        LlmRouter router = routerWith(Map.of(
                "llm_provider", "gemini",
                "llm_model", "gemini-2.0-flash",
                "gemini_api_key", "a-real-key"));

        LlmRouter.Selection selection = router.select(null);

        assertEquals("gemini", selection.providerId());
        assertEquals("gemini-2.0-flash", selection.model());
        assertTrue(router.isReady(selection));
        assertInstanceOf(GeminiProvider.class, router.provider(selection));
    }

    @Test
    void aBusinessMayOverrideTheProviderAndGetsThatVendorsDefaultModel() {
        LlmRouter router = routerWith(Map.of(
                "llm_provider", "gemini",
                "llm_model", "gemini-2.0-flash",
                "openai_model_default", "gpt-4o-mini",
                "openai_api_key", "a-real-key"));

        AiSettings settings = new AiSettings();
        settings.setProviderOverride("openai");

        LlmRouter.Selection selection = router.select(settings);

        assertEquals("openai", selection.providerId());
        assertEquals("gpt-4o-mini", selection.model(), "not the Gemini model left in Settings");
        assertInstanceOf(OpenAiProvider.class, router.provider(selection));
    }

    @Test
    void aPlaceholderKeyIsNotReady() {
        LlmRouter router = routerWith(Map.of(
                "llm_provider", "gemini",
                "gemini_api_key", "PLACEHOLDER_GEMINI_API_KEY"));

        assertFalse(router.isReady(router.select(null)));
    }

    @Test
    void anUnknownProviderNameFallsBackToGemini() {
        LlmRouter router = routerWith(Map.of("llm_provider", "llama-on-a-toaster"));

        assertEquals("gemini", router.select(null).providerId());
    }

    /** Settings without a database behind them. */
    private static LlmRouter routerWith(Map<String, String> values) {
        Map<String, String> stored = new HashMap<>(values);
        return new LlmRouter(new ConfigService(null) {
            @Override
            public String getString(String key, String fallback) {
                return stored.getOrDefault(key, fallback);
            }

            @Override
            public boolean isPlaceholder(String key) {
                return ConfigService.isPlaceholderValue(stored.get(key));
            }
        });
    }
}
