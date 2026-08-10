package com.ulab.agent.brain;

import com.ulab.agent.api.dto.CallDtos;
import com.ulab.agent.services.CallLogService;
import com.ulab.agent.utils.Lang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Everything the agent says that no caller asked for, and the one way a call
 * hangs up.
 *
 * A turn is a caller's sentence answered by a model. None of this is: asking
 * whether anybody is still there, asking somebody to repeat themselves, telling
 * a caller to mind their language, and putting the phone down. They share a
 * shape — a line from {@link Lang} rather than from a model, carrying turn 0 so
 * the panel does not count it as an exchange — and they share the rule that
 * matters most, which is that nothing is ever cut off mid-word.
 */
@Component
public class CallInterventions {

    private static final Logger log = LoggerFactory.getLogger(CallInterventions.class);

    /** Roles as the model layer names them, which are not the transcript's names. */
    private static final String AGENT = "assistant";

    /** Utterances the recogniser makes nothing of before the agent asks again. */
    private static final int UNHEARD_BEFORE_REPROMPT = 2;

    /**
     * And how many before it stops asking and hangs up.
     *
     * This used to have no ceiling, and the counter behind it was reset every
     * time the agent re-prompted — so a line producing nothing but noise asked
     * "could you say that once more" for ever. Worse, each re-prompt is the
     * agent speaking, which restarts the silence clock, so the one thing that
     * would have ended the call never fired either. Two asks, then goodbye.
     */
    private static final int UNHEARD_BEFORE_HANGUP = 6;

    /** Times a caller may swear before the call ends. The first is a warning. */
    private static final int SLANG_STRIKES_ALLOWED = 1;

    private final CallLogService callLog;

    public CallInterventions(CallLogService callLog) {
        this.callLog = callLog;
    }

    // --------------------------------------------------------------- silence --

    /** Asks whether anyone is still there, once, after the configured wait. */
    public void warnAboutSilence(CallSession session) {
        log.info("[{}] nobody has said anything; asking", session.callId());
        say(session, "voice.still_there");
    }

    public void hangUpForSilence(CallSession session) {
        log.info("[{}] still nothing; ending the call", session.callId());
        endCall(session, "inactivity", "voice.inactivity_farewell");
    }

    /**
     * Two utterances in a row that the recogniser made nothing of is a caller
     * worth asking to repeat themselves — most often someone switching between
     * Bangla and English mid-sentence while the recogniser listens for one.
     *
     * Six of them is not a caller at all. It is a room with a fan in it, or the
     * agent's own voice coming back down the microphone: something is reaching
     * the recogniser and none of it is speech. The call ends rather than asking
     * a seventh time, and it says why on the way out.
     */
    void nothingHeard(CallSession session) {
        int unheard = session.unheardInARow();
        if (unheard >= UNHEARD_BEFORE_HANGUP) {
            log.info("[{}] {} utterances in a row made no words; ending the call",
                    session.callId(), unheard);
            endCall(session, "nothing_heard", "voice.inactivity_farewell");
            return;
        }
        // The counter is deliberately not reset here. Asking again is the
        // response to the second, fourth and sixth of them; resetting it is
        // what used to make the sequence run for ever.
        if (unheard % UNHEARD_BEFORE_REPROMPT != 0) return;
        say(session, "voice.not_understood");
    }

    // -------------------------------------------------------------- civility --

    /**
     * A caller who swore at the agent is told once, and hung up on the second
     * time. The line is still written into the transcript — an operator reading
     * a call back has to be able to see why it ended — but it is kept out of
     * the model's history, because the model has nothing useful to do with it.
     */
    void answerAbuse(CallSession session, String text) {
        callLog.record(session.callId(), CallDtos.LineToStore.caller(text,
                session.language().code(), session.mode().name(), 0, null));

        int strikes = session.recordSlang();
        log.info("[{}] abusive language, strike {}", session.callId(), strikes);
        if (strikes <= SLANG_STRIKES_ALLOWED) {
            say(session, "voice.slang_warning");
            return;
        }
        endCall(session, "abusive_language", "voice.slang_farewell");
    }

    // ------------------------------------------------------------- speaking --

    /**
     * Anything the agent says that no caller asked for. It carries turn 0, so
     * the panel does not count it as an exchange and expects no reply time.
     */
    public void say(CallSession session, String stringKey) {
        String text = spokenLine(session, stringKey);
        if (text == null || text.isBlank()) return;

        session.remember(AGENT, text);
        callLog.record(session.callId(), agentLine(session, text));
        session.send("say", "seq", 0, "text", text,
                "language", session.language().code(), "last", true);
    }

    /** Asks for the call to end, and sends it if nothing else is talking. */
    private void endCall(CallSession session, String reason, String farewellKey) {
        session.requestHangup(reason, spokenLine(session, farewellKey));
        hangUpIfAsked(session);
    }

    /**
     * Sends the hangup once, after whatever was being said has been said. The
     * farewell travels with it rather than as another sentence, so the voice
     * server can speak it and close in one move.
     */
    public void hangUpIfAsked(CallSession session) {
        CallSession.Hangup hangup = session.takeHangup();
        if (hangup == null) return;

        if (hangup.farewellText() != null && !hangup.farewellText().isBlank()) {
            session.remember(AGENT, hangup.farewellText());
            callLog.record(session.callId(), agentLine(session, hangup.farewellText()));
        }
        log.info("[{}] hanging up ({})", session.callId(), hangup.reason());
        session.send("hangup", "reason", hangup.reason(), "language", session.language().code(),
                "farewellText", hangup.farewellText() == null ? "" : hangup.farewellText());
    }

    /** One of the few lines the agent says that the model did not write. */
    public static String spokenLine(CallSession session, String stringKey) {
        return Lang.ui(session.language().code()).get(stringKey);
    }

    private static CallDtos.LineToStore agentLine(CallSession session, String text) {
        return CallDtos.LineToStore.agent(text, session.language().code(),
                session.mode().name(), 0, null, null, null);
    }
}
