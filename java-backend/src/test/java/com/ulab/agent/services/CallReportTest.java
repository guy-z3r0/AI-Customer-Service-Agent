package com.ulab.agent.services;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.api.dto.ReportDtos;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What a report says about a set of calls.
 *
 * A report is printed and handed to somebody who cannot click into the panel to
 * check it, so every number here has to mean what its label says. The cases
 * that matter most are the ones where a call is not what it appears to be on
 * its own row: a call nobody spoke on still holds the screening mode it opened
 * in, and counting that as a conversation would put a conversation that never
 * happened into a document somebody acts on.
 */
class CallReportTest {

    private static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");

    // ---------------------------------------------------------- the totals --

    @Test
    void aCallNobodySpokeOnIsNotCountedAsAConversation() {
        List<CallDtos.CallListItem> calls = List.of(
                call("2026-08-01T04:00:00Z", "NEW_CUSTOMER", 6, null, 120L, false),
                call("2026-08-01T05:00:00Z", "NEW_CUSTOMER", 0, null, 4L, false));

        ReportDtos.Totals totals = CallReportService.totals(calls, List.of());

        assertEquals(2, totals.calls());
        assertEquals(1, totals.answered());
        assertEquals(1, totals.noAnswer());
        assertEquals(1L, countOf(CallReportService.outcomes(calls), CallReportService.NO_ANSWER),
                "the silent call belongs in its own bucket, not under new customers");
        assertEquals(1L, countOf(CallReportService.outcomes(calls), "NEW_CUSTOMER"));
    }

    @Test
    void escalatedMeansAPersonWasFetched() {
        List<CallDtos.CallListItem> calls = List.of(
                call("2026-08-01T04:00:00Z", "COMPLEX_REQUEST", 9, null, 300L, true),
                // Dialled, rang out, and left holding the mode it opened in.
                // Nobody was fetched, because nobody asked for anything.
                call("2026-08-01T05:00:00Z", "COMPLEX_REQUEST", 0, null, 3L, false));

        assertEquals(1, CallReportService.totals(calls, List.of()).escalated());
    }

    @Test
    void thesamePersonCallingTwiceIsOneCustomer() {
        List<CallDtos.CallListItem> calls = List.of(
                call("2026-08-01T04:00:00Z", "EXISTING_CUSTOMER", 4, "Rahim Uddin", 100L, true),
                call("2026-08-02T04:00:00Z", "EXISTING_CUSTOMER", 3, "Rahim Uddin", 80L, true),
                call("2026-08-03T04:00:00Z", "NEW_CUSTOMER", 5, null, 90L, false));

        ReportDtos.Totals totals = CallReportService.totals(calls, List.of());

        assertEquals(1, totals.callersRecognised(), "one customer, reached twice");
        assertEquals(270, totals.talkSeconds());
        assertEquals(2, totals.summarised());
    }

    @Test
    void aRangeWithNothingInItCountsNothingRatherThanFailing() {
        ReportDtos.Totals totals = CallReportService.totals(List.of(), List.of());

        assertEquals(0, totals.calls());
        assertEquals(0, totals.talkSeconds());
        assertNull(totals.medianReplyMs(), "no turn was timed, which is not the same as zero");
        assertNull(totals.slowestTenthReplyMs());
    }

    @Test
    void aCallStillRunningAddsNoLengthToTheTotal() {
        List<CallDtos.CallListItem> calls = List.of(
                call("2026-08-01T04:00:00Z", "NEW_CUSTOMER", 3, null, 60L, false),
                call("2026-08-01T04:30:00Z", "NEW_CUSTOMER", 1, null, null, false));

        assertEquals(60, CallReportService.totals(calls, List.of()).talkSeconds());
    }

    // ------------------------------------------------------ the breakdowns --

    @Test
    void everyKindIsReportedEvenWhenItHasNoCalls() {
        // "No wrong numbers this month" is worth knowing, and a row that
        // vanishes at zero cannot say it.
        List<ReportDtos.Count> outcomes = CallReportService.outcomes(
                List.of(call("2026-08-01T04:00:00Z", "NEW_CUSTOMER", 2, null, 30L, false)));

        assertEquals(5, outcomes.size());
        assertEquals(0L, countOf(outcomes, "WRONG_NUMBER"));
        assertEquals(0L, countOf(outcomes, CallReportService.NO_ANSWER));
    }

    @Test
    void aCallThatWasNeverReclassifiedIsWhatItOpenedAs() {
        List<ReportDtos.Count> outcomes = CallReportService.outcomes(
                List.of(call("2026-08-01T04:00:00Z", null, 3, null, 40L, false)));

        assertEquals(1L, countOf(outcomes, "NEW_CUSTOMER"));
    }

    @Test
    void bothLanguagesAreReportedSoTheSplitReadsAsASplit() {
        List<ReportDtos.Count> languages = CallReportService.languages(List.of(
                callInLanguage("2026-08-01T04:00:00Z", "BN"),
                callInLanguage("2026-08-01T05:00:00Z", "BN")));

        assertEquals(2, languages.size());
        assertEquals(2L, countOf(languages, "BN"));
        assertEquals(0L, countOf(languages, "EN"));
    }

    @Test
    void aCallWithNoLanguageOnItsRowIsCountedAsEnglish() {
        List<ReportDtos.Count> languages = CallReportService.languages(
                List.of(callInLanguage("2026-08-01T04:00:00Z", null)));

        assertEquals(1L, countOf(languages, "EN"));
    }

    // ------------------------------------------------------------ the days --

    @Test
    void theBusinessClockDecidesWhichDayACallBelongsTo() {
        // Half past eight in the evening in London is half past two the next
        // morning in Dhaka. A report for a Bangladeshi business that filed this
        // call under the day before would be a report about the wrong day.
        List<ReportDtos.Count> days = CallReportService.byDay(
                List.of(call("2026-08-11T20:30:00Z", "NEW_CUSTOMER", 2, null, 30L, false)), DHAKA);

        assertEquals(1, days.size());
        assertEquals("2026-08-12", days.get(0).key());
    }

    @Test
    void quietDaysAreNotRows() {
        List<ReportDtos.Count> days = CallReportService.byDay(List.of(
                call("2026-08-01T04:00:00Z", "NEW_CUSTOMER", 2, null, 30L, false),
                call("2026-08-04T04:00:00Z", "NEW_CUSTOMER", 2, null, 30L, false),
                call("2026-08-04T09:00:00Z", "NEW_CUSTOMER", 2, null, 30L, false)), DHAKA);

        assertEquals(2, days.size(), "a quiet fortnight would otherwise be fourteen empty rows");
        assertEquals("2026-08-01", days.get(0).key(), "oldest first, the way a month is read");
        assertEquals(1L, days.get(0).calls());
        assertEquals("2026-08-04", days.get(1).key());
        assertEquals(2L, days.get(1).calls());
    }

    // ------------------------------------------------------------ fixtures --

    private static long countOf(List<ReportDtos.Count> counts, String key) {
        Map<String, Long> byKey = counts.stream()
                .collect(Collectors.toMap(ReportDtos.Count::key, ReportDtos.Count::calls));
        return byKey.getOrDefault(key, -1L);
    }

    private static CallDtos.CallListItem call(String startedAt, String mode, long turns,
                                              String caller, Long seconds, boolean summarised) {
        return new CallDtos.CallListItem(UUID.randomUUID(), "Bengal Power System", startedAt,
                seconds, mode, "EN", "browser", turns, caller, summarised);
    }

    private static CallDtos.CallListItem callInLanguage(String startedAt, String language) {
        return new CallDtos.CallListItem(UUID.randomUUID(), "Bengal Power System", startedAt,
                60L, "NEW_CUSTOMER", language, "browser", 3, null, false);
    }
}
