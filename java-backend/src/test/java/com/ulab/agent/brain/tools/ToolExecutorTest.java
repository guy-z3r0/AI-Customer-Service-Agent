package com.ulab.agent.brain.tools;

import com.ulab.agent.brain.CallSession;
import com.ulab.agent.brain.TestCalls;
import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the model asks for and what it is actually allowed to do.
 *
 * Every answer here is JSON the model reads back on its next pass, so a refusal
 * has to be as readable as a success — a tool that fails silently is a model
 * that repeats itself for the rest of the call.
 */
class ToolExecutorTest {

    @Test
    void switchingLanguageChangesTheCallAndTellsTheVoiceServer() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.SET_LANGUAGE,
                "{\"language\":\"bn\"}");

        assertTrue(result.contains("\"ok\":true"));
        assertEquals(Language.BN, wiring.session().language());
        assertEquals(List.of(Language.BN), wiring.log().languageChanges);

        List<Map<String, Object>> told = wiring.outbox().ofType("set_language");
        assertEquals(1, told.size());
        assertEquals("bn", told.get(0).get("language"));
    }

    @Test
    void aLanguageTheCallDoesNotSpeakIsRefusedRatherThanGuessed() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.SET_LANGUAGE,
                "{\"language\":\"fr\"}");

        assertTrue(result.contains("\"ok\":false"));
        assertEquals(Language.EN, wiring.session().language());
        assertTrue(wiring.outbox().ofType("set_language").isEmpty());
    }

    @Test
    void callingItAWrongNumberEndsTheCallWithAFarewell() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.SET_MODE,
                "{\"mode\":\"WRONG_NUMBER\",\"reason\":\"caller asked for a taxi\"}");

        assertTrue(result.contains("\"callIsEnding\":true"));
        assertTrue(wiring.session().isEnding());

        CallSession.Hangup hangup = wiring.session().takeHangup();
        assertNotNull(hangup);
        assertFalse(hangup.farewellText().isBlank(), "nobody is hung up on in silence");
    }

    @Test
    void aCallNeedingAPersonStaysOnTheLine() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.SET_MODE,
                "{\"mode\":\"COMPLEX_REQUEST\",\"reason\":\"a refund we cannot approve\"}");

        assertTrue(result.contains("\"callIsEnding\":false"));
        assertFalse(wiring.session().isEnding(), "there are still details to take down");
    }

    @Test
    void aMoveTheCallCannotMakeComesBackAsAReadableNo() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.WRONG_NUMBER, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.SET_MODE,
                "{\"mode\":\"EXISTING_CUSTOMER\",\"reason\":\"the caller insisted\"}");

        assertTrue(result.contains("\"ok\":false"));
        assertTrue(result.contains("error"));
        assertEquals(CallMode.WRONG_NUMBER, wiring.session().mode());
    }

    @Test
    void hangingUpSpeaksTheGoodbyeInTheCallsOwnLanguage() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.BN);

        wiring.executor().run(wiring.session(), ToolRegistry.END_CALL,
                "{\"reason\":\"the caller said goodbye\"}");

        CallSession.Hangup hangup = wiring.session().takeHangup();
        assertNotNull(hangup);
        assertTrue(hangup.farewellText().codePoints().anyMatch(c -> c >= 0x0980 && c <= 0x09FF),
                "a Bangla call is said goodbye to in Bangla");
    }

    @Test
    void anInventedToolIsRefusedInsteadOfThrowing() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        assertTrue(wiring.executor().run(wiring.session(), "refund_everything", "{}")
                .contains("\"ok\":false"));
    }

    @Test
    void argumentsThatAreNotJsonAreTreatedAsNoArguments() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        assertTrue(wiring.executor().run(wiring.session(), ToolRegistry.SET_LANGUAGE, "not json")
                .contains("\"ok\":false"));
    }

    @Test
    void everyToolHasASchemaBothVendorsCanRead() {
        List<String> schemas = new ToolRegistry().schemas();

        assertEquals(7, schemas.size());
        schemas.forEach(schema -> {
            assertTrue(schema.contains("\"name\""));
            assertTrue(schema.contains("\"description\""));
            assertTrue(schema.contains("\"parameters\""));
        });
    }

    // ------------------------------------------------------------ escalation --

    @Test
    void handingACallToAPersonMovesItAndKeepsTheDetailsTheyWillNeed() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.ESCALATE_TO_HUMAN,
                "{\"reason\":\"wants a refund we cannot approve\","
                        + "\"details\":\"order 4471, call back after six\"}");

        assertTrue(result.contains("\"ok\":true"));
        assertEquals(CallMode.COMPLEX_REQUEST, wiring.session().mode());
        assertFalse(wiring.session().isEnding(), "a promise of a person is not a goodbye");
        assertEquals("wants a refund we cannot approve",
                wiring.log().modeChanges.get(0).getReason());
        assertEquals(List.of("order 4471, call back after six"),
                wiring.log().lines.stream().map(line -> line.text()).toList());
    }

    @Test
    void askingForAManagerIsNotOnItsOwnAReasonToHandTheCallOver() {
        // From a real call: "I want to talk with your manager" was the whole of
        // what the caller had said, and it was escalated on that sentence — so a
        // colleague was handed a call with nothing in it to prepare for, and
        // whatever the caller actually wanted was never asked about.
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.ESCALATE_TO_HUMAN,
                "{\"reason\":\"caller asked for a person\"}");

        assertTrue(result.contains("\"ok\":false"));
        assertEquals(CallMode.NEW_CUSTOMER, wiring.session().mode(),
                "the call has not been handed over, so nothing may have been promised");
        assertTrue(wiring.log().modeChanges.isEmpty());
    }

    @Test
    void aDetailFieldSayingNothingIsTheSameAsNoDetailAtAll() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        assertTrue(wiring.executor().run(wiring.session(), ToolRegistry.ESCALATE_TO_HUMAN,
                "{\"reason\":\"wants a person\",\"details\":\"manager\"}")
                .contains("\"ok\":false"));
        assertEquals(CallMode.NEW_CUSTOMER, wiring.session().mode());
    }

    @Test
    void onceTheAgentKnowsWhatTheMatterIsItHandsItOverAtOnce() {
        // The rule is about knowing what to hand over, not about making the
        // caller ask twice: a complaint explained on the first turn is escalated
        // on the first turn.
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.ESCALATE_TO_HUMAN,
                "{\"reason\":\"a complaint about a late delivery\","
                        + "\"details\":\"parcel booked Tuesday has not arrived and the "
                        + "compensation offered was refused\"}");

        assertTrue(result.contains("\"ok\":true"));
        assertEquals(CallMode.COMPLEX_REQUEST, wiring.session().mode());
    }

    @Test
    void handingOverTwiceIsNotARefusal() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.COMPLEX_REQUEST, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.ESCALATE_TO_HUMAN,
                "{\"reason\":\"still needs a person\"}");

        assertTrue(result.contains("\"ok\":true"), "the promise was already made and still holds");
        assertTrue(result.contains("\"alreadyHandedOver\":true"));
        assertEquals(CallMode.COMPLEX_REQUEST, wiring.session().mode());
    }

    // -------------------------------------------------------------- customers --

    @Test
    void aCallerWhoProvesWhoTheyAreStopsBeingAStranger() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111",
                List.of("March: a repair, resolved."));

        String result = wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"name\":\"Example Customer One\",\"clientCode\":\"c001\","
                        + "\"phoneLastFour\":\"1111\"}");

        assertTrue(result.contains("\"ok\":true"));
        assertFalse(result.contains("Example Customer One"),
                "confirming an identity is not the same as disclosing it");
        assertEquals("C001", wiring.session().client().clientCode());
        assertEquals(CallMode.EXISTING_CUSTOMER, wiring.session().mode());
        assertEquals(List.of("Example Customer One"), wiring.log().identified);
    }

    @Test
    void aGuessedCustomerCodeAloneIdentifiesNobody() {
        // Codes run C001, C002, C003. On its own a code is a guess, and it used
        // to be answered with the customer's real name. SECURITY-AUDIT SEC-006.
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111", List.of());

        String result = wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"name\":\"Example Customer One\",\"clientCode\":\"C001\"}");

        assertTrue(result.contains("\"ok\":false"));
        assertFalse(result.contains("Example Customer One"));
        assertNull(wiring.session().client());
        assertEquals(CallMode.NEW_CUSTOMER, wiring.session().mode());
    }

    @Test
    void theWrongLastFourDigitsIdentifyNobody() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111", List.of());

        assertTrue(wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"name\":\"Example Customer One\",\"clientCode\":\"C001\","
                        + "\"phoneLastFour\":\"9999\"}").contains("\"ok\":false"));
        assertNull(wiring.session().client());
    }

    @Test
    void aCallCannotBeUsedToReadTheCustomerListOneCodeAtATime() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111", List.of());

        for (int guess = 1; guess <= 3; guess++) {
            wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                    "{\"name\":\"Example Customer One\",\"clientCode\":\"C00" + guess
                            + "\",\"phoneLastFour\":\"0000\"}");
        }

        // Even the right answer is refused now — the line is done answering.
        String afterwards = wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"name\":\"Example Customer One\",\"clientCode\":\"C001\","
                        + "\"phoneLastFour\":\"1111\"}");
        assertTrue(afterwards.contains("\"ok\":false"));
        assertNull(wiring.session().client());
    }

    @Test
    void aCallerCanBeFoundByTheNumberTheyGive() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111", List.of());

        String result = wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"name\":\"Example Customer One\",\"phone\":\"01711111111\"}");

        assertTrue(result.contains("\"ok\":true"), "the same number written another way");
        assertEquals("C001", wiring.session().client().clientCode());
    }

    @Test
    void aNumberWithoutANameIdentifiesNobody() {
        // A number was proof on its own, and it is not: handsets are shared,
        // numbers get reassigned, and a wrong digit over a bad line lands on
        // somebody else's record — who the caller would then be greeted as.
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111", List.of());

        assertTrue(wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"phone\":\"01711111111\"}").contains("\"ok\":false"));
        assertNull(wiring.session().client());
        assertEquals(CallMode.NEW_CUSTOMER, wiring.session().mode());
    }

    @Test
    void theRightNumberWithSomebodyElsesNameIdentifiesNobody() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111", List.of());

        String result = wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"name\":\"Somebody Else\",\"phone\":\"01711111111\"}");

        assertTrue(result.contains("\"ok\":false"));
        assertFalse(result.contains("Example Customer One"),
                "a near miss must not tell a guesser whose number they found");
        assertNull(wiring.session().client());
    }

    @Test
    void thePartOfANameACallerActuallySaysIsEnough() {
        // Nobody reads their full name off their own record. "Sadman" has to
        // match "Sadman Sakib", or the rule is one no real caller can satisfy.
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Sadman Sakib", "+8801711111111", List.of());

        assertTrue(wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"name\":\"Sadman\",\"phone\":\"01711111111\"}").contains("\"ok\":true"));
        assertEquals("C001", wiring.session().client().clientCode());
    }

    @Test
    void aRecordWrittenDuringTheCallIsNotReportedAsRecognised() {
        // The panel used to say "Recognised: Sadman" over a record created ten
        // seconds earlier from what the caller had just said their name was.
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        wiring.executor().run(wiring.session(), ToolRegistry.CREATE_CLIENT,
                "{\"name\":\"Sadman\",\"phone\":\"01700000000\",\"request\":\"asked about prices\"}");

        assertEquals(List.of("Sadman"), wiring.log().identified);
        assertTrue(wiring.log().recognised.isEmpty(),
                "nothing was recognised; a name was written down");
    }

    @Test
    void aNumberAlreadyOnTheBooksIsNotWrittenDownASecondTime() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Sadman Sakib", "+8801711111111", List.of());

        String result = wiring.executor().run(wiring.session(), ToolRegistry.CREATE_CLIENT,
                "{\"name\":\"Sadman\",\"phone\":\"01711111111\",\"request\":\"a quote\"}");

        assertTrue(result.contains("\"ok\":false"));
        assertEquals(1, wiring.customers().records.size(),
                "two records for one person is two histories and one wrong callback");
    }

    @Test
    void aStrangerWhoIsNotOnTheBooksIsReportedAsNotFound() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        assertTrue(wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"name\":\"Nobody At All\",\"clientCode\":\"C999\"}").contains("\"ok\":false"));
        assertNull(wiring.session().client());
        assertEquals(CallMode.NEW_CUSTOMER, wiring.session().mode());
    }

    @Test
    void aNewCallerIsWrittenDownWithWhatTheyCalledAbout() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        String result = wiring.executor().run(wiring.session(), ToolRegistry.CREATE_CLIENT,
                "{\"name\":\"Rahim\",\"phone\":\"01700000000\","
                        + "\"request\":\"asked about a home visit\"}");

        assertTrue(result.contains("\"ok\":true"));
        assertEquals("Rahim", wiring.session().client().name());
        assertEquals(List.of("asked about a home visit"), wiring.session().client().pastIssues());
        assertEquals(1, wiring.customers().records.size());
    }

    @Test
    void thereIsOnlyOneNewRecordPerCall() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.executor().run(wiring.session(), ToolRegistry.CREATE_CLIENT,
                "{\"name\":\"Rahim\",\"request\":\"asked about a home visit\"}");

        String again = wiring.executor().run(wiring.session(), ToolRegistry.CREATE_CLIENT,
                "{\"name\":\"Rahim again\",\"request\":\"the same call\"}");

        assertTrue(again.contains("\"ok\":false"));
        assertEquals(1, wiring.customers().records.size());
    }

    @Test
    void nothingIsWrittenAgainstNobody() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        assertTrue(wiring.executor().run(wiring.session(), ToolRegistry.LOG_REQUEST,
                "{\"summary\":\"wanted a quote\"}").contains("\"ok\":false"));
    }

    @Test
    void whatTheCallerNeededIsAddedToTheirRecord() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111",
                List.of("March: a repair, resolved."));
        wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"name\":\"Example Customer One\",\"clientCode\":\"C001\","
                        + "\"phoneLastFour\":\"1111\"}");

        wiring.executor().run(wiring.session(), ToolRegistry.LOG_REQUEST,
                "{\"summary\":\"August: asked about the warranty.\"}");

        assertEquals(List.of("March: a repair, resolved.", "August: asked about the warranty."),
                wiring.session().client().pastIssues());
    }
}
