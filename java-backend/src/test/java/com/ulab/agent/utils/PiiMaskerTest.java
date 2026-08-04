package com.ulab.agent.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The masker has two ways of being wrong, and both matter.
 *
 * Letting a national ID number through is the obvious one. The quieter one is
 * masking a number that was never personal — an order number, a year, a
 * quantity — because a model that cannot see any numbers cannot answer the
 * question it was asked.
 */
class PiiMaskerTest {

    @Test
    void aNationalIdNumberIsMaskedInEitherLength() {
        assertEquals("My NID is " + PiiMasker.NID, PiiMasker.mask("My NID is 1234567890"));
        assertEquals("My NID is " + PiiMasker.NID, PiiMasker.mask("My NID is 1990123456789"));
        assertEquals("My NID is " + PiiMasker.NID,
                PiiMasker.mask("My NID is 19901234567890123"));
    }

    @Test
    void aNationalIdNumberIsMaskedInBengaliDigits() {
        assertEquals("আমার এনআইডি " + PiiMasker.NID, PiiMasker.mask("আমার এনআইডি ১৯৯০১২৩৪৫৬৭৮৯"));
    }

    @Test
    void aPhoneNumberIsMaskedHoweverItIsWritten() {
        assertEquals(PiiMasker.PHONE, PiiMasker.mask("01712345678"));
        assertEquals(PiiMasker.PHONE, PiiMasker.mask("+8801712345678"));
        assertEquals(PiiMasker.PHONE, PiiMasker.mask("017-1234-5678"));
        assertEquals("আমার নম্বর " + PiiMasker.PHONE, PiiMasker.mask("আমার নম্বর ০১৭১২৩৪৫৬৭৮"));
    }

    @Test
    void anEmailAddressIsMasked() {
        assertEquals("write to " + PiiMasker.EMAIL,
                PiiMasker.mask("write to nanjiba.rahman@example.com"));
    }

    @Test
    void anAmountIsMaskedWithItsCurrencyOnEitherSide() {
        assertEquals("I paid " + PiiMasker.AMOUNT, PiiMasker.mask("I paid 2,500 taka"));
        assertEquals("I paid " + PiiMasker.AMOUNT, PiiMasker.mask("I paid ৳2500"));
        assertEquals("balance " + PiiMasker.AMOUNT, PiiMasker.mask("balance 1500 টাকা"));
        assertEquals("about " + PiiMasker.AMOUNT + " left", PiiMasker.mask("about $40.50 left"));
    }

    @Test
    void anOrdinaryNumberIsLeftAlone() {
        assertEquals("order 4471 please", PiiMasker.mask("order 4471 please"),
                "a short number is nobody's identity");
        assertEquals("booked for 2026-08-04", PiiMasker.mask("booked for 2026-08-04"),
                "a date must survive, or the agent cannot answer about it");
        assertEquals("I need 3 of them", PiiMasker.mask("I need 3 of them"));
    }

    @Test
    void severalDetailsInOneSentenceAreAllMasked() {
        String masked = PiiMasker.mask(
                "I am 01712345678, NID 1990123456789, email me at a.b@c.com");

        assertTrue(masked.contains(PiiMasker.PHONE));
        assertTrue(masked.contains(PiiMasker.NID));
        assertTrue(masked.contains(PiiMasker.EMAIL));
        assertFalse(masked.contains("1990123456789"));
    }

    @Test
    void nothingIsInventedFromNothing() {
        assertEquals("", PiiMasker.mask(""));
        assertNull(PiiMasker.mask(null));
        assertFalse(PiiMasker.carriesPersonalDetail("we open at eight"));
        assertTrue(PiiMasker.carriesPersonalDetail("call me on 01712345678"));
    }
}
