package com.gios.lightchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two version shapes actually in play: a plain release tag ("2.36.0") on one side,
 * and either another plain tag or this build's own four-segment CI-stamped version
 * ("2.35.0.42") on the other.
 */
class UpdaterTest {

    @Test
    fun `a higher component anywhere makes it newer`() {
        assertTrue(Updater.isNewer("2.36.0", "2.35.0"))
        assertTrue(Updater.isNewer("3.0.0", "2.99.0"))
        assertTrue(Updater.isNewer("2.35.1", "2.35.0"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(Updater.isNewer("2.35.0", "2.35.0"))
    }

    @Test
    fun `a lower component anywhere makes it not newer`() {
        assertFalse(Updater.isNewer("2.35.0", "2.36.0"))
        assertFalse(Updater.isNewer("2.34.9", "2.35.0"))
    }

    @Test
    fun `missing trailing components count as zero`() {
        // A plain release tag against this build's own CI-stamped four-segment version.
        assertTrue(Updater.isNewer("2.36.0", "2.35.0.42"))
        assertFalse(Updater.isNewer("2.35.0", "2.35.0.42"))
        assertTrue(Updater.isNewer("2.35.0.43", "2.35.0.42"))
    }

    @Test
    fun `non-numeric junk in a component is treated as zero rather than crashing`() {
        assertFalse(Updater.isNewer("2.35.0-beta", "2.35.0"))
    }
}
