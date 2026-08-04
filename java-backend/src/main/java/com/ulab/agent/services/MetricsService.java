package com.ulab.agent.services;

import com.ulab.agent.api.dto.MetricsDtos;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.CallMessage;
import com.ulab.agent.domain.CallRecord;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.repo.CallMessageRepository;
import com.ulab.agent.repo.CallRecordRepository;
import com.ulab.agent.repo.ClientRepository;
import com.ulab.agent.repo.KbEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The numbers on the Dashboard.
 *
 * Everything here is counted on demand rather than kept in a running tally.
 * A single operator's call volume is nowhere near enough to make that
 * expensive, and a number that is computed from the rows it describes cannot
 * drift away from them.
 */
@Service
public class MetricsService {

    /** Under this and the reply felt immediate; the objective in the proposal. */
    public static final long LATENCY_TARGET_MS = 2000;

    private final BusinessRepository businesses;
    private final KbEntryRepository kbEntries;
    private final ClientRepository clients;
    private final CallRecordRepository calls;
    private final CallMessageRepository messages;
    private final ConfigService config;

    public MetricsService(BusinessRepository businesses, KbEntryRepository kbEntries,
                          ClientRepository clients, CallRecordRepository calls,
                          CallMessageRepository messages, ConfigService config) {
        this.businesses = businesses;
        this.kbEntries = kbEntries;
        this.clients = clients;
        this.calls = calls;
        this.messages = messages;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public MetricsDtos.Summary summary(String voiceServerState) {
        List<CallRecord> allCalls = calls.findAll();
        Instant midnight = Instant.now().truncatedTo(ChronoUnit.DAYS);

        return new MetricsDtos.Summary(
                businesses.count(),
                kbEntries.count(),
                clients.count(),
                allCalls.size(),
                allCalls.stream().filter(call -> call.getStartedAt().isAfter(midnight)).count(),
                medianReplyMs(),
                capabilities(voiceServerState),
                recentCalls(allCalls));
    }

    /**
     * The middle reply time across every turn ever taken. The median rather
     * than the mean, because one call that stalled on a cold connection should
     * not be able to make a hundred fast ones look slow.
     */
    private Long medianReplyMs() {
        List<Long> replies = messages.findAll().stream()
                .map(MetricsService::replyMillis)
                .filter(ms -> ms != null && ms >= 0)
                .sorted()
                .toList();
        if (replies.isEmpty()) return null;

        int middle = replies.size() / 2;
        return replies.size() % 2 == 1
                ? replies.get(middle)
                : (replies.get(middle - 1) + replies.get(middle)) / 2;
    }

    private static Long replyMillis(CallMessage message) {
        if (message.getTSttFinal() == null || message.getTTtsFirst() == null) return null;
        return Duration.between(message.getTSttFinal(), message.getTTtsFirst()).toMillis();
    }

    /**
     * What the system can actually do right now.
     *
     * This is the honest version of a feature list: each entry says whether the
     * thing works, works in a reduced form, or is switched off waiting for a
     * credential — which is the difference between a demo that surprises you
     * and one that does not.
     */
    private List<MetricsDtos.Capability> capabilities(String voiceServerState) {
        String provider = config.getString("llm_provider", "gemini");
        boolean modelReady = !config.isPlaceholder(
                "openai".equalsIgnoreCase(provider) ? "openai_api_key" : "gemini_api_key");
        boolean googleSpeech = googleCredentialsPresent();
        boolean twilio = !config.isPlaceholder("twilio_account_sid");
        boolean email = !config.isPlaceholder("smtp_host");

        return List.of(
                new MetricsDtos.Capability("database", "ready", null),
                new MetricsDtos.Capability("voice",
                        "up".equals(voiceServerState) ? "ready" : "off", null),
                new MetricsDtos.Capability("model", modelReady ? "ready" : "off", provider),
                new MetricsDtos.Capability("speech", googleSpeech ? "ready" : "degraded", null),
                new MetricsDtos.Capability("telephony", twilio ? "ready" : "off", null),
                new MetricsDtos.Capability("email", email ? "ready" : "off", null));
    }

    /**
     * Whether Google speech will actually be used on the next call.
     *
     * The setting holding the path is not a placeholder — it has a sensible
     * default — so asking whether it is one says nothing. What decides it is
     * whether a key file is really sitting there, which is the same test the
     * voice server makes when it chooses a provider.
     */
    private boolean googleCredentialsPresent() {
        String path = config.getString("gcp_credentials_path", "");
        if (path.isBlank() || path.startsWith(ConfigService.PLACEHOLDER_PREFIX)) return false;
        File keyFile = new File(path);
        return keyFile.isFile() && keyFile.length() > 0;
    }

    private List<MetricsDtos.RecentCall> recentCalls(List<CallRecord> allCalls) {
        List<CallRecord> newest = allCalls.stream()
                .sorted(Comparator.comparing(CallRecord::getStartedAt).reversed())
                .limit(10)
                .toList();
        if (newest.isEmpty()) return List.of();

        Map<UUID, String> names = businesses.findAll().stream()
                .collect(Collectors.toMap(Business::getId, Business::getName));

        Map<UUID, Long> turnCounts = messages.findAll().stream()
                .collect(Collectors.groupingBy(CallMessage::getCallId, Collectors.counting()));

        return newest.stream().map(call -> new MetricsDtos.RecentCall(
                call.getId(),
                names.getOrDefault(call.getBusinessId(), ""),
                call.getStartedAt().toString(),
                call.getEndedAt() == null ? null
                        : Duration.between(call.getStartedAt(), call.getEndedAt()).toSeconds(),
                call.getFinalMode() == null ? null : call.getFinalMode().name(),
                call.getFinalLanguage() == null ? null : call.getFinalLanguage().name(),
                call.getTelephony().name(),
                turnCounts.getOrDefault(call.getId(), 0L))).toList();
    }
}
