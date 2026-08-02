package com.gios.lightchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The messages here are real shapes, not invented ones — Apple, Google, banks and every
 * service that has ever texted a code write it in one of about six ways, and the two that
 * matter are "code first" and "code last". The negative cases are the point of the file: a
 * detector that finds codes is easy, and one that declines to find them in a message about a
 * dinner reservation is the whole job.
 */
class LoginCodesTest {

    @Test
    fun `code after the keyword`() {
        assertEquals("481920", LoginCodes.find("Your verification code is 481920"))
        assertEquals("4829", LoginCodes.find("Enter PIN 4829 to continue"))
    }

    @Test
    fun `code before the keyword`() {
        assertEquals("123456", LoginCodes.find("123456 is your Apple ID verification code."))
        assertEquals(
            "902133",
            LoginCodes.find("902133 is your one-time passcode. Do not share it with anyone."),
        )
    }

    @Test
    fun `the webotp line wins without a keyword`() {
        assertEquals("559812", LoginCodes.find("Use this to sign in.\n@example.com #559812"))
        // No `@host`, so it's a hashtag or a numbering, not the convention.
        assertNull(LoginCodes.find("see photo #559812"))
    }

    @Test
    fun `alphanumeric codes survive intact`() {
        assertEquals("G4T7QX", LoginCodes.find("Your login code: G4T7QX"))
        // Case is part of the code. Normalising it would be inventing a different one.
        assertEquals("a1b2c3", LoginCodes.find("Your code is a1b2c3"))
    }

    @Test
    fun `no keyword means no code`() {
        assertNull(LoginCodes.find("Table booked for 730 at Lilia, see you there"))
        assertNull(LoginCodes.find("running late, 15 mins"))
        assertNull(LoginCodes.find("apartment 4b, buzzer 2201"))
    }

    @Test
    fun `the keyword alone is not a code`() {
        // "verify", "please" and "account" are all a plausible code length; only the digit
        // requirement keeps the word that triggered the match from being returned as the code
        // it was announcing.
        assertNull(LoginCodes.find("please verify your account"))
    }

    @Test
    fun `a code ending a sentence is still a code`() {
        // The regression that made the separator rule look one character further: a trailing
        // period is punctuation, not a decimal point.
        assertEquals("481920", LoginCodes.find("Your verification code is 481920."))
    }

    @Test
    fun `the nearest number to the keyword wins`() {
        assertEquals(
            "481920",
            LoginCodes.find("Your code is 481920. Reply STOP to 44398 to opt out."),
        )
    }

    @Test
    fun `long numbers are not codes`() {
        // One token, thirteen characters — it must fail whole rather than surrender a slice.
        assertNull(LoginCodes.find("Your order code 1234567890123 has shipped"))
        assertNull(LoginCodes.find("Tracking code 9400111899223197428490"))
    }

    @Test
    fun `numbers joined to something else are not codes`() {
        assertNull(LoginCodes.find("Your code costs \u00241999.50"))
        assertNull(LoginCodes.find("verification at 10:3045"))
        assertNull(LoginCodes.find("login window 2026.0415"))
    }

    @Test
    fun `pin only matches as a whole word`() {
        assertNull(LoginCodes.find("Your shipping label 4829 is ready"))
        assertNull(LoginCodes.find("Spinning up 4829 instances"))
    }

    @Test
    fun `empty and absent text`() {
        assertNull(LoginCodes.find(null))
        assertNull(LoginCodes.find(""))
        assertNull(LoginCodes.find("   "))
    }

    @Test
    fun `looksLikeLogin agrees with find`() {
        assert(LoginCodes.looksLikeLogin("Your verification code is 481920"))
        assert(!LoginCodes.looksLikeLogin("dinner at 7"))
    }

    @Test
    fun `well formed rejects what the provider must not serve`() {
        assert(LoginCodes.wellFormedCode("481920"))
        assert(LoginCodes.wellFormedCode("G4T7QX"))
        // Too short, too long, and no digit at all.
        assert(!LoginCodes.wellFormedCode("123"))
        assert(!LoginCodes.wellFormedCode("123456789"))
        assert(!LoginCodes.wellFormedCode("PASSWORD"))
    }
}
