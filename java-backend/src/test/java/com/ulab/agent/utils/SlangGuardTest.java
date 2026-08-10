package com.ulab.agent.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What counts as swearing at the agent, and — more important — what does not.
 *
 * A customer service line that hangs up on annoyance is worse than one that
 * puts up with a word, so the cases below that must not match are the ones
 * worth reading.
 */
class SlangGuardTest {

    @Test
    void abuseInEnglishIsRecognised() {
        assertTrue(SlangGuard.isAbusive("this is a load of bullshit"));
        assertTrue(SlangGuard.isAbusive("You are an idiot, you absolute bastard"));
        assertTrue(SlangGuard.isAbusive("SHUT UP"));
    }

    @Test
    void abuseInBanglaIsRecognisedInEitherScript() {
        assertTrue(SlangGuard.isAbusive("তুই একটা হারামজাদা"));
        assertTrue(SlangGuard.isAbusive("shala banchot ke dhorbi"));
    }

    @Test
    void anIrritatedCustomerIsStillACustomer() {
        assertFalse(SlangGuard.isAbusive("This is useless, I have been waiting all week"));
        assertFalse(SlangGuard.isAbusive("Your service is terrible and I want a refund"));
        assertFalse(SlangGuard.isAbusive("Damn, that is expensive"));
        assertFalse(SlangGuard.isAbusive("আপনাদের সার্ভিস খুবই বাজে"));
    }

    @Test
    void aLongerWordThatMerelyContainsOneDoesNotCount() {
        // The classic false positive: a word list without boundaries flags
        // "assessment", "Scunthorpe" and every place name like them.
        assertFalse(SlangGuard.isAbusive("I need an assessment of the damage"));
        assertFalse(SlangGuard.isAbusive("We are in Scunthorpe"));
        assertFalse(SlangGuard.isAbusive("The shitake mushrooms were lovely"));
    }

    @Test
    void nothingToReadIsNotAbuse() {
        assertFalse(SlangGuard.isAbusive(null));
        assertFalse(SlangGuard.isAbusive(""));
        assertFalse(SlangGuard.isAbusive("   "));
    }
}
