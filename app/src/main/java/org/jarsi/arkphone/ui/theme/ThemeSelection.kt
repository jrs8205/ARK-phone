package org.jarsi.arkphone.ui.theme

fun isDynamicColorAvailable(sdkInt: Int): Boolean = sdkInt >= 31

/** Below API 31 the app is always dark; with dynamic color it follows the system setting. */
fun useDarkTheme(systemDark: Boolean, sdkInt: Int): Boolean =
    if (isDynamicColorAvailable(sdkInt)) systemDark else true
