package com.ulab.agent.brain;

import java.util.List;
import java.util.Locale;

/**
 * Whether a reply the model wrote was the goodbye.
 *
 * A model that says "thank you for calling, and have a good day" and then does
 * not ask to hang up leaves a caller holding a dead line, listening to
 * nothing — which is exactly what happened on a real call: the agent said its
 * goodbye, stayed on, and the caller had to ask "are you still there?" twice
 * before it would put the phone down.
 *
 * Asking the model harder is not enough on its own, because the failure *is*
 * the model forgetting. This reads what it actually said instead, and it can do
 * that because the standing orders already make the two cases distinguishable:
 * every reply ends with a question except the goodbye. So a farewell is a reply
 * that says goodbye and asks nothing. Both halves are needed — "is there
 * anything else, or shall I say goodbye?" is a question and stays on the line,
 * and a reply that merely forgot its question mark is not hung up on.
 */
public final class FarewellSense {

    private FarewellSense() {
        // Nothing to construct; this reads a string.
    }

    /**
     * Turns of phrase that end a telephone call and start nothing.
     *
     * Deliberately short. Anything ambiguous — "thank you", "that is all" —
     * is left out, because the cost of a false positive is a call cut off
     * mid-conversation and the cost of a miss is the caller saying goodbye
     * again.
     */
    private static final List<String> ENGLISH = List.of(
            "goodbye", "good bye", "bye for now",
            "have a good day", "have a nice day", "have a great day",
            "have a good evening", "have a lovely day",
            "thank you for calling", "thanks for calling",
            "thank you for contacting");

    /** The same in Bengali script, including the two everyday sign-offs. */
    private static final List<String> BANGLA = List.of(
            "বিদায়", "আল্লাহ হাফেজ", "আল্লাহ হাফিজ", "খোদা হাফেজ",
            "শুভ দিন", "শুভ কামনা", "ভালো থাকবেন",
            "কল করার জন্য ধন্যবাদ", "যোগাযোগ করার জন্য ধন্যবাদ");

    /**
     * @param reply everything the agent said this turn, in either language
     * @return true when this was the goodbye and the call should now end
     */
    public static boolean endsTheCall(String reply) {
        if (reply == null || reply.isBlank()) return false;
        if (asksSomething(reply)) return false;

        String said = reply.toLowerCase(Locale.ROOT);
        return ENGLISH.stream().anyMatch(said::contains)
                || BANGLA.stream().anyMatch(reply::contains);
    }

    /**
     * Bengali writes its questions with the same mark English does — the danda
     * that ends a Bengali sentence is a full stop, not a question mark — so one
     * check covers both languages.
     */
    private static boolean asksSomething(String reply) {
        return reply.indexOf('?') >= 0;
    }
}
