package com.ulab.agent.utils;

import com.ulab.agent.domain.CallMessage;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * How long a caller waited for an answer, and what a set of those waits says
 * taken together.
 *
 * Three screens ask this same question of different sets of lines: the
 * dashboard over every call there has ever been, a report over a chosen range,
 * and one call's transcript over its own turns. A median that came out
 * differently on the dashboard and in a report covering the same calls would
 * not be two opinions, it would be a bug — so the arithmetic is written once
 * and all three read it from here.
 */
public final class ReplyTimes {

    private ReplyTimes() {
        // Static helpers only; nothing to construct.
    }

    /**
     * The wait this line ended: from the caller finishing their sentence to the
     * first byte of the reply leaving for them.
     *
     * Null when the turn was not timed end to end, which is every line that is
     * not an answer — a greeting, a system note, the caller's own words.
     */
    public static Long forMessage(CallMessage message) {
        if (message.getTSttFinal() == null || message.getTTtsFirst() == null) return null;
        return Duration.between(message.getTSttFinal(), message.getTTtsFirst()).toMillis();
    }

    /**
     * Every measurable wait in a set of lines, shortest first, ready to be cut
     * at a rank.
     *
     * A negative reading is dropped rather than kept: the two stamps are taken
     * by two different processes, and a reply that appears to have left before
     * the question finished is a clock disagreeing with itself, not a fast
     * answer.
     */
    public static List<Long> sorted(Collection<CallMessage> messages) {
        return messages.stream()
                .map(ReplyTimes::forMessage)
                .filter(milliseconds -> milliseconds != null && milliseconds >= 0)
                .sorted()
                .toList();
    }

    /**
     * Percentiles rather than an average, because one call that stalled on a
     * cold connection should not be allowed to make a hundred fast ones look
     * slow. The median says what a caller usually waits; the 90th says what the
     * unlucky one in ten waited, which is the number that decides whether the
     * system is really under two seconds or only mostly.
     *
     * @param sorted waits already in ascending order, as {@link #sorted} leaves them
     * @return null when there is nothing to take a percentile of
     */
    public static Long percentile(List<Long> sorted, int percent) {
        if (sorted.isEmpty()) return null;

        int rank = (int) Math.ceil(sorted.size() * percent / 100.0) - 1;
        return sorted.get(Math.clamp(rank, 0, sorted.size() - 1));
    }
}
