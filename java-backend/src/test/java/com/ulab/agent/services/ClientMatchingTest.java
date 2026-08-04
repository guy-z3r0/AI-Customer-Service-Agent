package com.ulab.agent.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deciding whether two phone numbers are the same person.
 *
 * This is the rule that lets a call greet somebody by name, so being wrong in
 * the generous direction is the dangerous one: matching the wrong customer
 * means telling a stranger what is on another person's record. The masked case
 * at the bottom is not hypothetical — it happened on a live call, and it is
 * what this rule now exists to stop.
 */
class ClientMatchingTest {

    @Test
    void theSameNumberWrittenTwoWaysIsOnePerson() {
        assertTrue(ClientService.sameNumber("+8801711111111", "01711111111"));
        assertTrue(ClientService.sameNumber("01711111111", "+8801711111111"));
        assertTrue(ClientService.sameNumber("017-1111-1111", "01711111111"));
        assertTrue(ClientService.sameNumber("+880 1711 111111", "1711111111"));
    }

    @Test
    void twoDifferentPeopleDoNotMatch() {
        assertFalse(ClientService.sameNumber("+8801711111111", "01722222222"));
        assertFalse(ClientService.sameNumber("01733333333", "01712345678"));
    }

    @Test
    void aNumberWithNoDigitsMatchesNobody() {
        // What a masked caller line leaves behind. Before this rule existed it
        // matched whoever was first on the books, because every string ends
        // with the empty one.
        assertFalse(ClientService.sameNumber("+8801711111111", "[MASKED_PHONE]"));
        assertFalse(ClientService.sameNumber("+8801711111111", ""));
        assertFalse(ClientService.sameNumber("+8801711111111", null));
        assertFalse(ClientService.sameNumber(null, "01711111111"));
    }

    @Test
    void tooFewDigitsIsNotEnoughToNameSomebody() {
        assertFalse(ClientService.sameNumber("+8801711111111", "1111"),
                "four digits would match half a customer list");
        assertTrue(ClientService.sameNumber("+8801711111111", "111111"),
                "six is the agreed line, and it is a real suffix of that number");
    }
}
