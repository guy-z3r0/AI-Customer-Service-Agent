package com.ulab.agent.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes the personal details out of what a caller said, before that sentence is
 * sent anywhere it does not have to go.
 *
 * A caller reads their national ID card number out loud so the agent can look
 * them up. The agent does not need it — it already has the record — but the
 * sentence is on its way to a company's servers, into a written summary, and
 * out again in an email to a colleague. This class is the point where the
 * number stops travelling.
 *
 * What it does not touch is the transcript. The operator watching the call is
 * meant to see what was actually said; masking is for what leaves the building.
 *
 * Both scripts are handled. A Bangla speaker's phone number is written ০১৭…
 * rather than 017…, and a masker that only knew Western digits would let it
 * straight through.
 */
public final class PiiMasker {

    public static final String NID = "[MASKED_NID]";
    public static final String PHONE = "[MASKED_PHONE]";
    public static final String EMAIL = "[MASKED_EMAIL]";
    public static final String AMOUNT = "[MASKED_AMOUNT]";

    /** One digit in either script: 0-9 or ০-৯. */
    private static final String DIGIT = "[0-9০-৯]";

    /** Lengths a Bangladeshi national ID number comes in. */
    private static final int NID_OLD = 10;
    private static final int NID_BIRTH = 13;
    private static final int NID_SMART = 17;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]*\\w");

    /** A number with a currency word or symbol on one side of it. */
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?i)(?:৳|\\$|tk\\.?|bdt|usd|rs\\.?)\\s*" + DIGIT + "[0-9০-৯,.]*"
                    + "|" + DIGIT + "[0-9০-৯,.]*\\s*"
                    + "(?:৳|টাকা|taka|tk\\.?|bdt|usd|dollars?)");

    /** Seven or more digits, allowing one space or dash between any two. */
    private static final Pattern DIGIT_RUN_PATTERN =
            Pattern.compile("\\+?" + DIGIT + "(?:[ -]?" + DIGIT + "){6,}");

    /** Every Bangladeshi mobile number, in the form people read out. */
    private static final Pattern MOBILE_PATTERN = Pattern.compile("01[3-9][0-9]{8}");

    private PiiMasker() {
        // Static helpers only; nothing to construct.
    }

    /**
     * The masked version of one caller's sentence.
     *
     * The order is not arbitrary. Email addresses go first because one can hold
     * a long run of digits, and amounts go before bare numbers because the
     * currency beside them is the only thing that says what the number means.
     */
    public static String mask(String text) {
        if (text == null || text.isBlank()) return text;

        String masked = EMAIL_PATTERN.matcher(text).replaceAll(EMAIL);
        masked = AMOUNT_PATTERN.matcher(masked).replaceAll(Matcher.quoteReplacement(AMOUNT));
        return maskDigitRuns(masked);
    }

    /** True when masking would change the text — used to log that it did. */
    public static boolean carriesPersonalDetail(String text) {
        return text != null && !text.equals(mask(text));
    }

    // ------------------------------------------------------------ internals --

    private static String maskDigitRuns(String text) {
        Matcher matcher = DIGIT_RUN_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(classify(matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Decides what a run of digits is, by its length and how it starts.
     *
     * A number that fits nothing is left exactly as it was. An order number or
     * a year read aloud is not a secret, and a masker that swallowed every
     * number in the call would leave the model unable to answer anything.
     */
    private static String classify(String run) {
        String digits = toWesternDigits(run);

        if (MOBILE_PATTERN.matcher(nationalForm(digits)).matches()) return PHONE;
        if (isNidLength(digits.length())) return NID;
        if (run.startsWith("+") && digits.length() >= 8 && digits.length() <= 15) return PHONE;
        if (digits.startsWith("0") && digits.length() >= 9 && digits.length() <= 15) return PHONE;
        return run;
    }

    private static boolean isNidLength(int length) {
        return length == NID_OLD || length == NID_BIRTH || length == NID_SMART;
    }

    /**
     * The same number as a Bangladeshi would dial it: no country code, one
     * leading zero. +8801712…, 8801712… and 1712… are all one person's phone,
     * and comparing them is only possible once they are written the same way.
     */
    private static String nationalForm(String digits) {
        String local = digits;
        if (local.startsWith("880")) local = local.substring(3);
        else if (local.startsWith("88") && local.length() >= 13) local = local.substring(2);
        return local.startsWith("0") ? local : "0" + local;
    }

    /** Keeps only the digits, and writes ০১৭ as 017 so lengths can be compared. */
    private static String toWesternDigits(String run) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < run.length(); i++) {
            int value = Character.digit(run.charAt(i), 10);
            if (value >= 0) digits.append(value);
        }
        return digits.toString();
    }
}
