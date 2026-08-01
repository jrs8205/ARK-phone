package org.jarsi.arkphone.ui.conversation

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StyledMessageBodyTest {

    private val underline = SpanStyle(textDecoration = TextDecoration.Underline)

    @Test
    fun aDetectedLinkGetsTheStyleOverItsExactRange() {
        val body = "Katso https://yle.fi/a/1 nyt"
        val result = styledMessageBody(body, MessageLinkifier.detect(body), underline)
        assertEquals(body, result.text)
        val styles = result.spanStyles
        assertEquals(1, styles.size)
        assertEquals(underline, styles[0].item)
        assertEquals("https://yle.fi/a/1", body.substring(styles[0].start, styles[0].end))
    }

    @Test
    fun noLinksMeansAPlainString() {
        val result = styledMessageBody("Moro, tuutko syömään?", emptyList(), underline)
        assertTrue(result.spanStyles.isEmpty())
    }
}
