package com.instachat.app.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryExemptionTest {
    @Test
    fun `asks the first time the exemption is missing`() {
        assertTrue(shouldAskForExemption(exempt = false, askedBefore = false, wasExempt = false))
    }

    @Test
    fun `does not ask again after a refusal`() {
        assertFalse(shouldAskForExemption(exempt = false, askedBefore = true, wasExempt = false))
    }

    @Test
    fun `asks again when an exemption the app had is lost`() {
        assertTrue(shouldAskForExemption(exempt = false, askedBefore = true, wasExempt = true))
    }

    @Test
    fun `never asks while exempt`() {
        assertFalse(shouldAskForExemption(exempt = true, askedBefore = false, wasExempt = false))
        assertFalse(shouldAskForExemption(exempt = true, askedBefore = true, wasExempt = true))
    }

    @Test
    fun `notifications being off outranks the exemption`() {
        assertEquals(
            Blocker.NOTIFICATIONS_OFF,
            blockerOf(notificationsEnabled = false, exempt = false),
        )
        assertEquals(Blocker.NOT_EXEMPT, blockerOf(notificationsEnabled = true, exempt = false))
        assertNull(blockerOf(notificationsEnabled = true, exempt = true))
    }

    @Test
    fun `a refusal leaves a banner rather than nothing`() {
        assertEquals(Blocker.NOT_EXEMPT, bannerFor(Blocker.NOT_EXEMPT, dismissed = null))
    }

    @Test
    fun `a dismissed banner stays hidden while the same problem lasts`() {
        assertNull(bannerFor(Blocker.NOT_EXEMPT, dismissed = Blocker.NOT_EXEMPT))
        assertEquals(Blocker.NOT_EXEMPT, dismissalAfter(Blocker.NOT_EXEMPT, Blocker.NOT_EXEMPT))
    }

    @Test
    fun `a different problem shows despite an earlier dismissal`() {
        assertEquals(
            Blocker.NOTIFICATIONS_OFF,
            bannerFor(Blocker.NOTIFICATIONS_OFF, Blocker.NOT_EXEMPT),
        )
    }

    @Test
    fun `a dismissal is forgotten once the problem clears`() {
        val kept = dismissalAfter(blocker = null, dismissed = Blocker.NOT_EXEMPT)
        assertNull(kept)
        assertEquals(Blocker.NOT_EXEMPT, bannerFor(Blocker.NOT_EXEMPT, kept))
    }
}
