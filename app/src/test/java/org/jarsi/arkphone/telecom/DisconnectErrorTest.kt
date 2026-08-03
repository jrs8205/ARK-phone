package org.jarsi.arkphone.telecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisconnectErrorTest {

    @Test
    fun nonErrorCausesProduceNothing() {
        assertNull(disconnectErrorOf(isError = false, label = "Radio off", description = "Details"))
    }

    @Test
    fun blankTextsProduceNothing() {
        assertNull(disconnectErrorOf(isError = true, label = " ", description = null))
    }

    @Test
    fun keepsLabelAndDescription() {
        assertEquals(
            DisconnectError("Radio pois käytöstä", "Poista lentokonetila käytöstä."),
            disconnectErrorOf(
                isError = true,
                label = "Radio pois käytöstä",
                description = "Poista lentokonetila käytöstä.",
            ),
        )
    }

    @Test
    fun aLoneFieldIsEnough() {
        assertEquals(
            DisconnectError(null, "Verkkovirhe."),
            disconnectErrorOf(isError = true, label = null, description = "Verkkovirhe."),
        )
        assertEquals(
            DisconnectError("Verkkovirhe", null),
            disconnectErrorOf(isError = true, label = "Verkkovirhe", description = " "),
        )
    }
}
