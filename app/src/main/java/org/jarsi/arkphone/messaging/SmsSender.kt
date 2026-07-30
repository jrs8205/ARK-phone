package org.jarsi.arkphone.messaging

import android.net.Uri

/** Sends one text to one recipient with the device's default messaging SIM
 *  and records it in the provider. */
interface SmsSender {
    /** Returns the provider row URI, or null when the send could not start. */
    suspend fun send(address: String, body: String): Uri?

    /** Removes a failed outgoing row so its resend does not duplicate it. */
    suspend fun discardFailed(messageId: Long)
}
