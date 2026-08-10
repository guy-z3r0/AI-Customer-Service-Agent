package com.ulab.agent.api;

import com.ulab.agent.api.dto.ConfigEntryView;
import com.ulab.agent.services.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    private static final ParameterizedTypeReference<Map<String, Object>> VOICE_LIST =
            new ParameterizedTypeReference<>() {
            };

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
    public ConfigService.UpdateResult update(@RequestBody Map<String, String> values) {
        return config.update(values);
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
            // Not a rescan. Enumerating voices starts a speech engine, and this
            // is called every time Settings is opened — refreshing on each one
            // turned a page load into an unbounded amount of work.
            //
            // The type reference rather than Map.class: the raw form was the one
            // unchecked warning in this codebase, and an empty body would have
            // been returned to the panel as a null it does not expect.
            Map<String, Object> found = voiceServer.get().uri("/voices").retrieve().body(VOICE_LIST);
            return found == null ? noVoices() : found;
        } catch (RuntimeException e) {
            log.info("Could not read the voice list from the voice server: {}", e.toString());
            return noVoices();
        }
    }

    /**
     * What the page falls back to: a plain text field, as it always used to be.
     *
     * "unknown" for the credentials rather than "missing" — a voice server that
     * is not running has not told us anything, and warning that a key file is
     * absent when nobody looked would send an operator hunting for a file that
     * is already there.
     */
    private static Map<String, Object> noVoices() {
        return Map.of("voices", List.of(), "speaks", List.of(), "provider", "unknown",
                "credentials", "unknown", "nearMisses", List.of());
    }

    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(VOICE_LIST_TIMEOUT);
        factory.setReadTimeout(VOICE_LIST_TIMEOUT);
        return factory;
    }
}
