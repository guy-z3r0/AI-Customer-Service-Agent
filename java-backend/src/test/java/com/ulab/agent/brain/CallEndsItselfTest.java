package com.ulab.agent.brain;

import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ways a call has to be able to end without anybody deciding to end it.
 *
 * Both were reported from real use. A line producing nothing but noise asked
 * "could you say that once more" for ever — the counter behind it was reset
 * every time it asked, and asking is the agent speaking, which also restarted
 * the silence clock that would otherwise have hung up. And a caller could swear
 * at the agent indefinitely, because nothing was reading what they said.
 */
class CallEndsItselfTest {

    @Test
    void twoUtterancesWithNoWordsInThemGetOneReprompt() {
        Call call = new Call();

        call.nothingHeard();
        assertTrue(call.spoken().isEmpty(), "one is a cough");

        call.nothingHeard();
        assertEquals(1, call.spoken().size());
        assertTrue(call.spoken().get(0).contains("could not make that out"));
    }

    @Test
    void aLineThatOnlyEverProducesNoiseIsHungUpOnRatherThanAskedForEver() {
        Call call = new Call();

        for (int utterance = 1; utterance <= 6; utterance++) {
            call.nothingHeard();
        }

        assertEquals(2, call.spoken().size(), "it asks twice, and then it stops asking");
        assertTrue(call.session.isEnding());
        List<Map<String, Object>> hangups = call.outbox.ofType("hangup");
        assertEquals(1, hangups.size());
        assertEquals("nothing_heard", hangups.get(0).get("reason"));
        assertFalse(String.valueOf(hangups.get(0).get("farewellText")).isBlank(),
                "nobody is hung up on in silence");
    }

    @Test
    void aRealSentenceClearsTheCountSoOneBadPatchDoesNotEndTheCall() {
        Call call = new Call();
        call.nothingHeard();
        call.nothingHeard();
        call.nothingHeard();

        call.said("I would like to book an appointment please");
        for (int utterance = 1; utterance <= 5; utterance++) {
            call.nothingHeard();
        }

        assertFalse(call.session.isEnding(), "the count started again from the sentence");
    }

    @Test
    void swearingIsWarnedAboutOnceAndThenTheCallEnds() {
        Call call = new Call();

        call.said("this is complete bullshit");
        assertEquals(1, call.spoken().size());
        assertTrue(call.spoken().get(0).contains("civil"));
        assertFalse(call.session.isEnding(), "the first one is a warning");

        call.said("you are a bastard");
        assertTrue(call.session.isEnding());
        assertEquals("abusive_language", call.outbox.ofType("hangup").get(0).get("reason"));
    }

    @Test
    void whatTheCallerSaidIsKeptEvenThoughTheModelIsNotGivenIt() {
        Call call = new Call();

        call.said("this is complete bullshit");

        assertTrue(call.log.lines.stream().anyMatch(line ->
                        "caller".equals(line.role()) && line.text().contains("bullshit")),
                "an operator reading the call back has to see why it went this way");
        assertTrue(call.session.messages().stream()
                        .noneMatch(message -> message.text().contains("bullshit")),
                "and the model has nothing useful to do with it");
    }

    // ------------------------------------------- the goodbye it forgot to act on --

    @Test
    void anAgentThatSaysGoodbyeHangsUpEvenWhenItForgotToAskTo() {
        // From a real call: the agent said its farewell, did not call end_call,
        // and stayed on a line the caller thought was over — who then had to ask
        // "are you still there?" twice before it would put the phone down.
        Call call = new Call();

        call.answered("Thank you for contacting Bengal Power System, and have a good day.");

        assertTrue(call.session.isEnding());
        List<Map<String, Object>> hangups = call.outbox.ofType("hangup");
        assertEquals(1, hangups.size());
        assertEquals("agent_said_goodbye", hangups.get(0).get("reason"));
        assertEquals("", hangups.get(0).get("farewellText"),
                "the goodbye has just been spoken; saying Lang's as well says it twice");
    }

    @Test
    void anOrdinaryReplyLeavesTheCallWhereItIs() {
        Call call = new Call();

        call.answered("Same-day delivery inside Dhaka is 80 taka. Shall I book one for you?");

        assertFalse(call.session.isEnding());
        assertTrue(call.outbox.ofType("hangup").isEmpty());
    }

    @Test
    void anAgentAlreadyHangingUpDoesNotEndTheCallTwice() {
        Call call = new Call();
        call.session.requestHangup("caller_said_goodbye", "Thank you for calling. Goodbye.");

        call.answered("Thank you for calling. Goodbye.");

        assertEquals(1, call.outbox.ofType("hangup").size());
        assertEquals("caller_said_goodbye",
                call.outbox.ofType("hangup").get(0).get("reason"),
                "the reason the call was already ending for is the one that stands");
    }

    /** One call, its brain, and everything either of them said. */
    private static final class Call {

        private final TestCalls.Outbox outbox = new TestCalls.Outbox();
        private final TestCalls.Recorder log = new TestCalls.Recorder();
        private final CallRegistry registry = new CallRegistry();
        private final CallSession session;
        private final ConversationBrain brain;

        private Call() {
            session = TestCalls.session(CallMode.NEW_CUSTOMER, Language.EN, outbox);
            registry.add(session);
            brain = TestCalls.brainWithoutAModel(registry, log);
        }

        private UUID id() {
            return session.callId();
        }

        /** An utterance the recogniser made no words out of. */
        private void nothingHeard() {
            brain.onTranscriptFinal(id(), "", 0L);
        }

        /**
         * A sentence the caller said. Only lines the brain answers on its own
         * reach here — anything needing a model is a different test.
         */
        private void said(String text) {
            brain.onTranscriptFinal(id(), text, 0L);
        }

        /**
         * A turn the model has finished writing, handed to the brain the way a
         * real one arrives. No model is involved: what is being tested is what
         * the brain does with the words, whoever wrote them.
         */
        private void answered(String reply) {
            brain.finishTurn(session, 1, 0L, reply, reply, null);
        }

        private List<String> spoken() {
            return outbox.ofType("say").stream()
                    .map(message -> String.valueOf(message.get("text")))
                    .toList();
        }
    }
}
