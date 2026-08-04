package com.ulab.agent.api.dto;

import java.util.List;

/**
 * What the Dashboard reads. Grouped in one file for the same reason as the
 * other containers here: these records only mean anything together.
 *
 * A call in the recent list is the same shape as a call on the history page —
 * {@link CallDtos.CallListItem} — because the two tables show the same facts
 * and a second shape for them would only be a second thing to keep in step.
 */
public final class MetricsDtos {

    private MetricsDtos() {
    }

    /**
     * The state of the whole system on one screen.
     *
     * @param medianReplyMs      median time from the caller finishing a sentence
     *                           to the first byte of the answer; null until a
     *                           call has produced at least one measurable turn
     * @param slowestTenthReplyMs the 90th percentile of the same measurement —
     *                           what the unlucky one call in ten waited
     */
    public record Summary(
            long businesses,
            long knowledgeEntries,
            long customers,
            long callsTotal,
            long callsToday,
            Long medianReplyMs,
            Long slowestTenthReplyMs,
            List<Capability> capabilities,
            List<ModeCount> modes,
            List<CallDtos.CallListItem> recentCalls) {
    }

    /** How many calls ended up in one of the four screening modes. */
    public record ModeCount(String mode, long calls) {
    }

    /**
     * One part of the system and whether it is ready to be shown.
     *
     * @param id     a key the panel translates; never displayed raw
     * @param state  "ready", "degraded" or "off"
     * @param detail a short line naming what would make it ready
     */
    public record Capability(String id, String state, String detail) {
    }

}
