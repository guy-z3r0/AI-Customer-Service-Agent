package com.ulab.agent.services;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.brain.llm.LlmRouter;
import com.ulab.agent.repo.AiSettingsRepository;
import com.ulab.agent.repo.CallRecordRepository;
import com.ulab.agent.repo.CallSummaryRepository;
import com.ulab.agent.repo.EscalationContactRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What happens to a write-up that is in flight when the app stops (BUG-010).
 *
 * Each finished call is summarised on its own thread, because the websocket
 * that carried it is still closing and nobody is waiting for the summary. That
 * left two things unsaid. The executor was shut down without ever being waited
 * for, so a summary being written when the app stopped was simply lost — and
 * if that call was the kind that ends in an email to a colleague, so was the
 * email. And nothing bounded it, so a burst of calls ending together started an
 * unbounded number of billed model requests at the same moment.
 */
class PostCallShutdownTest {

    /** A history that blocks, so a write-up can be caught in the middle of one. */
    private static CallHistoryService slowHistory(CountDownLatch entered,
                                                  CountDownLatch release,
                                                  AtomicInteger finished,
                                                  AtomicInteger inFlight,
                                                  AtomicInteger highWaterMark) {
        CallHistoryService history = mock(CallHistoryService.class);
        when(history.detail(any(UUID.class))).thenAnswer(call -> {
            highWaterMark.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            inFlight.decrementAndGet();
            finished.incrementAndGet();
            // No lines, so writeUp stops here — the point is that it got here
            // and got back out, not what it would have written.
            return new CallDtos.CallDetail(null, null, List.of(), List.of(), null);
        });
        return history;
    }

    private static PostCallService serviceOver(CallHistoryService history) {
        return new PostCallService(history, mock(CallRecordRepository.class),
                mock(CallSummaryRepository.class), mock(AiSettingsRepository.class),
                mock(EscalationContactRepository.class), mock(LlmRouter.class),
                mock(MailService.class));
    }

    @Test
    void aWriteUpInFlightIsWaitedForRatherThanDropped() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger finished = new AtomicInteger();
        PostCallService service = serviceOver(slowHistory(entered, release, finished,
                new AtomicInteger(), new AtomicInteger()));

        service.onCallEnded(UUID.randomUUID());
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the write-up should have started");

        // Shutdown begins while it is still going. Let it through a moment
        // later, the way a real one finishes shortly after the signal.
        Thread stopping = new Thread(service::stopWorkers);
        stopping.start();
        release.countDown();
        stopping.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(1, finished.get(),
                "shutdown() alone returns at once and the summary is lost with the process");
    }

    @Test
    void aBurstOfCallsEndingTogetherDoesNotOpenAModelRequestForEachOne()
            throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger highWaterMark = new AtomicInteger();
        PostCallService service = serviceOver(slowHistory(entered, release,
                new AtomicInteger(), inFlight, highWaterMark));

        for (int i = 0; i < 20; i++) service.onCallEnded(UUID.randomUUID());

        assertTrue(entered.await(5, TimeUnit.SECONDS), "the first four should be running");
        assertTrue(highWaterMark.get() <= 4,
                "at most four write-ups at once, not twenty — was " + highWaterMark.get());

        release.countDown();
        service.stopWorkers();
    }
}
