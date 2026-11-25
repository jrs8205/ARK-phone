package org.jarsi.arkphone

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    private fun stringIn(languageTag: String, id: Int, vararg formatArgs: Any): String {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(languageTag))
        return context.createConfigurationContext(config).getString(id, *formatArgs)
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

    /** Every surface a hidden or unknown caller reaches: the spoken
     *  announcement, the in-call screen, the notification and the log. */
    private val unknownCallerStrings = mapOf(
        "announce_unknown_caller" to R.string.announce_unknown_caller,
        "announce_whatsapp_unknown_caller" to R.string.announce_whatsapp_unknown_caller,
        "incall_unknown_number" to R.string.incall_unknown_number,
        "incall_unknown_caller" to R.string.incall_unknown_caller,
        "call_log_unknown_caller" to R.string.call_log_unknown_caller,
    )

    @Test
    fun `an unknown caller is named in every supported language`() {
        for ((name, id) in unknownCallerStrings) {
            val english = stringIn("en", id)
            assertTrue("$name is blank in English", english.isNotBlank())
            for (tag in supported.filter { it != "en" }) {
                val translated = stringIn(tag, id)
                assertTrue("$name is blank in $tag", translated.isNotBlank())
                assertNotEquals("$name still shows English in $tag", english, translated)
            }
        }
    }

    @Test
    fun `a named caller is announced in every supported language`() {
        val english = stringIn("en", R.string.announce_caller, "Jarsi")
        assertTrue(english.contains("Jarsi"))
        for (tag in supported.filter { it != "en" }) {
            val translated = stringIn(tag, R.string.announce_caller, "Jarsi")
            assertTrue("announce_caller drops the name in $tag", translated.contains("Jarsi"))
            assertNotEquals("announce_caller still shows English in $tag", english, translated)
        }
    }

    @Test
    fun `return to call includes the caller in every language`() {
        val english = stringIn("en", R.string.return_to_call, "Ada")
        for (tag in supported) {
            val label = stringIn(tag, R.string.return_to_call, "Ada")
            assertTrue("caller missing in $tag", label.contains("Ada"))
            if (tag != "en") assertNotEquals("English fallback in $tag", english, label)
        }
    }

    @Test
    fun `the Finnish announcements read as the family hears them`() {
        assertEquals("Tuntematon soittaja soittaa", stringIn("fi", R.string.announce_unknown_caller))
        assertEquals("Jarsi soittaa", stringIn("fi", R.string.announce_caller, "Jarsi"))
    }
}
