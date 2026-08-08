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

    // The eight rows SECURITY-AUDIT.md SEC-011 proved were passing straight
    // through into the summary request and the escalation email.

    @Test
    void aPaymentCardIsMaskedHoweverItIsSpaced() {
        assertEquals("My card is " + PiiMasker.CARD,
                PiiMasker.mask("My card is 4111 1111 1111 1111"));
        assertEquals("Visa " + PiiMasker.CARD + " exp 12/28",
                PiiMasker.mask("Visa 4111111111111111 exp 12/28"));
        assertEquals("CVV 123 and card " + PiiMasker.CARD,
                PiiMasker.mask("CVV 123 and card 5500005555555559"));
    }

    @Test
    void aLongAccountNumberIsMasked() {
        assertEquals("account number " + PiiMasker.ACCOUNT,
                PiiMasker.mask("account number 12345678901234567890"));
    }

    @Test
    void anIbanIsMasked() {
        assertEquals("IBAN " + PiiMasker.ACCOUNT,
                PiiMasker.mask("IBAN GB29NWBK60161331926819"));
    }

    @Test
    void aNumberThatIsNotACardSurvivesEvenAtCardLength() {
        // Sixteen digits that fail the checksum are an order or invoice number.
        // Masking those would leave the agent unable to answer about them.
        String reference = "invoice 4111111111111112";
        assertEquals(PiiMasker.ACCOUNT, PiiMasker.mask(reference).split(" ")[1],
                "16+ digits are still treated as an account, just not as a card");
        assertEquals("order 4471 please", PiiMasker.mask("order 4471 please"));
    }

    @Test
    void nothingIsInventedFromNothing() {
        assertEquals("", PiiMasker.mask(""));
        assertNull(PiiMasker.mask(null));
        assertFalse(PiiMasker.carriesPersonalDetail("we open at eight"));
        assertTrue(PiiMasker.carriesPersonalDetail("call me on 01712345678"));
    }
}
