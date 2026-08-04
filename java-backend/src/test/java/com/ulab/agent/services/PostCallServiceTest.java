package com.ulab.agent.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading back what the model wrote about a finished call.
 *
 * A model asked for JSON usually sends JSON, and the rest of the time it sends
 * JSON wrapped in something — a code fence, a sentence of introduction. Both of
 * those are worth forgiving. A reply with no JSON in it at all is not, because
 * the alternative to a bad summary is a plain one, and this is where that fork
 * is taken.
 */
class PostCallServiceTest {

    @Test
    void aPlainJsonReplyIsRead() {
        PostCallService.Written written = PostCallService.parse("""
                {"summary_text": "The caller asked about opening hours and was told them.",
                 "structured": {"intent": "opening hours", "outcome": "answered"},
                 "action_items": ["Call back on Sunday"]}""");

        assertNotNull(written);
        assertEquals("The caller asked about opening hours and was told them.", written.text());
        assertEquals("opening hours", written.structured().get("intent").getAsString());
        assertEquals(1, written.actions().size());
    }

    @Test
    void aCodeFenceAndAPrefaceAreForgiven() {
        PostCallService.Written written = PostCallService.parse("""
                Sure, here is the summary:
                ```json
                {"summary_text": "A wrong number.", "structured": {}, "action_items": []}
                ```""");

        assertNotNull(written);
        assertEquals("A wrong number.", written.text());
        assertTrue(written.actions().isEmpty());
    }

    @Test
    void missingPartsBecomeEmptyRatherThanNull() {
        PostCallService.Written written = PostCallService.parse(
                "{\"summary_text\": \"Nothing else came back.\"}");

        assertNotNull(written);
        assertTrue(written.structured().isEmpty());
        assertTrue(written.actions().isEmpty());
    }

    @Test
    void aReplyWithNoUsableSummaryIsRefused() {
        assertNull(PostCallService.parse(""), "nothing at all");
        assertNull(PostCallService.parse("I am afraid I cannot help with that."), "no JSON");
        assertNull(PostCallService.parse("{\"structured\": {}}"), "JSON, but no summary in it");
        assertNull(PostCallService.parse("{\"summary_text\": \"  \"}"), "a blank summary");
    }
}
