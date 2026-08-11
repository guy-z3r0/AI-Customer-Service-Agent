package com.ulab.agent.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * A report over many calls rather than one.
 *
 * The history page answers "what happened on that call". This answers "what has
 * been happening", which is a different question and wants different shapes: a
 * handful of totals, three breakdowns, the work the calls left behind, and then
 * the calls themselves so that every number above can be checked against the
 * rows it was counted from.
 *
 * A call appears here as {@link CallDtos.CallListItem} — the same row the
 * history table shows — because a report whose rows disagreed with the page it
 * was made from would be worse than no report.
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * @param business    the business this covers, or null for every business
     * @param from        the first day counted, as a plain date
     * @param to          the last day counted, included in full
     * @param generatedAt when this was worked out, so a printed copy says how
     *                    old it is
     */
    public record CallReport(
            String business,
            String from,
            String to,
            String generatedAt,
            Totals totals,
            List<Count> outcomes,
            List<Count> languages,
            List<Count> byDay,
            List<FollowUp> followUps,
            List<CallDtos.CallListItem> calls) {
    }

    /**
     * The numbers at the top of the report.
     *
     * @param answered            calls where the caller said something the agent heard
     * @param noAnswer            calls where nobody ever did
     * @param escalated           calls that ended up wanting a person
     * @param callersRecognised   how many different customers were matched to a record
     * @param talkSeconds         every call's length added together
     * @param medianReplyMs       what a caller usually waited for an answer;
     *                            null when no turn in the range was fully timed
     * @param slowestTenthReplyMs what the unlucky one call in ten waited
     */
    public record Totals(long calls, long answered, long noAnswer, long escalated,
                         long summarised, long callersRecognised, long talkSeconds,
                         Long medianReplyMs, Long slowestTenthReplyMs) {
    }

    /**
     * One bucket of a breakdown and how many calls fell in it.
     *
     * @param key a screening mode, a language or a date — the panel decides how
     *            to read it from which list it came out of, so that no English
     *            or Bangla is written here
     */
    public record Count(String key, long calls) {
    }

    /** One thing a call left for somebody to do, and the call it came from. */
    public record FollowUp(UUID callId, String business, String startedAt, String item) {
    }
}
