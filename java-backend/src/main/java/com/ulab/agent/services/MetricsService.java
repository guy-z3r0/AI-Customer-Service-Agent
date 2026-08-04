package com.ulab.agent.services;

import com.ulab.agent.api.dto.MetricsDtos;
import com.ulab.agent.domain.CallMessage;
import com.ulab.agent.domain.CallRecord;
import com.ulab.agent.domain.enums.CallMode;
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
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    /** The dashboard shows this many of the newest calls. */
    private static final int RECENT_CALLS = 10;

    private final BusinessRepository businesses;
    private final KbEntryRepository kbEntries;
    private final ClientRepository clients;
    private final CallRecordRepository calls;
    private final CallMessageRepository messages;
    private final CallHistoryService history;
    private final ConfigService config;

    public MetricsService(BusinessRepository businesses, KbEntryRepository kbEntries,
                          ClientRepository clients, CallRecordRepository calls,
                          CallMessageRepository messages, CallHistoryService history,
                          ConfigService config) {
        this.businesses = businesses;
        this.kbEntries = kbEntries;
        this.clients = clients;
        this.calls = calls;
        this.messages = messages;
        this.history = history;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public MetricsDtos.Summary summary(String voiceServerState) {
        List<CallRecord> allCalls = calls.findAll();
        Instant midnight = Instant.now().truncatedTo(ChronoUnit.DAYS);
        List<Long> replies = replyTimes();

        return new MetricsDtos.Summary(
                businesses.count(),
                kbEntries.count(),
                clients.count(),
                allCalls.size(),
                allCalls.stream().filter(call -> call.getStartedAt().isAfter(midnight)).count(),
                percentile(replies, 50),
                percentile(replies, 90),
                capabilities(voiceServerState),
                modeCounts(allCalls),
                history.list(null, RECENT_CALLS));
    }

    /** Every turn's reply time, oldest to slowest, ready to be cut at a rank. */
    private List<Long> replyTimes() {
        return messages.findAll().stream()
                .map(MetricsService::replyMillis)
                .filter(ms -> ms != null && ms >= 0)
                .sorted()
                .toList();
    }

    /**
     * Percentiles rather than an average, because one call that stalled on a
     * cold connection should not be allowed to make a hundred fast ones look
     * slow. The median says what a caller usually waits; the 90th says what the
     * unlucky one in ten waited, which is the number that decides whether the
     * system is really under two seconds or only mostly.
     */
    private static Long percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) return null;

        int rank = (int) Math.ceil(sorted.size() * percent / 100.0) - 1;
        return sorted.get(Math.clamp(rank, 0, sorted.size() - 1));
    }

    /**
     * How calls have ended up, across all four kinds.
     *
     * A call that was never reclassified has no final mode on its row, and it
     * is counted as what it opened as: a new caller. Leaving those out would
     * make the ordinary call — the one that simply worked — the one kind the
     * dashboard never showed.
     */
    private static List<MetricsDtos.ModeCount> modeCounts(List<CallRecord> allCalls) {
        Map<CallMode, Long> counted = allCalls.stream().collect(Collectors.groupingBy(
                call -> call.getFinalMode() == null ? CallMode.NEW_CUSTOMER : call.getFinalMode(),
                () -> new EnumMap<>(CallMode.class), Collectors.counting()));

        return Arrays.stream(CallMode.values())
                .map(mode -> new MetricsDtos.ModeCount(mode.name(), counted.getOrDefault(mode, 0L)))
                .toList();
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

}
