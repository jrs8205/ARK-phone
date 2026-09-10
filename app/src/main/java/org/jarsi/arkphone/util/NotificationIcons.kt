package org.jarsi.arkphone.util

import android.os.Bundle
import androidx.core.app.NotificationCompat

/**
 * `android.app.Notification.EXTRA_PREFER_SMALL_ICON`, an API 37 constant this
 * compileSdk (36) cannot name. Android 17 draws the launcher icon on the
 * notification row and keeps the small icon for the status bar alone; ARK's
 * launcher icon is a speech bubble, so every missed call read as a text
 * message (field report 2026-09-10). Older releases ignore the extra.
 */
const val EXTRA_PREFER_SMALL_ICON = "android.app.preferSmallIcon"

/** Asks the row for the small icon — handset or message bubble — over the launcher icon. */
fun NotificationCompat.Builder.preferSmallIcon(): NotificationCompat.Builder =
    addExtras(Bundle().apply { putBoolean(EXTRA_PREFER_SMALL_ICON, true) })
