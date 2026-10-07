package org.jarsi.arkphone

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * Every language the app ships is both declared for the Android 13 per-app
 * language picker and actually translated; anything else falls back to English.
 */
@RunWith(RobolectricTestRunner::class)
class LocalesTest {

    private val supported = listOf("en", "fi", "sv", "de", "fr", "es", "et", "ru", "pt", "it", "pl")

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun stringIn(languageTag: String, id: Int): String {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(languageTag))
        return context.createConfigurationContext(config).getString(id)
    }

    @Test
    fun `locale config lists exactly the supported languages`() {
        val parser = context.resources.getXml(R.xml.locales_config)
        val declared = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                declared += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
            }
        }
        assertEquals(supported, declared)
    }

    @Test
    fun `every supported language has its own translation`() {
        val english = stringIn("en", R.string.recents_empty)
        for (tag in supported.filter { it != "en" }) {
            assertNotEquals("$tag still shows English", english, stringIn(tag, R.string.recents_empty))
        }
    }

    @Test
    fun `an unsupported language falls back to English`() {
        assertEquals(stringIn("en", R.string.recents_empty), stringIn("ja", R.string.recents_empty))
    }
}
