package com.ulab.agent.brain;

import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.services.ConfigService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is allowed to be interrupted, and who is not.
 *
 * All three of these were reported from real calls. The agent asked whether
 * anybody was there while it was still reading its own greeting, and again over
 * a caller who was halfway through answering it — which on the free recogniser
 * is invisible, because it says nothing at all until a sentence is finished.
 */
class InactivityWatchdogTest {

    private static final int WARN_AFTER_S = 0;
    private static final int HANG_UP_AFTER_S = 999;

    @Test
    void aCallerWhoHasSaidNothingForLongEnoughIsAsked() {
        Watched watched = new Watched();

        watched.sweep();

        assertEquals(1, watched.outbox.ofType("say").size());
        assertTrue(String.valueOf(watched.outbox.ofType("say").get(0).get("text"))
                .contains("still there"));
    }

    @Test
    void nothingIsSaidOverTheAgentsOwnVoice() {
        Watched watched = new Watched();
        watched.session.send("say", "seq", 0, "text", "a long greeting", "last", true);

        watched.sweep();

        assertEquals(1, watched.outbox.ofType("say").size(), "only the greeting itself");
    }

    @Test
    void theFloorIsGivenBackOnceTheAudioHasActuallyPlayed() {
        Watched watched = new Watched();
        watched.session.send("say", "seq", 0, "text", "a long greeting", "last", true);
        watched.session.agentFinishedSpeaking();

        watched.sweep();

        assertEquals(2, watched.outbox.ofType("say").size());
    }

    @Test
    void aCallerInTheMiddleOfASentenceIsNotTalkedOver() {
        Watched watched = new Watched();
        watched.session.callerStartedSpeaking();

        watched.sweep();

        assertTrue(watched.outbox.ofType("say").isEmpty());
        assertFalse(watched.session.isEnding());
    }

    @Test
    void aCallerWhoHasFinishedTheirSentenceCanBeAskedAgain() {
        Watched watched = new Watched();
        watched.session.callerStartedSpeaking();
        watched.session.callerStoppedSpeaking();

        watched.sweep();

        assertEquals(1, watched.outbox.ofType("say").size());
    }

    /** One call being watched, with the two waits set so a sweep decides now. */
    private static final class Watched {

        private final TestCalls.Outbox outbox = new TestCalls.Outbox();
        private final TestCalls.Recorder log = new TestCalls.Recorder();
        private final CallRegistry registry = new CallRegistry();
        private final CallSession session;
        private final InactivityWatchdog watchdog;

        private Watched() {
            session = TestCalls.session(CallMode.NEW_CUSTOMER, Language.EN, outbox);
            registry.add(session);
            watchdog = new InactivityWatchdog(registry, new CallInterventions(log), timings());
        }

        private void sweep() {
            watchdog.sweep();
        }

        private static ConfigService timings() {
            return new ConfigService(null) {
                @Override
                public int getInt(String key, int fallback) {
                    return "inactivity_warn_s".equals(key) ? WARN_AFTER_S : HANG_UP_AFTER_S;
                }
            };
        }
    }
}
