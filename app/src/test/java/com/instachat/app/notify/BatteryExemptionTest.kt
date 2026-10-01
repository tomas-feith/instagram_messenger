package com.instachat.app.notify

import org.junit.Assert.assertFalse
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
}
