package com.ulab.agent.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The indexed way of finding a customer by their number (BUG-007).
 *
 * Finding an inbound caller used to mean decrypting every customer on the books
 * and comparing the plain text, because pgcrypto gives the same number a
 * different ciphertext each time it is written. The hash is a column that can
 * be looked up instead.
 *
 * What matters here is that it agrees with {@link ClientService#sameNumber},
 * which is the rule the rest of the application matches by. Where it cannot
 * agree — numbers too short to hash — it has to answer "no idea" rather than
 * "nobody", so that the caller falls through to the slower comparison instead
 * of being told the customer does not exist.
 */
class ClientPhoneHashTest {

    @Test
    void oneNumberWrittenFourWaysHashesToOneValue() {
        String canonical = ClientService.phoneHash("+8801711111111");

        assertNotNull(canonical);
        assertEquals(canonical, ClientService.phoneHash("01711111111"));
        assertEquals(canonical, ClientService.phoneHash("017-1111-1111"));
        assertEquals(canonical, ClientService.phoneHash("+880 1711 111111"));
    }

    @Test
    void twoDifferentPeopleHashToDifferentValues() {
        assertNotEquals(ClientService.phoneHash("+8801711111111"),
                ClientService.phoneHash("+8801722222222"));
    }

    @Test
    void theHashAgreesWithTheRuleTheRestOfTheAppMatchesBy() {
        String[][] sameperson = {
                {"+8801711111111", "01711111111"},
                {"01711111111", "1711111111"},
                {"+880 1711 111111", "017 1111 1111"},
        };
        for (String[] pair : sameperson) {
            assertTrue(ClientService.sameNumber(pair[0], pair[1]), pair[0] + " vs " + pair[1]);
            assertEquals(ClientService.phoneHash(pair[0]), ClientService.phoneHash(pair[1]),
                    "the index has to find whoever the rule would have found");
        }
    }

    @Test
    void aNumberTooShortToHashSaysSoRatherThanHashingSomethingElse() {
        // Six digits still matches by suffix, so a hash that answered here
        // would have to be wrong: it would say "nobody" for somebody the slow
        // comparison finds. Null is how the lookup is told to fall through.
        assertNull(ClientService.phoneHash("111111"));
        assertTrue(ClientService.sameNumber("+8801711111111", "111111"));
    }

    @Test
    void thereIsNothingToHashInAMaskedOrEmptyNumber() {
        assertNull(ClientService.phoneHash("[MASKED_PHONE]"));
        assertNull(ClientService.phoneHash(""));
        assertNull(ClientService.phoneHash(null));
    }

    @Test
    void theHashIsTheSameOneTheMigrationWrites() {
        // V8 backfills existing rows with digest(right(digits, 9), 'sha256'),
        // and Java has to produce that same value or every customer who was on
        // the books before today becomes unfindable by their number.
        //
        // The two below are sha256("711111111") and sha256("722222222"),
        // computed outside this codebase so they are a real check on it rather
        // than a copy of its own output.
        assertEquals("1fab07aa7c1b3516c366d9b2d4bb548a42956f61ad8e5583099cb2a25762bb04",
                ClientService.phoneHash("+8801711111111"));
        assertEquals("9c8a68e3279416aaeef61768fcacd7a8c0d3300c036f8aaadaa99ba5f66c88d4",
                ClientService.phoneHash("01722222222"));
    }
}
