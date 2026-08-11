package com.ulab.agent.utils;

import com.ulab.agent.domain.CallMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How long a caller waited, and what a set of those waits says as a whole.
 *
 * This moved out of the dashboard when the report started asking the same
 * question of a different set of calls. Two answers to one question is the
 * failure this guards against: a median that came out differently on the
 * dashboard and in a report of the same calls would leave nobody able to say
 * which was the truth.
 */
class ReplyTimesTest {

    private static final Instant ASKED = Instant.parse("2026-08-11T04:00:00Z");

    // --------------------------------------------------------- one message --

    @Test
    void aTimedAnswerIsTheGapBetweenTheTwoStamps() {
        assertEquals(1400L, ReplyTimes.forMessage(answeredAfter(1400)));
    }

    @Test
    void aLineThatWasNotTimedEndToEndHasNoWait() {
        // A greeting, a system note and the caller's own words all land here:
        // there was no question to answer, so there is nothing to measure.
        CallMessage untimed = new CallMessage();
        assertNull(ReplyTimes.forMessage(untimed));

        CallMessage halfTimed = new CallMessage();
        halfTimed.setTSttFinal(ASKED);
        assertNull(ReplyTimes.forMessage(halfTimed), "a question with no answer stamped");
    }

    @Test
    void aReplyThatAppearsToPredateItsQuestionIsDropped() {
        // Two processes take these two stamps. A negative reading is two clocks
        // disagreeing, not an answer that arrived early.
        List<Long> waits = ReplyTimes.sorted(List.of(answeredAfter(-40), answeredAfter(900)));

        assertEquals(List.of(900L), waits);
    }

    @Test
    void theWaitsComeBackShortestFirst() {
        List<Long> waits = ReplyTimes.sorted(
                List.of(answeredAfter(2200), answeredAfter(400), answeredAfter(1300)));

        assertEquals(List.of(400L, 1300L, 2200L), waits);
    }

    // -------------------------------------------------------- percentiles --

    @Test
    void nothingMeasuredIsNotTheSameAsZero() {
        assertNull(ReplyTimes.percentile(List.of(), 50),
                "a dashboard with no calls yet must not claim a nought-millisecond reply");
    }

    @Test
    void oneTurnIsBothTheUsualCaseAndTheWorstOne() {
        assertEquals(1200L, ReplyTimes.percentile(List.of(1200L), 50));
        assertEquals(1200L, ReplyTimes.percentile(List.of(1200L), 90));
    }

    @Test
    void theMedianIsTheMiddleOfAnOddNumberOfTurns() {
        assertEquals(900L, ReplyTimes.percentile(List.of(400L, 900L, 3000L), 50));
    }

    @Test
    void theSlowestTenthIsTheUnluckyCallerNotTheSlowestOne() {
        List<Long> tenTurns = List.of(300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L,
                1100L, 18000L);

        assertEquals(1100L, ReplyTimes.percentile(tenTurns, 90),
                "the ninth of ten, so one stalled turn cannot become the headline");
        assertTrue(ReplyTimes.percentile(tenTurns, 50) < 1000L,
                "and the median is unmoved by it, which is why it is not a mean");
    }

    // ------------------------------------------------------------ fixtures --

    private static CallMessage answeredAfter(long milliseconds) {
        CallMessage message = new CallMessage();
        message.setTSttFinal(ASKED);
        message.setTTtsFirst(ASKED.plusMillis(milliseconds));
        return message;
    }
}
