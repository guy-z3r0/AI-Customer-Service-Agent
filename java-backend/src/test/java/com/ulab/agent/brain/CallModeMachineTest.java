package com.ulab.agent.brain;

import com.ulab.agent.domain.ModeTransition;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table is the whole point of this class: the thing asking to change a
 * call's mode is a language model, and a model can be talked into anything by
 * whoever is on the phone. These say what it cannot be talked into.
 */
class CallModeMachineTest {

    @Test
    void aCallOpensAsAStrangerUnlessTheCallerIsOnRecord() {
        CallModeMachine machine = new CallModeMachine(new TestCalls.Recorder());

        assertEquals(CallMode.NEW_CUSTOMER, machine.initialMode(false));
        assertEquals(CallMode.EXISTING_CUSTOMER, machine.initialMode(true));
    }

    @Test
    void aNewCallerCanTurnOutToBeAnythingElse() {
        CallModeMachine machine = new CallModeMachine(new TestCalls.Recorder());

        assertTrue(machine.allows(CallMode.NEW_CUSTOMER, CallMode.EXISTING_CUSTOMER));
        assertTrue(machine.allows(CallMode.NEW_CUSTOMER, CallMode.WRONG_NUMBER));
        assertTrue(machine.allows(CallMode.NEW_CUSTOMER, CallMode.COMPLEX_REQUEST));
    }

    @Test
    void aKnownCustomerCannotBeDemotedToAStranger() {
        CallModeMachine machine = new CallModeMachine(new TestCalls.Recorder());

        assertFalse(machine.allows(CallMode.EXISTING_CUSTOMER, CallMode.NEW_CUSTOMER));
        assertTrue(machine.allows(CallMode.EXISTING_CUSTOMER, CallMode.WRONG_NUMBER));
        assertTrue(machine.allows(CallMode.EXISTING_CUSTOMER, CallMode.COMPLEX_REQUEST));
    }

    @Test
    void theLastTwoModesAreTheEndOfTheRoad() {
        CallModeMachine machine = new CallModeMachine(new TestCalls.Recorder());

        for (CallMode target : CallMode.values()) {
            assertFalse(machine.allows(CallMode.WRONG_NUMBER, target),
                    "a nuisance call must not be talked back into being a customer");
            assertFalse(machine.allows(CallMode.COMPLEX_REQUEST, target),
                    "a call already promised to a person must not be quietly taken back");
        }
    }

    @Test
    void anAcceptedMoveIsWrittenDownWithItsReason() {
        TestCalls.Recorder log = new TestCalls.Recorder();
        CallModeMachine machine = new CallModeMachine(log);
        CallSession session = TestCalls.session(CallMode.NEW_CUSTOMER, Language.EN,
                new TestCalls.Outbox());

        ModeTransition transition = machine.apply(session, CallMode.COMPLEX_REQUEST,
                "caller wants a refund we cannot approve");

        assertNotNull(transition);
        assertEquals(CallMode.NEW_CUSTOMER, transition.getFromMode());
        assertEquals(CallMode.COMPLEX_REQUEST, transition.getToMode());
        assertEquals(CallMode.COMPLEX_REQUEST, session.mode());
        assertEquals(1, log.modeChanges.size());
        assertEquals("caller wants a refund we cannot approve", log.modeChanges.get(0).getReason());
    }

    @Test
    void aRefusedMoveChangesNothingAndIsNotWrittenDown() {
        TestCalls.Recorder log = new TestCalls.Recorder();
        CallModeMachine machine = new CallModeMachine(log);
        CallSession session = TestCalls.session(CallMode.WRONG_NUMBER, Language.EN,
                new TestCalls.Outbox());

        assertNull(machine.apply(session, CallMode.EXISTING_CUSTOMER, "the caller insisted"));
        assertEquals(CallMode.WRONG_NUMBER, session.mode());
        assertTrue(log.modeChanges.isEmpty());
    }

    @Test
    void onlyAWrongNumberHangsUpOnItsOwn() {
        CallModeMachine machine = new CallModeMachine(new TestCalls.Recorder());

        assertTrue(machine.endsTheCall(CallMode.WRONG_NUMBER));
        assertFalse(machine.endsTheCall(CallMode.COMPLEX_REQUEST),
                "there are still details to take down before a person picks it up");
    }

    @Test
    void everyModeHasItsOwnStandingOrders() {
        CallModeMachine machine = new CallModeMachine(new TestCalls.Recorder());

        for (CallMode mode : CallMode.values()) {
            String instructions = machine.instructionsFor(mode);
            assertNotNull(instructions);
            assertFalse(instructions.isBlank(), mode + " has nothing to tell the model");
        }
    }
}
