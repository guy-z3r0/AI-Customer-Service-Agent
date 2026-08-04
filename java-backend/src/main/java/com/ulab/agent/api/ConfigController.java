package com.ulab.agent.api;

import com.ulab.agent.api.dto.ConfigEntryView;
import com.ulab.agent.services.ConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final ConfigService config;

    public ConfigController(ConfigService config) {
        this.config = config;
    }

    @GetMapping
    public List<ConfigEntryView> list() {
        return config.listForPanel();
    }

    @PutMapping
    public Map<String, Object> update(@RequestBody Map<String, String> values) {
        return Map.of("changed", config.update(values));
    }
}
