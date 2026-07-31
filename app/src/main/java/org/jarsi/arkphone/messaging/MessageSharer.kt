package org.jarsi.arkphone.messaging

import android.content.Intent
import org.jarsi.arkphone.data.model.Message

interface MessageSharer {
    /** A share intent for the given messages, or null when nothing is shareable. */
    suspend fun shareIntent(messages: List<Message>): Intent?
}
