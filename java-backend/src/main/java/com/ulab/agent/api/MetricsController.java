package com.ulab.agent.api;

import com.ulab.agent.api.dto.MetricsDtos;
import com.ulab.agent.services.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Dashboard's one call.
 *
 * It borrows the health endpoint's view of the voice server rather than
 * pinging it a second time, so the dashboard and the status bar can never
 * disagree about whether the voice half is running.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metrics;
    private final HealthController health;

    public MetricsController(MetricsService metrics, HealthController health) {
        this.metrics = metrics;
        this.health = health;
    }

    @GetMapping("/summary")
    public MetricsDtos.Summary summary() {
        return metrics.summary(health.health().voiceServer());
    }
}
