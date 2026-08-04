package com.ulab.agent.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * What the Dashboard reads. Grouped in one file for the same reason as the
 * other containers here: these records only mean anything together.
 */
public final class MetricsDtos {

    private MetricsDtos() {
    }

    /**
     * The state of the whole system on one screen.
     *
     * @param medianReplyMs median time from the caller finishing a sentence to
     *                      the first byte of the answer; null until a call has
     *                      produced at least one measurable turn
     */
    public record Summary(
            long businesses,
            long knowledgeEntries,
            long customers,
            long callsTotal,
            long callsToday,
            Long medianReplyMs,
            List<Capability> capabilities,
            List<RecentCall> recentCalls) {
    }

    /**
     * One part of the system and whether it is ready to be shown.
     *
     * @param id     a key the panel translates; never displayed raw
     * @param detail a short line naming what would make it ready
     */
    public record Capability(String id, String state, String detail) {
    }

    public record RecentCall(
            UUID id,
            String business,
            String startedAt,
            Long durationSeconds,
            String mode,
            String language,
            String telephony,
            long turns) {
    }
}
