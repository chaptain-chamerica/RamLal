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
        val hasListSpans = normalized.any {
            it.style is SpanStyle.Bullet || it.style is SpanStyle.Numbered || it.style is SpanStyle.Todo
        }
        if (!hasListSpans) {
            return renderRangeToHtml(text, normalized, 0, text.length)
        }

        // Line-based rendering when list items exist so <ul>/<ol>/checklist tags stay well-formed
        val lines = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start <= text.length) {
            var end = start
            while (end < text.length && text[end] != '\n') end++
            lines.add(start to end)
            if (end >= text.length) break
            start = end + 1
        }

        val sb = StringBuilder()
        var idx = 0
        while (idx < lines.size) {
            if (idx > 0) sb.append("<br>")
            val (ls, le) = lines[idx]
            val prefixSpan = normalized.firstOrNull {
                it.start == ls && it.end <= le &&
                    (it.style is SpanStyle.Bullet || it.style is SpanStyle.Numbered || it.style is SpanStyle.Todo)
            }
            if (prefixSpan == null) {
                sb.append(renderRangeToHtml(text, normalized, ls, le))
                idx++
                continue
            }

            when (prefixSpan.style) {
                is SpanStyle.Bullet -> {
                    sb.append("<ul>")
                    while (idx < lines.size) {
                        val (cls, cle) = lines[idx]
                        val ps = normalized.firstOrNull {
                            it.start == cls && it.end <= cle && it.style is SpanStyle.Bullet
                        } ?: break
                        val content = renderRangeToHtml(text, normalized, ps.end, cle)
                        sb.append("<li>").append(content).append("</li>")
                        idx++
                    }
                    sb.append("</ul>")
                }
                is SpanStyle.Numbered -> {
                    sb.append("<ol>")
                    while (idx < lines.size) {
                        val (cls, cle) = lines[idx]
                        val ps = normalized.firstOrNull {
                            it.start == cls && it.end <= cle && it.style is SpanStyle.Numbered
                        } ?: break
                        val content = renderRangeToHtml(text, normalized, ps.end, cle)
                        sb.append("<li>").append(content).append("</li>")
                        idx++
                    }
                    sb.append("</ol>")
                }
                is SpanStyle.Todo -> {
                    sb.append("<ul data-checklist=\"true\">")
                    while (idx < lines.size) {
                        val (cls, cle) = lines[idx]
                        val ps = normalized.firstOrNull {
                            it.start == cls && it.end <= cle && it.style is SpanStyle.Todo
                        } ?: break
                        val todoStyle = ps.style as SpanStyle.Todo
                        val content = renderRangeToHtml(text, normalized, ps.end, cle)
                        sb.append("<li data-checked=\"${todoStyle.checked}\">").append(content).append("</li>")
                        idx++
                    }
                    sb.append("</ul>")
                }
                else -> idx++
            }
        }
        return sb.toString()
    }

    private fun renderRangeToHtml(
        text: String,
        normalized: List<Span>,
        rangeStart: Int,
        rangeEnd: Int
    ): String {
        if (rangeStart >= rangeEnd) return ""
        val rangeSpans = normalized.filter {
            it.end > rangeStart && it.start < rangeEnd &&
                it.style !is SpanStyle.Bullet && it.style !is SpanStyle.Numbered && it.style !is SpanStyle.Todo
        }
        if (rangeSpans.isEmpty()) {
            return escapeHtml(text.substring(rangeStart, rangeEnd)).replace("\n", "<br>")
        }

        val boundaries = mutableSetOf(rangeStart, rangeEnd)
        for (sp in rangeSpans) {
            boundaries.add(sp.start.coerceIn(rangeStart, rangeEnd))
            boundaries.add(sp.end.coerceIn(rangeStart, rangeEnd))
        }
        val sortedPoints = boundaries.sorted()

        val sb = StringBuilder()
        for (i in 0 until sortedPoints.size - 1) {
            val start = sortedPoints[i]
            val end = sortedPoints[i + 1]
            if (start >= end) continue

            val rawSegment = text.substring(start, end)
            var escaped = escapeHtml(rawSegment).replace("\n", "<br>")

            val active = rangeSpans
                .filter { it.start <= start && it.end >= end }
                .sortedBy {
                    when (it.style) {
                        is SpanStyle.Heading -> 3
                        is SpanStyle.Quote -> 2
                        is SpanStyle.Code -> 1
                        else -> 0
                    }
                }

            for (sp in active) {
                escaped = when (val s = sp.style) {
                    is SpanStyle.Bold -> "<b>$escaped</b>"
                    is SpanStyle.Italic -> "<i>$escaped</i>"
                    is SpanStyle.Underline -> "<u>$escaped</u>"
                    is SpanStyle.Strike -> "<s>$escaped</s>"
                    is SpanStyle.Color -> "<span style=\"color:${s.hex}\">$escaped</span>"
                    is SpanStyle.Highlight -> "<mark style=\"background-color:${s.hex}\">$escaped</mark>"
                    is SpanStyle.FontId -> "<span data-font=\"${escapeHtml(s.fontId)}\">$escaped</span>"
                    is SpanStyle.SizePx -> "<span style=\"font-size:${s.sizePx.toInt()}px\">$escaped</span>"
                    is SpanStyle.Code -> "<code>$escaped</code>"
                    is SpanStyle.Quote -> "<blockquote>$escaped</blockquote>"
                    is SpanStyle.Heading -> "<h${s.level}>$escaped</h${s.level}>"
                    is SpanStyle.Divider -> "<hr>"
                    else -> escaped
                }
            }
            sb.append(escaped)
        }

        return sb.toString()
    }

    private enum class HtmlListMode { UL, OL, CHECKLIST }

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

        var currentListMode: HtmlListMode? = null
        var listHasEmittedItem = false
        var olCounter = 0

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
                val tagName = (if (spaceIdx == -1) rawTag else rawTag.substring(0, spaceIdx)).trimEnd('/').lowercase()

                if (!isClosing && tagName == "hr") {
                    val markerPos = plainText.length
                    plainText.append(RichTextEditorState.DIVIDER_MARKER)
                    spans.add(Span(markerPos, markerPos + 1, SpanStyle.Divider))
                    continue
                }

                if (!isClosing && tagName == "ul") {
                    val isChecklist = extractAttr(rawTag, "data-checklist").equals("true", ignoreCase = true) ||
                        extractAttr(rawTag, "data-type").equals("checklist", ignoreCase = true)
                    currentListMode = if (isChecklist) HtmlListMode.CHECKLIST else HtmlListMode.UL
                    listHasEmittedItem = false
                    olCounter = 0
                    continue
                }

                if (!isClosing && tagName == "ol") {
                    currentListMode = HtmlListMode.OL
                    listHasEmittedItem = false
                    olCounter = 0
                    continue
                }

                if (isClosing && (tagName == "ul" || tagName == "ol")) {
                    currentListMode = null
                    listHasEmittedItem = false
                    olCounter = 0
                    continue
                }

                if (!isClosing && tagName == "li") {
                    if (listHasEmittedItem || (plainText.isNotEmpty() && plainText[plainText.length - 1] != '\n')) {
                        plainText.append('\n')
                    }
                    listHasEmittedItem = true
                    val checkedAttr = extractAttr(rawTag, "data-checked")
                    val pStart = plainText.length
                    when {
                        currentListMode == HtmlListMode.CHECKLIST || checkedAttr != null -> {
                            val checked = checkedAttr.equals("true", ignoreCase = true)
                            val prefix = if (checked) {
                                RichTextEditorState.TODO_CHECKED_PREFIX
                            } else {
                                RichTextEditorState.TODO_UNCHECKED_PREFIX
                            }
                            plainText.append(prefix)
                            spans.add(Span(pStart, plainText.length, SpanStyle.Todo(checked)))
                        }
                        currentListMode == HtmlListMode.OL -> {
                            olCounter++
                            val prefix = "$olCounter. "
                            plainText.append(prefix)
                            spans.add(Span(pStart, plainText.length, SpanStyle.Numbered))
                        }
                        else -> {
                            plainText.append(RichTextEditorState.BULLET_PREFIX)
                            spans.add(Span(pStart, plainText.length, SpanStyle.Bullet))
                        }
                    }
                    continue
                }

                if (isClosing && tagName == "li") {
                    continue
                }

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
                            is SpanStyle.FontId -> tagName == "span" || tagName == "font"
                            is SpanStyle.SizePx -> tagName == "span"
                            is SpanStyle.Quote -> tagName == "blockquote"
                            is SpanStyle.Code -> tagName == "code"
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
                val dataFont = extractAttr(rawTag, "data-font")
                val colorHex = extractColorFromStyle(rawTag, "color")
                val fontSizeCss = extractColorFromStyle(rawTag, "font-size")
                val parsedPx = fontSizeCss
                    ?.lowercase()
                    ?.removeSuffix("px")
                    ?.trim()
                    ?.toFloatOrNull()
                val bgHex = extractColorFromStyle(rawTag, "background-color")
                    ?: extractColorFromStyle(rawTag, "background")
                val fontFamily = extractColorFromStyle(rawTag, "font-family")
                when {
                    dataFont != null -> SpanStyle.FontId(unescapeHtml(dataFont))
                    colorHex != null -> SpanStyle.Color(colorHex)
                    parsedPx != null -> SpanStyle.SizePx(parsedPx)
                    bgHex != null -> SpanStyle.Highlight(bgHex)
                    fontFamily != null -> SpanStyle.FontId(unescapeHtml(fontFamily))
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
            "blockquote" -> SpanStyle.Quote
            "code" -> SpanStyle.Code
            else -> null
        }
    }

    private fun extractAttr(tag: String, attr: String): String? {
        val match = Regex("$attr\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)
        return match?.groupValues?.get(1)?.trim()
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
