package com.gios.lightchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handles here are the shapes BlueBubbles actually returns: E.164 from the server,
 * human-written variants from the Mac's address book, and Apple IDs, which are the case the
 * whole check exists for.
 */
class DialerTest {

    @Test
    fun `phone numbers are callable however they are written`() {
        assertTrue(Dialer.callable("+13152122695"))
        assertTrue(Dialer.callable("6082646591"))
        assertTrue(Dialer.callable("+1 (315) 212-2695"))
        assertTrue(Dialer.callable("608-264-6591"))
        // Leading and trailing space is routine in an address-book field.
        assertTrue(Dialer.callable("  +13152122695  "))
    }

    @Test
    fun `email handles are never callable`() {
        assertFalse(Dialer.callable("gman6849@gmail.com"))
        assertFalse(Dialer.callable("g.lupo@lrparis.com"))
        // The case that motivates checking for the @ before checking for digits: an Apple ID
        // with numbers in it has plenty of digits and is still not a number.
        assertFalse(Dialer.callable("basil2019@icloud.com"))
    }

    @Test
    fun `letters mean it is not a number written oddly`() {
        assertFalse(Dialer.callable("VERIFY"))
        assertFalse(Dialer.callable("Apple"))
        assertFalse(Dialer.callable("1-800-FLOWERS"))
    }

    @Test
    fun `short codes reach the keypad`() {
        // The number that sent a verification code is one worth being able to look at, and
        // DIAL only ever fills the keypad in.
        assertTrue(Dialer.callable("22395"))
        assertTrue(Dialer.callable("611"))
    }

    @Test
    fun `too few digits to be a number`() {
        assertFalse(Dialer.callable(""))
        assertFalse(Dialer.callable("   "))
        assertFalse(Dialer.callable("+"))
        assertFalse(Dialer.callable("12"))
    }
}
