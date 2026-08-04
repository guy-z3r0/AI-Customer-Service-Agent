package com.ulab.agent.api;

import com.ulab.agent.api.dto.ConfigEntryView;
import com.ulab.agent.services.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The global settings behind the panel's Settings page.
 *
 * GET never returns a real credential: secret values come back masked. PUT
 * takes a plain key-to-value map; a secret left blank keeps what is stored.
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    /** A voice list is worth waiting a moment for; a hung one is not. */
    private static final Duration VOICE_LIST_TIMEOUT = Duration.ofSeconds(8);

    private final ConfigService config;
    private final RestClient voiceServer;

    public ConfigController(ConfigService config,
                            @Value("${voice.internal-url:http://localhost:8090}") String voiceUrl) {
        this.config = config;
        this.voiceServer = RestClient.builder()
                .baseUrl(voiceUrl.replaceAll("/+$", ""))
                .requestFactory(timeoutFactory())
                .build();
    }

    @GetMapping
    public List<ConfigEntryView> list() {
        return config.listForPanel();
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, String> values) {
        return Map.of("changed", config.update(values));
    }

    /**
     * The voices the Settings page offers as a menu.
     *
     * Only the voice server can answer this — the voices are whatever the
     * operating system has installed, plus Google's once its credentials are
     * set. A voice server that is down is not an error here: the page falls
     * back to a plain text field, which is what it always used to be.
     */
    @GetMapping("/voices")
    public Map<String, Object> voices() {
        try {
            return voiceServer.get().uri("/voices").retrieve().body(Map.class);
        } catch (RuntimeException e) {
            log.info("Could not read the voice list from the voice server: {}", e.toString());
            return Map.of("voices", List.of(), "speaks", List.of(), "provider", "unknown");
        }
    }

    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(VOICE_LIST_TIMEOUT);
        factory.setReadTimeout(VOICE_LIST_TIMEOUT);
        return factory;
    }
}
