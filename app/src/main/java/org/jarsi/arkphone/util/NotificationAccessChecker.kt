package org.jarsi.arkphone.util

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

fun interface NotificationAccessChecker {
    fun hasAccess(): Boolean
}

class AndroidNotificationAccessChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationAccessChecker {
    override fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
