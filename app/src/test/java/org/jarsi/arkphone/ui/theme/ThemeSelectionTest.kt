package org.jarsi.arkphone.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSelectionTest {

    @Test
    fun dynamicColorRequiresApi31() {
        assertFalse(isDynamicColorAvailable(sdkInt = 30))
        assertTrue(isDynamicColorAvailable(sdkInt = 31))
    }

    @Test
    fun oldDevicesAreAlwaysDark() {
        assertTrue(useDarkTheme(systemDark = false, sdkInt = 26))
        assertTrue(useDarkTheme(systemDark = true, sdkInt = 30))
    }

    @Test
    fun dynamicDevicesFollowSystemSetting() {
        assertFalse(useDarkTheme(systemDark = false, sdkInt = 31))
        assertTrue(useDarkTheme(systemDark = true, sdkInt = 34))
    }
}
