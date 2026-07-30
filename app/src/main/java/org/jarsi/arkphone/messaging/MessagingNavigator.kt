package org.jarsi.arkphone.messaging

import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.ui.conversation.ConversationActivity
import javax.inject.Inject
import javax.inject.Singleton

/** Opens the in-app conversation for a number, resolving its thread first. */
@Singleton
class MessagingNavigator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    fun openConversation(context: Context, number: String) =
        openConversation(context, listOf(number))

    /** Several recipients resolve to the shared group thread. */
    fun openConversation(context: Context, numbers: List<String>) {
        val recipients = numbers.filter { it.isNotBlank() }
        if (recipients.isEmpty()) return
        scope.launch {
            val threadId = withContext(ioDispatcher) {
                runCatching {
                    if (recipients.size == 1) {
                        Telephony.Threads.getOrCreateThreadId(appContext, recipients.single())
                    } else {
                        Telephony.Threads.getOrCreateThreadId(appContext, recipients.toSet())
                    }
                }.getOrNull()
            } ?: return@launch
            runCatching {
                context.startActivity(ConversationActivity.intent(context, threadId))
            }
        }
    }
}
