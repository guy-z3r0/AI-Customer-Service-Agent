package com.ulab.agent.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telling the goodbye from everything else the agent says.
 *
 * From a real call: the agent said "Thank you for contacting Bengal Power
 * System, and have a good day" and then stayed on the line, and the caller had
 * to ask twice whether anybody was there. The line below is that line.
 *
 * The two ways this can be wrong are not equally bad. Missing a goodbye leaves
 * a caller to say it again; hanging up on a conversation that was still going
 * cannot be undone at all — so every case here that is not plainly a farewell
 * is expected to stay on the line.
 */
class FarewellSenseTest {

    @Test
    void theLineFromTheCallThatStartedThisEndsTheCall() {
        assertTrue(FarewellSense.endsTheCall(
                "I have successfully updated your details in our system, and a team member "
                        + "will follow up with you regarding your request to speak with a "
                        + "manager. Thank you for contacting Bengal Power System, and have a "
                        + "good day."));
    }

    @Test
    void aGoodbyeInEitherLanguageEndsTheCall() {
        assertTrue(FarewellSense.endsTheCall("Thank you for calling. Goodbye."));
        assertTrue(FarewellSense.endsTheCall("কল করার জন্য ধন্যবাদ। বিদায়।"));
        assertTrue(FarewellSense.endsTheCall("ঠিক আছে, ভালো থাকবেন।"));
    }

    @Test
    void anythingStillAskingSomethingStaysOnTheLine() {
        // The same words with a question after them are the agent offering to
        // finish, not finishing. This is the line that came next on that call.
        assertFalse(FarewellSense.endsTheCall(
                "Yes, I am still here. Is there anything else I can do for you, or shall I "
                        + "end the call now?"));
        assertFalse(FarewellSense.endsTheCall(
                "Before I say goodbye, is there anything else you need?"));
        assertFalse(FarewellSense.endsTheCall("আর কিছু লাগবে? না হলে বিদায় জানাব।"));
    }

    @Test
    void anOrdinaryAnswerThatForgotItsQuestionIsNotAGoodbye() {
        // A reply with no question is a reply that broke a rule, and breaking
        // that rule must not cost the caller their call.
        assertFalse(FarewellSense.endsTheCall(
                "Same-day delivery inside Dhaka is 80 taka for up to one kilogram."));
        assertFalse(FarewellSense.endsTheCall("I have noted that down for our team."));
        assertFalse(FarewellSense.endsTheCall(
                "Sorry, I had trouble answering that. Could you say it again?"));
    }

    @Test
    void nothingSaidIsNotAGoodbyeEither() {
        assertFalse(FarewellSense.endsTheCall(null));
        assertFalse(FarewellSense.endsTheCall("   "));
    }
}
