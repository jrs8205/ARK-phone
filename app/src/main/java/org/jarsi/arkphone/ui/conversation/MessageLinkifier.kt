package org.jarsi.arkphone.ui.conversation

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString

data class LinkSpan(val start: Int, val end: Int, val url: String)

// Styling only — tap handling stays in MessageBubble's own gesture detector so that
// long-press keeps starting selection and non-link taps keep reaching the bubble.
fun styledMessageBody(body: String, links: List<LinkSpan>, style: SpanStyle): AnnotatedString {
    if (links.isEmpty()) return AnnotatedString(body)
    return buildAnnotatedString {
        append(body)
        links.forEach { addStyle(style, it.start, it.end) }
    }
}

fun linkAt(layout: TextLayoutResult, links: List<LinkSpan>, position: Offset): LinkSpan? {
    val offset = layout.getOffsetForPosition(position)
    links.firstOrNull { offset >= it.start && offset < it.end }?.let { return it }
    // getOffsetForPosition returns a cursor slot, so a tap on the trailing half of a
    // link's last glyph lands one past the range; fall back to the glyph boxes.
    return links.firstOrNull { link ->
        val startLine = layout.getLineForOffset(link.start)
        val endLine = layout.getLineForOffset(link.end - 1)
        (startLine..endLine).any { line ->
            val from = maxOf(link.start, layout.getLineStart(line))
            val to = minOf(link.end - 1, layout.getLineEnd(line, visibleEnd = true) - 1)
            if (to < from) return@any false
            val left = minOf(layout.getBoundingBox(from).left, layout.getBoundingBox(to).left)
            val right = maxOf(layout.getBoundingBox(from).right, layout.getBoundingBox(to).right)
            position.x >= left && position.x <= right &&
                position.y >= layout.getLineTop(line) && position.y <= layout.getLineBottom(line)
        }
    }
}

object MessageLinkifier {

    fun detect(body: String): List<LinkSpan> {
        if (body.isBlank()) return emptyList()
        val spannable = SpannableString(body)
        Linkify.addLinks(
            spannable,
            Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS,
        )
        return spannable.getSpans(0, spannable.length, URLSpan::class.java)
            .mapNotNull { span ->
                val start = spannable.getSpanStart(span)
                val end = spannable.getSpanEnd(span)
                val text = body.substring(start, end)
                LinkSpan(start, end, normalize(span.url, text)).takeIf { keep(it.url, text) }
            }
            .sortedBy { it.start }
    }

    // Linkify prepends plain http:// when the text had no scheme; upgrade those to https.
    private fun normalize(url: String, text: String): String =
        if (url.startsWith("http://") && !text.startsWith("http://", ignoreCase = true)) {
            "https://" + url.removePrefix("http://")
        } else {
            url
        }

    private val dateLike = Regex("""\d{1,2}\.\d{1,2}\.\d{2,4}""")

    // Leniency.POSSIBLE linkifies date- and time-like digit runs; real Finnish
    // numbers have at least five digits, start with 0 or +, and are not
    // written in the dotted date shape ("01.08.2026" would otherwise pass).
    private fun keep(url: String, text: String): Boolean {
        if (!url.startsWith("tel:")) return true
        if (dateLike.matches(text)) return false
        val number = url.removePrefix("tel:")
        return number.count { it.isDigit() } >= 5 &&
            (number.startsWith("+") || number.startsWith("0"))
    }
}
