package org.jarsi.arkphone.ui.conversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MessageLinkifierTest {

    private lateinit var defaultLocale: Locale

    @Before
    fun pinFinnishLocale() {
        defaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("fi-FI"))
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun findsAnHttpsUrlWithItsExactRange() {
        val body = "Katso https://yle.fi/a/123 illalla"
        val links = MessageLinkifier.detect(body)
        assertEquals(1, links.size)
        assertEquals("https://yle.fi/a/123", links[0].url)
        assertEquals("https://yle.fi/a/123", body.substring(links[0].start, links[0].end))
    }

    @Test
    fun aBareDomainGetsAnHttpsScheme() {
        val links = MessageLinkifier.detect("Uutiset löytyy osoitteesta yle.fi tänään")
        assertEquals(1, links.size)
        assertEquals("https://yle.fi", links[0].url)
    }

    @Test
    fun anExplicitHttpSchemeIsPreserved() {
        val links = MessageLinkifier.detect("vanha sivu http://old.example.com toimii yhä")
        assertEquals(1, links.size)
        assertEquals("http://old.example.com", links[0].url)
    }

    @Test
    fun aWwwAddressGetsAnHttpsScheme() {
        val links = MessageLinkifier.detect("käy www.is.fi sivulla")
        assertEquals(1, links.size)
        assertEquals("https://www.is.fi", links[0].url)
    }

    @Test
    fun findsAnEmailAddress() {
        val links = MessageLinkifier.detect("laita postia matti@example.com kiitos")
        assertEquals(1, links.size)
        assertEquals("mailto:matti@example.com", links[0].url)
    }

    @Test
    fun findsAnInternationalPhoneNumber() {
        val links = MessageLinkifier.detect("soita +358 40 123 4567 huomenna")
        assertEquals(1, links.size)
        assertEquals("tel:+358401234567", links[0].url)
    }

    @Test
    fun findsANationalPhoneNumber() {
        val links = MessageLinkifier.detect("numero on 040 1234567")
        assertEquals(1, links.size)
        assertEquals("tel:0401234567", links[0].url)
    }

    @Test
    fun aClockTimeIsNotALink() {
        assertEquals(emptyList<LinkSpan>(), MessageLinkifier.detect("Nähdään klo 18.30 torilla"))
    }

    @Test
    fun aDateIsNotALink() {
        assertEquals(emptyList<LinkSpan>(), MessageLinkifier.detect("Palaveri siirtyy 1.8.2026 aamuun"))
    }

    @Test
    fun aZeroPaddedDateIsNotALink() {
        assertEquals(
            emptyList<LinkSpan>(),
            MessageLinkifier.detect("Palaveri siirtyy 01.08.2026 aamuun"),
        )
    }

    @Test
    fun anUppercaseHttpSchemeIsNotRewrittenToHttps() {
        val links = MessageLinkifier.detect("vanha sivu HTTP://old.example.com toimii")
        assertEquals(1, links.size)
        assertTrue(links[0].url.startsWith("http://", ignoreCase = true))
    }

    @Test
    fun plainTextHasNoLinks() {
        assertEquals(emptyList<LinkSpan>(), MessageLinkifier.detect("Moro, tuutko syömään?"))
    }

    @Test
    fun multipleLinksComeBackInTextOrder() {
        val body = "soita 040 1234567 tai katso https://yle.fi ja maila matti@example.com"
        val links = MessageLinkifier.detect(body)
        assertEquals(3, links.size)
        assertTrue(links[0].url.startsWith("tel:"))
        assertTrue(links[1].url.startsWith("https:"))
        assertTrue(links[2].url.startsWith("mailto:"))
        assertTrue(links[0].start < links[1].start)
        assertTrue(links[1].start < links[2].start)
    }
}
