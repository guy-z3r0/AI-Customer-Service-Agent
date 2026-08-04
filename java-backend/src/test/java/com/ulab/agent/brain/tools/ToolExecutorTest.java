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

        assertEquals(6, schemas.size());
        schemas.forEach(schema -> {
            assertTrue(schema.contains("\"name\""));
            assertTrue(schema.contains("\"description\""));
            assertTrue(schema.contains("\"parameters\""));
        });
    }

    // -------------------------------------------------------------- customers --

    @Test
    void aCallerWhoReadsOutTheirCodeIsRecognisedAndStopsBeingAStranger() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111",
                List.of("March: a repair, resolved."));

        String result = wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"clientCode\":\"c001\"}");

        assertTrue(result.contains("\"ok\":true"));
        assertTrue(result.contains("Example Customer One"));
        assertEquals("C001", wiring.session().client().clientCode());
        assertEquals(CallMode.EXISTING_CUSTOMER, wiring.session().mode());
        assertEquals(List.of("Example Customer One"), wiring.log().identified);
    }

    @Test
    void aCallerCanBeFoundByTheNumberTheyGive() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);
        wiring.customers().add("C001", "Example Customer One", "+8801711111111", List.of());

        String result = wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"phone\":\"01711111111\"}");

        assertTrue(result.contains("\"ok\":true"), "the same number written another way");
        assertEquals("C001", wiring.session().client().clientCode());
    }

    @Test
    void aStrangerWhoIsNotOnTheBooksIsReportedAsNotFound() {
        TestCalls.Wiring wiring = TestCalls.wire(CallMode.NEW_CUSTOMER, Language.EN);

        assertTrue(wiring.executor().run(wiring.session(), ToolRegistry.LOOKUP_CLIENT,
                "{\"clientCode\":\"C999\"}").contains("\"ok\":false"));
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
                "{\"clientCode\":\"C001\"}");

        wiring.executor().run(wiring.session(), ToolRegistry.LOG_REQUEST,
                "{\"summary\":\"August: asked about the warranty.\"}");

        assertEquals(List.of("March: a repair, resolved.", "August: asked about the warranty."),
                wiring.session().client().pastIssues());
    }
}
