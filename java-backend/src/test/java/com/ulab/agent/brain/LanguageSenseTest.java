package com.ulab.agent.brain;

import com.ulab.agent.domain.enums.CallMode;
import com.ulab.agent.domain.enums.Language;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Following the caller rather than waiting for the model to notice.
 *
 * The bug behind all of this: a Bangla call whose caller switches to English
 * stays on a Bangla recogniser, so every English sentence comes back as Bengali
 * letters spelling English sounds — including the one asking to switch, which
 * leaves the caller with no way out of it.
 */
class LanguageSenseTest {

    @Test
    void aCallerWritingInLatinLettersOnABanglaCallIsSpeakingEnglish() {
        assertEquals(Language.EN,
                LanguageSense.wantedBy("I would like to book an appointment", Language.BN));
    }

    @Test
    void aCallerWritingInBengaliOnAnEnglishCallIsSpeakingBangla() {
        assertEquals(Language.BN,
                LanguageSense.wantedBy("আমি একটি অ্যাপয়েন্টমেন্ট নিতে চাই", Language.EN));
    }

    @Test
    void aCallAlreadyInTheRightLanguageIsLeftAlone() {
        assertNull(LanguageSense.wantedBy("I would like to book an appointment", Language.EN));
        assertNull(LanguageSense.wantedBy("আমি একটি অ্যাপয়েন্টমেন্ট নিতে চাই", Language.BN));
    }

    @Test
    void askingForTheOtherLanguageCountsForMoreThanTheScriptItWasAskedIn() {
        // The recogniser hands back Bengali letters on a Bangla call whatever
        // was said. A caller asking for English in them still means it.
        assertEquals(Language.EN, LanguageSense.wantedBy("ইংরেজিতে বলুন please", Language.BN));
        assertEquals(Language.BN, LanguageSense.wantedBy("can we speak in Bangla", Language.EN));
    }

    @Test
    void aCallerNamingBothLanguagesIsNotAskingForEither() {
        assertNull(LanguageSense.wantedBy("do you speak English and Bangla?", Language.EN));
    }

    @Test
    void banglishLeavesTheCallWhereItIs() {
        // Half and half is exactly what the recogniser is already listening for.
        assertNull(LanguageSense.wantedBy("আমার একটা appointment লাগবে কালকে", Language.BN));
    }

    @Test
    void aShortNoiseIsNotEvidenceOfAnything() {
        assertNull(LanguageSense.wantedBy("ok", Language.BN));
        assertNull(LanguageSense.wantedBy("hmm", Language.BN));
        assertNull(LanguageSense.wantedBy("2025", Language.BN));
    }

    @Test
    void movingTheCallTellsTheVoiceServerAndIsWrittenDown() {
        TestCalls.Outbox outbox = new TestCalls.Outbox();
        TestCalls.Recorder log = new TestCalls.Recorder();
        CallSession session = TestCalls.session(CallMode.NEW_CUSTOMER, Language.BN, outbox);

        assertTrue(LanguageSense.apply(session, Language.EN, log));

        assertEquals(Language.EN, session.language());
        assertEquals(List.of(Language.EN), log.languageChanges);
        List<Map<String, Object>> told = outbox.ofType("set_language");
        assertEquals(1, told.size(), "the recogniser and the voice both have to change");
        assertEquals("en", told.get(0).get("language"));
    }

    @Test
    void movingACallToWhereItAlreadyIsChangesNothing() {
        TestCalls.Outbox outbox = new TestCalls.Outbox();
        TestCalls.Recorder log = new TestCalls.Recorder();
        CallSession session = TestCalls.session(CallMode.NEW_CUSTOMER, Language.EN, outbox);

        assertFalse(LanguageSense.apply(session, Language.EN, log));
        assertTrue(outbox.ofType("set_language").isEmpty());
        assertTrue(log.languageChanges.isEmpty());
    }
}
