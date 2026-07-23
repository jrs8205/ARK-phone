package org.jarsi.arkphone.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Fixed call-action colors, independent of the theme, like in stock dialers. */
val CallAnswerGreen = Color(0xFF188038)
val CallDeclineRed = Color(0xFFD32F2F)

/** Fallback scheme for devices without dynamic color (API 26-30). */
internal val ArkDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD0E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF263141),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
)

@Composable
fun ArkPhoneTheme(content: @Composable () -> Unit) {
    val sdkInt = Build.VERSION.SDK_INT
    val darkTheme = useDarkTheme(systemDark = isSystemInDarkTheme(), sdkInt = sdkInt)
    val context = LocalContext.current
    val colorScheme = when {
        isDynamicColorAvailable(sdkInt) && darkTheme -> dynamicDarkColorScheme(context)
        isDynamicColorAvailable(sdkInt) -> dynamicLightColorScheme(context)
        else -> ArkDarkColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = ArkTypography, content = content)
}
