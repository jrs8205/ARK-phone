package org.jarsi.arkphone.data

import org.jarsi.arkphone.data.model.SimAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimAccountsTest {

    private val sim1 = SimAccount(id = "sub-1", label = "DNA", number = "+358401234567")
    private val sim2 = SimAccount(id = "sub-2", label = "Elisa", number = null)

    @Test
    fun theChosenAccountIsUsedWhenItIsStillAvailable() {
        assertEquals(sim2, selectedSimAccount("sub-2", listOf(sim1, sim2)))
    }

    @Test
    fun aRemovedAccountFallsBackToTheSystemDefault() {
        assertNull(selectedSimAccount("sub-3", listOf(sim1, sim2)))
    }

    @Test
    fun noChoiceMeansTheSystemDefault() {
        assertNull(selectedSimAccount(null, listOf(sim1, sim2)))
    }

    @Test
    fun theSimLabelIsFoundByAccountId() {
        assertEquals("Elisa", simLabelFor("sub-2", listOf(sim1, sim2)))
    }

    @Test
    fun anUnknownOrMissingAccountHasNoLabel() {
        assertNull(simLabelFor("sub-9", listOf(sim1, sim2)))
        assertNull(simLabelFor(null, listOf(sim1, sim2)))
    }

    @Test
    fun labelsAreHiddenUntilThereIsMoreThanOneSim() {
        assertNull(simLabelFor("sub-1", listOf(sim1)))
        assertEquals("DNA", simLabelFor("sub-1", listOf(sim1, sim2)))
    }

    @Test
    fun anAccountLabelCombinesTheNameAndNumber() {
        assertEquals("DNA (+358401234567)", sim1.labelWithNumber)
        assertEquals("Elisa", sim2.labelWithNumber)
    }
}
