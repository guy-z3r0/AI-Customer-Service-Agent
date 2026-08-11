package com.ulab.agent.services;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.api.dto.ReportDtos;
import com.ulab.agent.domain.Business;
import com.ulab.agent.domain.CallMessage;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.repo.BusinessRepository;
import com.ulab.agent.repo.CallMessageRepository;
import com.ulab.agent.repo.CallSummaryRepository;
import com.ulab.agent.utils.Lang;
import com.ulab.agent.utils.ReplyTimes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Many calls read at once: what has been happening, over a stretch of days.
 *
 * Everything here is counted from the rows the history page already shows,
 * rather than from a second set of queries of its own. That is deliberate: the
 * report is printed and handed to somebody who cannot click into the panel to
 * check it, so the one thing it must never do is disagree with the screen it
 * came from.
 *
 * Nothing in this class writes anything, and nothing is stored. A report is
 * worked out when it is asked for and exists only in the answer.
 */
@Service
public class CallReportService {

    /** How far back a report reaches when nobody says. */
    public static final int DEFAULT_DAYS = 30;

    /**
     * The fifth outcome, which is not a screening mode.
     *
     * A call nobody spoke on kept whatever mode it opened in, and reporting
     * that as a conversation with a new customer would be a conversation that
     * never happened. The panel draws the same distinction in
     * static/js/call_outcome.js and the two must stay in step.
     */
    public static final String NO_ANSWER = "NO_ANSWER";

    private final CallHistoryService history;
    private final CallMessageRepository messages;
    private final CallSummaryRepository summaries;
    private final BusinessRepository businesses;

    public CallReportService(CallHistoryService history, CallMessageRepository messages,
                             CallSummaryRepository summaries, BusinessRepository businesses) {
        this.history = history;
        this.messages = messages;
        this.summaries = summaries;
        this.businesses = businesses;
    }

    /**
     * @param businessId only this business's calls, or null for every business
     * @param from       the first day counted
     * @param to         the last day counted, included in full
     */
    @Transactional(readOnly = true)
    public ReportDtos.CallReport report(UUID businessId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, Lang.ERR_REPORT_RANGE);
        }

        ZoneId zone = zoneFor(businessId);
        List<CallDtos.CallListItem> calls = history.between(businessId,
                from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant());

        return new ReportDtos.CallReport(
                businessId == null ? null : businessName(businessId),
                from.toString(), to.toString(), Instant.now().toString(),
                totals(calls, replyTimesFor(calls)),
                outcomes(calls), languages(calls), byDay(calls, zone),
                followUps(calls), calls);
    }

    // -------------------------------------------------------------- counting --

    static ReportDtos.Totals totals(List<CallDtos.CallListItem> calls, List<Long> replies) {
        long answered = calls.stream().filter(CallReportService::wasAnswered).count();

        return new ReportDtos.Totals(
                calls.size(),
                answered,
                calls.size() - answered,
                calls.stream().filter(call -> CallMode.COMPLEX_REQUEST.name().equals(outcomeOf(call)))
                        .count(),
                calls.stream().filter(CallDtos.CallListItem::summarised).count(),
                calls.stream().map(CallDtos.CallListItem::caller).filter(Objects::nonNull)
                        .distinct().count(),
                calls.stream().map(CallDtos.CallListItem::durationSeconds).filter(Objects::nonNull)
                        .mapToLong(Long::longValue).sum(),
                ReplyTimes.percentile(replies, 50),
                ReplyTimes.percentile(replies, 90));
    }

    /**
     * The five kinds of call, always all five.
     *
     * A kind with no calls in it is still reported, as a zero. "No wrong
     * numbers this month" is a thing worth knowing, and a row that vanishes
     * when it reaches zero cannot say it.
     */
    static List<ReportDtos.Count> outcomes(List<CallDtos.CallListItem> calls) {
        Map<String, Long> counted = calls.stream()
                .collect(Collectors.groupingBy(CallReportService::outcomeOf, Collectors.counting()));

        List<String> kinds = new ArrayList<>(Arrays.stream(CallMode.values()).map(Enum::name).toList());
        kinds.add(NO_ANSWER);
        return kinds.stream()
                .map(kind -> new ReportDtos.Count(kind, counted.getOrDefault(kind, 0L)))
                .toList();
    }

    /** Both languages, always, so that the split reads as a split. */
    static List<ReportDtos.Count> languages(List<CallDtos.CallListItem> calls) {
        Map<String, Long> counted = calls.stream().collect(Collectors.groupingBy(
                call -> Language.of(call.language()).name(), Collectors.counting()));

        return Arrays.stream(Language.values())
                .map(language -> new ReportDtos.Count(language.name(),
                        counted.getOrDefault(language.name(), 0L)))
                .toList();
    }

    /**
     * How many calls on each day, oldest first.
     *
     * Only days that had a call appear. A quiet fortnight inside a range would
     * otherwise be fourteen rows of nothing, and a range of a year would be a
     * report nobody could read.
     */
    static List<ReportDtos.Count> byDay(List<CallDtos.CallListItem> calls, ZoneId zone) {
        Map<String, Long> counted = calls.stream().collect(Collectors.groupingBy(
                call -> Instant.parse(call.startedAt()).atZone(zone).toLocalDate().toString(),
                TreeMap::new, Collectors.counting()));

        return counted.entrySet().stream()
                .map(day -> new ReportDtos.Count(day.getKey(), day.getValue()))
                .toList();
    }

    /** True when the caller said something the agent heard. */
    private static boolean wasAnswered(CallDtos.CallListItem call) {
        return call.turns() > 0;
    }

    private static String outcomeOf(CallDtos.CallListItem call) {
        if (!wasAnswered(call)) return NO_ANSWER;
        // A call that was never reclassified is what it opened as, which for an
        // unrecognised caller is a new one.
        return call.mode() == null ? CallMode.NEW_CUSTOMER.name() : call.mode();
    }

    // -------------------------------------------------------------- gathering --

    /**
     * Which clock decides where one day ends and the next begins.
     *
     * A business keeps its own timezone and its working day is the one that
     * matters, not the server's — a container running in UTC would otherwise
     * file a Dhaka morning under the day before. With every business in scope
     * there is no single answer, so the active business's clock stands in: it
     * is the one the operator is working to.
     */
    private ZoneId zoneFor(UUID businessId) {
        String named = (businessId == null
                ? businesses.findFirstByActiveTrue()
                : businesses.findById(businessId))
                .map(Business::getTimezone).orElse(null);
        try {
            return named == null || named.isBlank() ? ZoneId.systemDefault() : ZoneId.of(named);
        } catch (DateTimeException notAZone) {
            return ZoneId.systemDefault();
        }
    }

    private String businessName(UUID businessId) {
        return businesses.findById(businessId).map(Business::getName).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, Lang.ERR_BUSINESS_NOT_FOUND));
    }

    private List<Long> replyTimesFor(List<CallDtos.CallListItem> calls) {
        Set<UUID> ids = calls.stream().map(CallDtos.CallListItem::id).collect(Collectors.toSet());
        if (ids.isEmpty()) return List.of();

        List<CallMessage> mine = messages.findAll().stream()
                .filter(message -> ids.contains(message.getCallId()))
                .toList();
        return ReplyTimes.sorted(mine);
    }

    /**
     * Everything the calls in this range left for somebody to do, newest call
     * first.
     *
     * This is the part of a report that gets acted on rather than read, which
     * is why each item carries the call it came from — an action with no way
     * back to the conversation that produced it is an instruction without a
     * reason.
     */
    private List<ReportDtos.FollowUp> followUps(List<CallDtos.CallListItem> calls) {
        Map<UUID, CallDtos.CallListItem> summarised = calls.stream()
                .filter(CallDtos.CallListItem::summarised)
                .collect(Collectors.toMap(CallDtos.CallListItem::id, call -> call));
        if (summarised.isEmpty()) return List.of();

        List<ReportDtos.FollowUp> items = new ArrayList<>();
        summaries.findAllById(summarised.keySet()).forEach(summary -> {
            CallDtos.CallListItem call = summarised.get(summary.getCallId());
            CallHistoryService.readList(summary.getActionItemsJson()).forEach(item ->
                    items.add(new ReportDtos.FollowUp(call.id(), call.business(),
                            call.startedAt(), item)));
        });

        items.sort(Comparator.comparing(ReportDtos.FollowUp::startedAt).reversed());
        return items;
    }
}
