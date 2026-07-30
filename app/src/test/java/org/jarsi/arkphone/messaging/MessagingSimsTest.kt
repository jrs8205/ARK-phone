package org.jarsi.arkphone.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

class MessagingSimsTest {

    private fun sim(subscriptionId: Int) = MessagingSim(subscriptionId, "SIM $subscriptionId")

    @Test
    fun `a valid android default wins`() {
        assertEquals(5, pickDefaultSubscription(5, listOf(sim(1), sim(5))))
    }

    @Test
    fun `an invalid default falls back to the only sim`() {
        assertEquals(3, pickDefaultSubscription(-1, listOf(sim(3))))
    }

    @Test
    fun `an invalid default with several sims picks none`() {
        assertEquals(-1, pickDefaultSubscription(-1, listOf(sim(1), sim(2))))
    }

    @Test
    fun `no sims at all picks none`() {
        assertEquals(-1, pickDefaultSubscription(-1, emptyList()))
    }
}
