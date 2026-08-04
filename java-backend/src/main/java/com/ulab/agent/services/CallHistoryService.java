package com.ulab.agent.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.CallMessage;
import com.ulab.agent.domain.CallRecord;
import com.ulab.agent.domain.CallSummary;
import com.ulab.agent.domain.Client;
import com.ulab.agent.domain.ModeTransition;
import com.ulab.agent.domain.enums.MessageRole;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.repo.CallMessageRepository;
import com.ulab.agent.repo.CallRecordRepository;
import com.ulab.agent.repo.CallSummaryRepository;
import com.ulab.agent.repo.ClientRepository;
import com.ulab.agent.repo.ModeTransitionRepository;
import com.ulab.agent.utils.Lang;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reading a finished call back: the list, one call in full, and the plain text
 * file of it.
 *
 * This is deliberately not part of {@link CallLogService}. That class writes a
 * call down while it is happening and tells the panel about every line as it
 * lands; this one only ever reads, has no live feed, and answers questions
 * nobody asks until the call is over. Keeping them apart keeps both readable.
 */
@Service
public class CallHistoryService {

    /** How many calls the history page asks for at once. */
    public static final int DEFAULT_LIMIT = 50;

    private final CallRecordRepository calls;
    private final CallMessageRepository messages;
    private final ModeTransitionRepository transitions;
    private final CallSummaryRepository summaries;
    private final BusinessRepository businesses;
    private final ClientRepository clients;

    public CallHistoryService(CallRecordRepository calls, CallMessageRepository messages,
                              ModeTransitionRepository transitions, CallSummaryRepository summaries,
                              BusinessRepository businesses, ClientRepository clients) {
        this.calls = calls;
        this.messages = messages;
        this.transitions = transitions;
        this.summaries = summaries;
        this.businesses = businesses;
        this.clients = clients;
    }

    // ----------------------------------------------------------- the list --

    /**
     * The newest calls first.
     *
     * The turn counts are gathered in one sweep rather than one query per row.
     * A single operator's call log is small enough that reading it whole costs
     * less than the round trips would.
     *
     * @param businessId only this business's calls, or null for all of them
     */
    @Transactional(readOnly = true)
    public List<CallDtos.CallListItem> list(UUID businessId, int limit) {
        List<CallRecord> found = businessId == null
                ? calls.findAll()
                : calls.findByBusinessIdOrderByStartedAtDesc(businessId);

        List<CallRecord> newest = found.stream()
                .sorted(Comparator.comparing(CallRecord::getStartedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
        if (newest.isEmpty()) return List.of();

        Names names = namesFor(newest);
        Map<UUID, Long> turnCounts = turnCounts();
        Set<UUID> summarised = summaries.findAllById(newest.stream().map(CallRecord::getId).toList())
                .stream().map(CallSummary::getCallId).collect(Collectors.toSet());

        return newest.stream()
                .map(call -> toListItem(call, names, turnCounts, summarised.contains(call.getId())))
                .toList();
    }

    // ------------------------------------------------------------ one call --

    @Transactional(readOnly = true)
    public CallDtos.CallDetail detail(UUID callId) {
        CallRecord call = calls.findById(callId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, Lang.ERR_CALL_NOT_FOUND));

        List<CallMessage> lines = messages.findByCallIdOrderBySeqAsc(callId);
        CallDtos.SummaryView summary = summaries.findById(callId).map(CallHistoryService::toSummary)
                .orElse(null);

        return new CallDtos.CallDetail(
                toListItem(call, namesFor(List.of(call)), Map.of(callId, callerTurns(lines)),
                        summary != null),
                call.getTerminationReason(),
                lines.stream().map(CallHistoryService::toLine).toList(),
                transitions.findByCallIdOrderByAtAsc(callId).stream()
                        .map(CallHistoryService::toTransition).toList(),
                summary);
    }

    /**
     * The whole call as a text file, in the language it was held in.
     *
     * It is meant to be readable by someone with no access to this panel at
     * all — a colleague sent one call, an owner keeping a record — so every
     * heading is spelled out rather than being a column that only means
     * something on screen.
     */
    @Transactional(readOnly = true)
    public String exportText(UUID callId) {
        CallDtos.CallDetail detail = detail(callId);
        Map<String, String> t = Lang.ui(detail.call().language() == null
                ? "en" : detail.call().language().toLowerCase());

        StringBuilder text = new StringBuilder();
        text.append(t.get("export.title")).append('\n').append("=".repeat(40)).append("\n\n");
        appendHeader(text, detail, t);
        appendSummary(text, detail.summary(), t);

        text.append(t.get("export.transcript")).append('\n');
        for (CallDtos.TranscriptLine line : detail.lines()) {
            text.append(t.getOrDefault("livecall.role_" + line.role().toLowerCase(), line.role()))
                    .append(": ").append(line.text()).append('\n');
        }
        return text.toString();
    }

    private static void appendHeader(StringBuilder text, CallDtos.CallDetail detail,
                                     Map<String, String> t) {
        CallDtos.CallListItem call = detail.call();
        line(text, t.get("export.business"), call.business());
        line(text, t.get("export.caller"), call.caller() == null
                ? t.get("call.not_recognised") : call.caller());
        line(text, t.get("export.started"), call.startedAt());
        line(text, t.get("export.length"), call.durationSeconds() == null
                ? t.get("call.still_running") : call.durationSeconds() + "s");
        line(text, t.get("export.mode"), readableMode(call.mode(), t));
        line(text, t.get("export.turns"), String.valueOf(call.turns()));
        text.append('\n');
    }

    private static void appendSummary(StringBuilder text, CallDtos.SummaryView summary,
                                      Map<String, String> t) {
        if (summary == null) {
            text.append(t.get("export.summary")).append('\n')
                    .append(t.get("history.no_summary")).append("\n\n");
            return;
        }

        text.append(t.get("export.summary")).append('\n').append(summary.text()).append("\n\n");
        if (summary.actionItems().isEmpty()) return;

        text.append(t.get("export.actions")).append('\n');
        summary.actionItems().forEach(item -> text.append("- ").append(item).append('\n'));
        text.append('\n');
    }

    // ------------------------------------------------------------ internals --

    /** Business and customer names for a page of calls, in two queries. */
    private Names namesFor(List<CallRecord> page) {
        Map<UUID, String> businessNames = businesses.findAll().stream()
                .collect(Collectors.toMap(Business::getId, Business::getName));
        Map<UUID, String> clientNames = clients.findAllById(page.stream()
                        .map(CallRecord::getClientId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Client::getId, Client::getName));
        return new Names(businessNames, clientNames);
    }

    private record Names(Map<UUID, String> businesses, Map<UUID, String> clients) {
    }

    /**
     * A turn is one thing the caller said and the answer to it, so the caller's
     * own lines are what there are to count. Counting every line instead would
     * roughly double the number and make a two-minute call look like a long one.
     */
    private Map<UUID, Long> turnCounts() {
        return messages.findAll().stream()
                .filter(CallHistoryService::isCaller)
                .collect(Collectors.groupingBy(CallMessage::getCallId, Collectors.counting()));
    }

    private static long callerTurns(List<CallMessage> lines) {
        return lines.stream().filter(CallHistoryService::isCaller).count();
    }

    private static boolean isCaller(CallMessage message) {
        return message.getRole() == MessageRole.CALLER;
    }

    private static CallDtos.CallListItem toListItem(CallRecord call, Names names,
                                                    Map<UUID, Long> turnCounts, boolean summarised) {
        return new CallDtos.CallListItem(
                call.getId(),
                names.businesses().getOrDefault(call.getBusinessId(), ""),
                call.getStartedAt().toString(),
                call.getEndedAt() == null ? null
                        : Duration.between(call.getStartedAt(), call.getEndedAt()).toSeconds(),
                call.getFinalMode() == null ? null : call.getFinalMode().name(),
                call.getFinalLanguage() == null ? null : call.getFinalLanguage().name(),
                call.getTelephony().name(),
                turnCounts.getOrDefault(call.getId(), 0L),
                call.getClientId() == null ? null : names.clients().get(call.getClientId()),
                summarised);
    }

    private static CallDtos.TranscriptLine toLine(CallMessage message) {
        return new CallDtos.TranscriptLine(message.getSeq(), message.getRole().name(),
                message.getText(), message.getLanguage() == null ? null : message.getLanguage().name(),
                message.getModeAtTime() == null ? null : message.getModeAtTime().name(),
                replyMillis(message));
    }

    private static Long replyMillis(CallMessage message) {
        if (message.getTSttFinal() == null || message.getTTtsFirst() == null) return null;
        return Duration.between(message.getTSttFinal(), message.getTTtsFirst()).toMillis();
    }

    private static CallDtos.TransitionView toTransition(ModeTransition transition) {
        return new CallDtos.TransitionView(
                transition.getFromMode() == null ? null : transition.getFromMode().name(),
                transition.getToMode().name(), transition.getReason(),
                transition.getAt() == null ? Instant.now().toString() : transition.getAt().toString());
    }

    private static CallDtos.SummaryView toSummary(CallSummary summary) {
        return new CallDtos.SummaryView(summary.getSummaryText(),
                readFields(summary.getStructuredJson()), readList(summary.getActionItemsJson()),
                summary.getGeneratedAt().toString());
    }

    /** The model's structured fields, flattened to text so the panel can show them. */
    private static Map<String, String> readFields(String json) {
        Map<String, String> fields = new LinkedHashMap<>();
        JsonElement parsed = parse(json);
        if (parsed == null || !parsed.isJsonObject()) return fields;

        JsonObject object = parsed.getAsJsonObject();
        for (String key : object.keySet()) {
            JsonElement value = object.get(key);
            if (value != null && !value.isJsonNull()) {
                fields.put(key, value.isJsonPrimitive() ? value.getAsString() : value.toString());
            }
        }
        return fields;
    }

    private static List<String> readList(String json) {
        List<String> items = new ArrayList<>();
        JsonElement parsed = parse(json);
        if (parsed == null || !parsed.isJsonArray()) return items;

        parsed.getAsJsonArray().forEach(item -> {
            if (item.isJsonPrimitive()) items.add(item.getAsString());
        });
        return items;
    }

    private static JsonElement parse(String json) {
        try {
            return json == null || json.isBlank() ? null : JsonParser.parseString(json);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static String readableMode(String mode, Map<String, String> t) {
        return mode == null ? "" : t.getOrDefault("mode." + mode.toLowerCase(), mode);
    }

    private static void line(StringBuilder text, String label, String value) {
        text.append(label).append(": ").append(value == null ? "" : value).append('\n');
    }
}
