package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow

interface SpeedDialRepository {
    /** Speed dial assignments: keypad digit (2–9) to phone number. */
    val entries: Flow<Map<Int, String>>

    suspend fun set(digit: Int, number: String)

    suspend fun clear(digit: Int)
}
