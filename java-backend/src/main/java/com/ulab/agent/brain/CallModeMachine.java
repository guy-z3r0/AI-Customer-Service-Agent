package com.ulab.agent.brain;

import com.ulab.agent.domain.ModeTransition;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.services.CallLogService;
import com.ulab.agent.utils.Lang;
import com.ulab.agent.utils.Prompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which of the four situations a call is in, and which of them it may move to.
 *
 * A call opens as a new caller or a known customer and can be reclassified once
 * — as a wrong number, or as something a person has to take over. Those two are
 * the end of the road: a call that has been called a nuisance does not get
 * talked back into being a customer, and a call already promised to a human is
 * not quietly taken back. Refusing the move is the point of having a table at
 * all, because the thing asking for it is a language model that can be talked
 * into anything.
 *
 * A move is only real once it has been written down, so every accepted one goes
 * to mode_transition with the reason that was given for it.
 */
@Component
public class CallModeMachine {

    private static final Logger log = LoggerFactory.getLogger(CallModeMachine.class);

    /** Where a call in each mode is allowed to go next. */
    private static final Map<CallMode, Set<CallMode>> LEGAL_MOVES = buildLegalMoves();

    private final CallLogService callLog;

    public CallModeMachine(CallLogService callLog) {
        this.callLog = callLog;
    }

    /** Known callers start as customers; everyone else starts as a stranger. */
    public CallMode initialMode(boolean callerIsKnown) {
        return callerIsKnown ? CallMode.EXISTING_CUSTOMER : CallMode.NEW_CUSTOMER;
    }

    /** Writes down the mode a call opened in, which has no mode before it. */
    public void open(CallSession session) {
        ModeTransition opening = new ModeTransition();
        opening.setCallId(session.callId());
        opening.setToMode(session.mode());
        opening.setReason("call started");
        callLog.recordModeChange(opening);
    }

    public boolean allows(CallMode from, CallMode to) {
        return LEGAL_MOVES.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Moves a call to another mode, or refuses.
     *
     * @param reason why, in plain words — it is stored and read by a person later
     * @return the transition that was written down, or null if the move is not legal
     */
    public ModeTransition apply(CallSession session, CallMode wanted, String reason) {
        if (wanted == null) return null;

        synchronized (session) {
            CallMode from = session.mode();
            if (!allows(from, wanted)) {
                log.info("[{}] refused a move from {} to {}", session.callId(), from, wanted);
                return null;
            }

            session.setMode(wanted);
            ModeTransition transition = new ModeTransition();
            transition.setCallId(session.callId());
            transition.setFromMode(from);
            transition.setToMode(wanted);
            transition.setReason(reason == null || reason.isBlank() ? "no reason given" : reason);
            callLog.recordModeChange(transition);

            log.info("[{}] {} -> {} ({})", session.callId(), from, wanted, transition.getReason());
            return transition;
        }
    }

    /** The extra standing orders that apply while a call is in this mode. */
    public String instructionsFor(CallMode mode) {
        return switch (mode) {
            case NEW_CUSTOMER -> Prompts.MODE_NEW_CUSTOMER;
            case EXISTING_CUSTOMER -> Prompts.MODE_EXISTING_CUSTOMER;
            case WRONG_NUMBER -> Prompts.MODE_WRONG_NUMBER;
            case COMPLEX_REQUEST -> Prompts.MODE_COMPLEX_REQUEST;
        };
    }

    /**
     * True for the one mode that hangs up. A complex request stays on the line —
     * there are details to take down before a person can pick it up — but a
     * wrong number has nothing left to say after goodbye.
     */
    public boolean endsTheCall(CallMode mode) {
        return mode == CallMode.WRONG_NUMBER;
    }

    /** Which line the agent says on the way out of a call this mode ended. */
    public String farewellKey(CallMode mode) {
        return mode == CallMode.WRONG_NUMBER ? "voice.wrong_number_farewell" : "voice.goodbye";
    }

    private static Map<CallMode, Set<CallMode>> buildLegalMoves() {
        Map<CallMode, Set<CallMode>> moves = new EnumMap<>(CallMode.class);
        moves.put(CallMode.NEW_CUSTOMER, EnumSet.of(CallMode.EXISTING_CUSTOMER,
                CallMode.WRONG_NUMBER, CallMode.COMPLEX_REQUEST));
        moves.put(CallMode.EXISTING_CUSTOMER, EnumSet.of(CallMode.WRONG_NUMBER,
                CallMode.COMPLEX_REQUEST));
        moves.put(CallMode.WRONG_NUMBER, EnumSet.noneOf(CallMode.class));
        moves.put(CallMode.COMPLEX_REQUEST, EnumSet.noneOf(CallMode.class));
        return moves;
    }
}
