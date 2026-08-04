package com.ulab.agent.api;

import com.ulab.agent.api.dto.ConfigEntryView;
import com.ulab.agent.api.dto.HealthView;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.services.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * What the panel's status bar reads every few seconds.
 *
 * This endpoint answers even when parts of the system are broken — that is the
 * whole point of it — so a failed database check becomes "down" in the reply
 * rather than an error response.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /** Long enough for a healthy voice server, short enough not to stall the panel. */
    private static final Duration VOICE_PING_TIMEOUT = Duration.ofSeconds(2);

    private final BusinessRepository businesses;
    private final ConfigService config;
    private final RestClient voiceServer;

    public HealthController(BusinessRepository businesses, ConfigService config,
                            @Value("${voice.internal-url:http://localhost:8090}") String voiceUrl) {
        this.businesses = businesses;
        this.config = config;
        this.voiceServer = RestClient.builder()
                .baseUrl(voiceUrl.replaceAll("/+$", ""))
                .requestFactory(timeoutFactory())
                .build();
    }

    @GetMapping
    public HealthView health() {
        return new HealthView(databaseState(), voiceServerState(), providerName(),
                providerKeyReady(), placeholderKeys());
    }

    private String databaseState() {
        try {
            businesses.count();
            return "up";
        } catch (RuntimeException e) {
            log.warn("Database health check failed: {}", e.toString());
            return "down";
        }
    }

    /**
     * Asks the voice server whether it is there. A failure is reported, not
     * thrown — the panel is meant to keep working while the voice half is down,
     * and this endpoint is how it finds out.
     */
    private String voiceServerState() {
        try {
            voiceServer.get().uri("/health").retrieve().toBodilessEntity();
            return "up";
        } catch (RuntimeException e) {
            log.debug("Voice server health check failed: {}", e.toString());
            return "down";
        }
    }

    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(VOICE_PING_TIMEOUT);
        factory.setReadTimeout(VOICE_PING_TIMEOUT);
        return factory;
    }

    private String providerName() {
        return config.getString("llm_provider", "gemini");
    }

    /** True once the selected provider's key is a real value rather than a placeholder. */
    private boolean providerKeyReady() {
        String keyName = "openai".equalsIgnoreCase(providerName()) ? "openai_api_key" : "gemini_api_key";
        return !config.isPlaceholder(keyName);
    }

    private List<String> placeholderKeys() {
        return config.listForPanel().stream()
                .filter(ConfigEntryView::placeholder)
                .map(ConfigEntryView::key)
                .toList();
    }
}
