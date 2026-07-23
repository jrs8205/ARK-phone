package org.jarsi.arkphone.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.1 contrast requirements for the static dark scheme (API 26-30).
 * AAA: normal text >= 7:1, large text >= 4.5:1; non-text UI >= 3:1.
 * Dynamic schemes cannot be checked at build time; the same role pairs keep a
 * Material tone distance >= 60 there, which yields comparable ratios.
 */
class ThemeContrastTest {

    private fun Color.relativeLuminance(): Double {
        fun channel(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val lighter = max(a.relativeLuminance(), b.relativeLuminance())
        val darker = min(a.relativeLuminance(), b.relativeLuminance())
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertContrastAtLeast(expected: Double, foreground: Color, background: Color, label: String) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "$label contrast %.2f is below required %.1f".format(ratio, expected),
            ratio >= expected,
        )
    }

    private val scheme = ArkDarkColorScheme

    @Test
    fun bodyTextMeetsAaa() {
        assertContrastAtLeast(7.0, scheme.onSurface, scheme.surface, "onSurface/surface")
        assertContrastAtLeast(7.0, scheme.onSurfaceVariant, scheme.surface, "onSurfaceVariant/surface")
        assertContrastAtLeast(7.0, scheme.onBackground, scheme.background, "onBackground/background")
    }

    @Test
    fun dialpadFieldAndKeysMeetAaa() {
        assertContrastAtLeast(7.0, scheme.onSurface, scheme.surfaceContainerHigh, "onSurface/surfaceContainerHigh")
        assertContrastAtLeast(
            7.0,
            scheme.onSurfaceVariant,
            scheme.surfaceContainerHigh,
            "onSurfaceVariant/surfaceContainerHigh",
        )
    }

    @Test
    fun dialpadFieldBorderIsVisibleOnBothBackgrounds() {
        assertContrastAtLeast(3.0, scheme.outline, scheme.surface, "outline/surface")
        assertContrastAtLeast(3.0, scheme.outline, scheme.surfaceContainerHigh, "outline/surfaceContainerHigh")
    }

    @Test
    fun buttonsAndBadgesMeetAaaForLargeElements() {
        assertContrastAtLeast(4.5, scheme.onPrimary, scheme.primary, "onPrimary/primary")
        assertContrastAtLeast(4.5, scheme.onPrimaryContainer, scheme.primaryContainer, "onPrimaryContainer/primaryContainer")
        assertContrastAtLeast(7.0, scheme.error, scheme.surface, "error/surface")
        assertContrastAtLeast(4.5, scheme.onSecondary, scheme.secondary, "onSecondary/secondary")
        assertContrastAtLeast(
            4.5,
            scheme.onSecondaryContainer,
            scheme.secondaryContainer,
            "onSecondaryContainer/secondaryContainer",
        )
    }
}
