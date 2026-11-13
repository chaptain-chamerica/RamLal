package com.likkho.app.editor

import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import com.likkho.app.editor.model.normalizeSpans

/**
 * Encodes and decodes rich-text spans to and from clean HTML.
 * Supports bold, italic, underline, strikethrough, text color, and highlight color.
 */
object HtmlSpanCodec {

    fun toHtml(text: String, spans: List<Span>): String {
        if (text.isEmpty()) return ""
        val normalized = normalizeSpans(spans, text.length)
        if (normalized.isEmpty()) {
            return escapeHtml(text).replace("\n", "<br>")
        }

        // Collect all slice boundaries
        val boundaries = mutableSetOf(0, text.length)
        for (sp in normalized) {
            boundaries.add(sp.start.coerceIn(0, text.length))
            boundaries.add(sp.end.coerceIn(0, text.length))
        }
        val sortedPoints = boundaries.sorted()

        val sb = StringBuilder()
        for (i in 0 until sortedPoints.size - 1) {
            val start = sortedPoints[i]
            val end = sortedPoints[i + 1]
            if (start >= end) continue

            val rawSegment = text.substring(start, end)
            var escaped = escapeHtml(rawSegment).replace("\n", "<br>")

            // Find all spans active across [start, end), placing non-heading styles inner and heading outermost
            val active = normalized
                .filter { it.start <= start && it.end >= end }
                .sortedBy { if (it.style is SpanStyle.Heading) 1 else 0 }

            // Wrap with tags in consistent order
            for (sp in active) {
                escaped = when (val s = sp.style) {
                    is SpanStyle.Bold -> "<b>$escaped</b>"
                    is SpanStyle.Italic -> "<i>$escaped</i>"
                    is SpanStyle.Underline -> "<u>$escaped</u>"
                    is SpanStyle.Strike -> "<s>$escaped</s>"
                    is SpanStyle.Color -> "<span style=\"color:${s.hex}\">$escaped</span>"
                    is SpanStyle.Highlight -> "<mark style=\"background-color:${s.hex}\">$escaped</mark>"
                    is SpanStyle.Heading -> "<h${s.level}>$escaped</h${s.level}>"
                    else -> escaped
                }
            }
            sb.append(escaped)
        }

        return sb.toString()
    }

    fun fromHtml(html: String): Pair<String, List<Span>> {
        if (html.isEmpty()) return Pair("", emptyList())

        // Quick check: if no '<', it's pure plain text
        if (!html.contains('<')) {
            val unescaped = unescapeHtml(html)
            return Pair(unescaped, emptyList())
        }

        val plainText = StringBuilder()
        val spans = mutableListOf<Span>()

        data class OpenTag(val style: SpanStyle, val startIndex: Int)
        val stack = mutableListOf<OpenTag>()

        var i = 0
        val len = html.length

        while (i < len) {
            if (html[i] == '<') {
                val closeIdx = html.indexOf('>', i)
                if (closeIdx == -1) {
                    plainText.append(html[i])
                    i++
                    continue
                }

                val tagContent = html.substring(i + 1, closeIdx).trim()
                i = closeIdx + 1

                if (tagContent.startsWith("br", ignoreCase = true)) {
                    plainText.append("\n")
                    continue
                }

                val isClosing = tagContent.startsWith("/")
                val rawTag = if (isClosing) tagContent.substring(1).trim() else tagContent

                val spaceIdx = rawTag.indexOf(' ')
                val tagName = (if (spaceIdx == -1) rawTag else rawTag.substring(0, spaceIdx)).lowercase()

                if (isClosing) {
                    // Match and close the corresponding open tag
                    val matchIdx = stack.indexOfLast {
                        when (it.style) {
                            is SpanStyle.Bold -> tagName == "b" || tagName == "strong"
                            is SpanStyle.Italic -> tagName == "i" || tagName == "em"
                            is SpanStyle.Underline -> tagName == "u"
                            is SpanStyle.Strike -> tagName == "s" || tagName == "strike" || tagName == "del"
                            is SpanStyle.Color -> tagName == "span" || tagName == "font"
                            is SpanStyle.Highlight -> tagName == "mark" || tagName == "span"
                            is SpanStyle.Heading -> tagName == "h${it.style.level}"
                            else -> false
                        }
                    }
                    if (matchIdx != -1) {
                        val open = stack.removeAt(matchIdx)
                        val endIdx = plainText.length
                        if (endIdx > open.startIndex) {
                            spans.add(Span(open.startIndex, endIdx, open.style))
                        }
                    }
                } else {
                    // Opening tag
                    val style = parseTagStyle(tagName, rawTag)
                    if (style != null) {
                        stack.add(OpenTag(style, plainText.length))
                    }
                }
            } else if (html[i] == '&') {
                val semi = html.indexOf(';', i)
                if (semi != -1 && semi - i <= 10) {
                    val entity = html.substring(i, semi + 1)
                    val decoded = decodeEntity(entity)
                    plainText.append(decoded)
                    i = semi + 1
                } else {
                    plainText.append('&')
                    i++
                }
            } else {
                plainText.append(html[i])
                i++
            }
        }

        // Close any remaining unclosed tags
        val endPos = plainText.length
        while (stack.isNotEmpty()) {
            val open = stack.removeAt(stack.size - 1)
            if (endPos > open.startIndex) {
                spans.add(Span(open.startIndex, endPos, open.style))
            }
        }

        val text = plainText.toString()
        val normalized = normalizeSpans(spans, text.length)
        return Pair(text, normalized)
    }

    private fun parseTagStyle(tagName: String, rawTag: String): SpanStyle? {
        return when (tagName) {
            "b", "strong" -> SpanStyle.Bold
            "i", "em" -> SpanStyle.Italic
            "u" -> SpanStyle.Underline
            "s", "strike", "del" -> SpanStyle.Strike
            "mark" -> {
                val hex = extractColorFromStyle(rawTag, "background-color")
                    ?: extractColorFromStyle(rawTag, "background")
                    ?: "#FFFF00"
                SpanStyle.Highlight(hex)
            }
            "span" -> {
                val colorHex = extractColorFromStyle(rawTag, "color")
                val bgHex = extractColorFromStyle(rawTag, "background-color")
                    ?: extractColorFromStyle(rawTag, "background")
                when {
                    colorHex != null -> SpanStyle.Color(colorHex)
                    bgHex != null -> SpanStyle.Highlight(bgHex)
                    else -> null
                }
            }
            "font" -> {
                val colorMatch = Regex("color\\s*=\\s*[\"']?([^\"'\\s>]+)", RegexOption.IGNORE_CASE).find(rawTag)
                colorMatch?.groupValues?.get(1)?.let { SpanStyle.Color(it) }
            }
            "h1" -> SpanStyle.Heading(1)
            "h2" -> SpanStyle.Heading(2)
            "h3" -> SpanStyle.Heading(3)
            else -> null
        }
    }

    private fun extractColorFromStyle(tag: String, property: String): String? {
        val styleAttrMatch = Regex("style\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)
        val styleContent = styleAttrMatch?.groupValues?.get(1) ?: return null
        val propMatch = Regex("$property\\s*:\\s*([^;\"'\\s]+)", RegexOption.IGNORE_CASE).find(styleContent)
        return propMatch?.groupValues?.get(1)?.trim()
    }

    private fun escapeHtml(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    private fun unescapeHtml(text: String): String {
        return text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }

    private fun decodeEntity(entity: String): String = when (entity.lowercase()) {
        "&amp;" -> "&"
        "&lt;" -> "<"
        "&gt;" -> ">"
        "&quot;" -> "\""
        "&#39;", "&apos;" -> "'"
        "&nbsp;" -> " "
        else -> entity
    }
}
