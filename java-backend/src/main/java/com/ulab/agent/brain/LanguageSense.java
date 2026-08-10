package com.ulab.agent.brain;

import com.ulab.agent.domain.enums.Language;
import com.ulab.agent.services.CallLogService;

import java.util.regex.Pattern;

/**
 * Which language a call should be in, judged from what the caller just said —
 * and the one place that moves a call from one to the other.
 *
 * The model has a set_language action and is told to use it, but it is a model:
 * on a Bangla call it will happily answer an English question in English and
 * never mention it, and the recogniser goes on listening for Bangla. Every word
 * after that comes back as Bengali letters spelling English sounds, which is
 * unusable — and the caller cannot get out of it, because asking to switch is
 * itself a sentence the recogniser mangles.
 *
 * So the switch does not depend on the model noticing. What the caller actually
 * said is written in one script or the other, and that is a fact this class can
 * read for itself.
 */
public final class LanguageSense {

    private LanguageSense() {
        // Static helpers only; nothing to construct.
    }

    /**
     * How many letters an utterance needs before its script is evidence.
     *
     * "ok", "hmm" and a stray "yes" are written in Latin letters on a Bangla
     * call and mean nothing about which language anybody wants.
     */
    private static final int MIN_LETTERS = 8;

    /**
     * How one-sided the script has to be. Callers here mix the two languages
     * inside one sentence, and a sentence that is half and half is Banglish —
     * the recogniser is already listening for both and the call should stay
     * where it is.
     */
    private static final double CLEAR_MAJORITY = 0.8;

    /** Bengali letters, digits and marks all live in one Unicode block. */
    private static final int BENGALI_FIRST = 0x0980;
    private static final int BENGALI_LAST = 0x09FF;

    private static final Pattern ASKED_FOR_ENGLISH =
            Pattern.compile("(?i)\\benglish\\b|ইংরেজি|ইংলিশ");
    private static final Pattern ASKED_FOR_BANGLA =
            Pattern.compile("(?i)\\b(bangla|bengali)\\b|বাংলা|বাংলায়");

    /**
     * The language this call should move to, or null to leave it alone.
     *
     * Asking counts for more than writing: a caller who says "can we do this in
     * English" has said so in whatever script the recogniser gave back, and that
     * request is answered even when the letters point the other way.
     */
    public static Language wantedBy(String callerText, Language current) {
        if (callerText == null || callerText.isBlank()) return null;

        Language asked = askedFor(callerText);
        if (asked != null) return asked == current ? null : asked;

        Language written = scriptOf(callerText);
        return written == null || written == current ? null : written;
    }

    /**
     * Moves the call, writes it down, and tells the voice server — which has to
     * change both recogniser and voice, and is the only part that knows what
     * that costs.
     *
     * @return false when the call was already in that language
     */
    public static boolean apply(CallSession session, Language language, CallLogService callLog) {
        if (language == null || language == session.language()) return false;

        session.setLanguage(language);
        callLog.recordLanguageChange(session.callId(), language);
        session.send("set_language", "language", language.code());
        return true;
    }

    // ------------------------------------------------------------ internals --

    /** A caller naming one of the two languages, and not the other. */
    private static Language askedFor(String text) {
        boolean english = ASKED_FOR_ENGLISH.matcher(text).find();
        boolean bangla = ASKED_FOR_BANGLA.matcher(text).find();
        if (english == bangla) return null;  // both, or neither: no request in there
        return english ? Language.EN : Language.BN;
    }

    /** Which script the sentence is overwhelmingly written in, if either. */
    private static Language scriptOf(String text) {
        int bengali = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char letter = text.charAt(i);
            if (letter >= BENGALI_FIRST && letter <= BENGALI_LAST) bengali++;
            else if (Character.isLetter(letter) && letter < 0x0080) latin++;
        }

        int letters = bengali + latin;
        if (letters < MIN_LETTERS) return null;
        if (bengali >= letters * CLEAR_MAJORITY) return Language.BN;
        if (latin >= letters * CLEAR_MAJORITY) return Language.EN;
        return null;
    }
}
