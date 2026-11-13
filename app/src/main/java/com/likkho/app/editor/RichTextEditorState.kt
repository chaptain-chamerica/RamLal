package com.likkho.app.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import com.likkho.app.editor.model.normalizeSpans

/**
 * Manages the rich-text editing state, active typing tools, and character span transformations.
 *
 * Requirements enforced:
 * 1. Tapping a tool (Bold, Italic, Underline, Strike, Color, Highlight) enables it for typing.
 * 2. Tapping it again disables it, returning future typing to the default unstyled state.
 * 3. Disabling a tool NEVER undos or strips styles from already-typed characters.
 * 4. Moving the cursor inside a styled word NEVER auto-enables the tool if it was disabled.
 */
class RichTextEditorState(
    initialText: String = "",
    initialSpans: List<Span> = emptyList()
) {
    var textFieldValue by mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
        private set

    var spans by mutableStateOf(normalizeSpans(initialSpans, initialText.length))
        private set

    // Active tool states for future typing
    var isBoldActive by mutableStateOf(false)
        private set

    var isItalicActive by mutableStateOf(false)
        private set

    var isUnderlineActive by mutableStateOf(false)
        private set

    var isStrikeActive by mutableStateOf(false)
        private set

    var activeTextColor by mutableStateOf<String?>(null)
        private set

    var activeHighlightColor by mutableStateOf<String?>(null)
        private set

    var activeHeadingLevel by mutableStateOf<Int?>(null)
        private set

    val isH1Active: Boolean get() = activeHeadingLevel == 1
    val isH2Active: Boolean get() = activeHeadingLevel == 2
    val isH3Active: Boolean get() = activeHeadingLevel == 3

    val text: String get() = textFieldValue.text
    val selection: TextRange get() = textFieldValue.selection

    // =========================================================================
    //  Tool Toggles & Actions
    // =========================================================================

    fun toggleBold() {
        val sel = selection
        if (sel.min < sel.max) {
            val hasBold = spans.any { it.style == SpanStyle.Bold && it.start <= sel.min && it.end >= sel.max }
            if (hasBold) {
                removeStyleFromRange(sel.min, sel.max) { it == SpanStyle.Bold }
                isBoldActive = false
            } else {
                applyStyleToRange(sel.min, sel.max, SpanStyle.Bold)
                isBoldActive = true
            }
        } else {
            isBoldActive = !isBoldActive
        }
    }

    fun toggleItalic() {
        val sel = selection
        if (sel.min < sel.max) {
            val hasItalic = spans.any { it.style == SpanStyle.Italic && it.start <= sel.min && it.end >= sel.max }
            if (hasItalic) {
                removeStyleFromRange(sel.min, sel.max) { it == SpanStyle.Italic }
                isItalicActive = false
            } else {
                applyStyleToRange(sel.min, sel.max, SpanStyle.Italic)
                isItalicActive = true
            }
        } else {
            isItalicActive = !isItalicActive
        }
    }

    fun toggleUnderline() {
        val sel = selection
        if (sel.min < sel.max) {
            val hasUnderline = spans.any { it.style == SpanStyle.Underline && it.start <= sel.min && it.end >= sel.max }
            if (hasUnderline) {
                removeStyleFromRange(sel.min, sel.max) { it == SpanStyle.Underline }
                isUnderlineActive = false
            } else {
                applyStyleToRange(sel.min, sel.max, SpanStyle.Underline)
                isUnderlineActive = true
            }
        } else {
            isUnderlineActive = !isUnderlineActive
        }
    }

    fun toggleStrike() {
        val sel = selection
        if (sel.min < sel.max) {
            val hasStrike = spans.any { it.style == SpanStyle.Strike && it.start <= sel.min && it.end >= sel.max }
            if (hasStrike) {
                removeStyleFromRange(sel.min, sel.max) { it == SpanStyle.Strike }
                isStrikeActive = false
            } else {
                applyStyleToRange(sel.min, sel.max, SpanStyle.Strike)
                isStrikeActive = true
            }
        } else {
            isStrikeActive = !isStrikeActive
        }
    }

    fun setTextColor(hex: String?) {
        val cleanHex = if (hex == null || hex == "default" || hex.isBlank()) null else hex
        activeTextColor = cleanHex

        val sel = selection
        if (sel.min < sel.max) {
            removeStyleFromRange(sel.min, sel.max) { it is SpanStyle.Color }
            if (cleanHex != null) {
                applyStyleToRange(sel.min, sel.max, SpanStyle.Color(cleanHex))
            }
        }
    }

    fun setHighlightColor(hex: String?) {
        val cleanHex = if (hex == null || hex == "transparent" || hex.isBlank()) null else hex
        activeHighlightColor = cleanHex

        val sel = selection
        if (sel.min < sel.max) {
            removeStyleFromRange(sel.min, sel.max) { it is SpanStyle.Highlight }
            if (cleanHex != null) {
                applyStyleToRange(sel.min, sel.max, SpanStyle.Highlight(cleanHex))
            }
        }
    }

    fun toggleHeading(level: Int) {
        require(level in 1..3) { "Heading level must be 1, 2, or 3" }
        val sel = selection
        if (sel.min < sel.max) {
            val targetStyle = SpanStyle.Heading(level)
            val hasHeading = spans.any { it.style == targetStyle && it.start <= sel.min && it.end >= sel.max }
            if (hasHeading) {
                removeStyleFromRange(sel.min, sel.max) { it == targetStyle }
                if (activeHeadingLevel == level) activeHeadingLevel = null
            } else {
                removeStyleFromRange(sel.min, sel.max) { it is SpanStyle.Heading }
                applyStyleToRange(sel.min, sel.max, targetStyle)
                activeHeadingLevel = level
            }
        } else {
            if (activeHeadingLevel == level) {
                // Disable heading for future typing without undoing already-typed heading text
                activeHeadingLevel = null
            } else {
                activeHeadingLevel = level
                val pos = sel.start.coerceIn(0, text.length)
                val (lineStart, lineEnd) = currentLineBounds(text, pos)
                if (lineStart < lineEnd) {
                    removeStyleFromRange(lineStart, lineEnd) { it is SpanStyle.Heading }
                    applyStyleToRange(lineStart, lineEnd, SpanStyle.Heading(level))
                }
            }
        }
    }

    private fun currentLineBounds(str: String, cursor: Int): Pair<Int, Int> {
        val pos = cursor.coerceIn(0, str.length)
        var start = pos
        while (start > 0 && str[start - 1] != '\n') start--
        var end = pos
        while (end < str.length && str[end] != '\n') end++
        return start to end
    }

    // =========================================================================
    //  Input Handling & Non-Destructive Span Updates
    // =========================================================================

    fun onTextFieldValueChange(newValue: TextFieldValue) {
        val oldText = textFieldValue.text
        val newText = newValue.text

        // Case 1: Cursor moved or selection changed without text change
        if (oldText == newText) {
            textFieldValue = newValue
            // CRITICAL: Do NOT auto-enable any tool based on cursor position!
            // Active typing tools stay strictly as user manually configured them.
            return
        }

        // Case 2: Text changed (typing, deleting, pasting)
        // Find common prefix and suffix to isolate modified range
        var prefixLen = 0
        val minLen = minOf(oldText.length, newText.length)
        while (prefixLen < minLen && oldText[prefixLen] == newText[prefixLen]) {
            prefixLen++
        }

        var suffixLen = 0
        while (suffixLen < (minLen - prefixLen) &&
            oldText[oldText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]
        ) {
            suffixLen++
        }

        val deletedStart = prefixLen
        val deletedEnd = oldText.length - suffixLen
        val insertedStart = prefixLen
        val insertedEnd = newText.length - suffixLen
        val deletedCount = deletedEnd - deletedStart
        val insertedCount = insertedEnd - insertedStart
        val insertedText = if (insertedCount > 0) newText.substring(insertedStart, insertedEnd) else ""
        val firstNewlineRel = insertedText.indexOf('\n')
        val hasNewlineInInsert = firstNewlineRel != -1

        val updatedSpans = mutableListOf<Span>()

        // 1. Shift and adjust existing spans around the deleted and inserted ranges
        for (sp in spans) {
            var s = sp.start
            var e = sp.end

            // Account for deletion in [deletedStart, deletedEnd)
            if (deletedCount > 0) {
                when {
                    // Strictly before deletion
                    e <= deletedStart -> {}
                    // Strictly after deletion: shift left
                    s >= deletedEnd -> {
                        s -= deletedCount
                        e -= deletedCount
                    }
                    // Completely inside deletion: drop span
                    s >= deletedStart && e <= deletedEnd -> continue
                    // Spans over the deleted range: shrink
                    s < deletedStart && e > deletedEnd -> {
                        e -= deletedCount
                    }
                    // Starts before, ends inside
                    s < deletedStart && e > deletedStart -> {
                        e = deletedStart
                    }
                    // Starts inside, ends after
                    s < deletedEnd && e > deletedEnd -> {
                        s = deletedStart
                        e -= deletedCount
                    }
                }
            }

            // Account for insertion at insertedStart with length insertedCount
            if (insertedCount > 0) {
                when {
                    // Strictly before insertion
                    e <= insertedStart -> {
                        updatedSpans.add(Span(s, e, sp.style))
                    }
                    // Strictly after insertion: shift right
                    s >= insertedStart -> {
                        updatedSpans.add(Span(s + insertedCount, e + insertedCount, sp.style))
                    }
                    // Insertion is in the middle of this span (s < insertedStart && e > insertedStart)
                    else -> {
                        val isStyleActive = when (val style = sp.style) {
                            is SpanStyle.Bold -> isBoldActive
                            is SpanStyle.Italic -> isItalicActive
                            is SpanStyle.Underline -> isUnderlineActive
                            is SpanStyle.Strike -> isStrikeActive
                            is SpanStyle.Color -> activeTextColor == style.hex
                            is SpanStyle.Highlight -> activeHighlightColor == style.hex
                            is SpanStyle.Heading -> !hasNewlineInInsert && activeHeadingLevel == style.level
                            else -> false
                        }

                        if (isStyleActive) {
                            // Style is active: span expands across inserted text
                            updatedSpans.add(Span(s, e + insertedCount, sp.style))
                        } else {
                            // Style is disabled (or Enter split a heading): split span so inserted text remains unstyled
                            if (insertedStart > s) {
                                updatedSpans.add(Span(s, insertedStart, sp.style))
                            }
                            if (e > insertedStart) {
                                updatedSpans.add(Span(insertedEnd, e + insertedCount, sp.style))
                            }
                        }
                    }
                }
            } else {
                if (s < e) {
                    updatedSpans.add(Span(s, e, sp.style))
                }
            }
        }

        // 2. Apply all currently active tools to newly inserted characters
        if (insertedCount > 0) {
            if (isBoldActive) {
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.Bold))
            }
            if (isItalicActive) {
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.Italic))
            }
            if (isUnderlineActive) {
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.Underline))
            }
            if (isStrikeActive) {
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.Strike))
            }
            activeTextColor?.let { hex ->
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.Color(hex)))
            }
            activeHighlightColor?.let { hex ->
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.Highlight(hex)))
            }
            activeHeadingLevel?.let { lvl ->
                val headingEnd = if (hasNewlineInInsert) insertedStart + firstNewlineRel else insertedEnd
                if (insertedStart < headingEnd) {
                    updatedSpans.add(Span(insertedStart, headingEnd, SpanStyle.Heading(lvl)))
                }
            }
            // Pressing Enter starts the new line back in default normal paragraph mode
            if (hasNewlineInInsert) {
                activeHeadingLevel = null
            }
        }

        spans = normalizeSpans(updatedSpans, newText.length)
        textFieldValue = newValue
    }

    private fun applyStyleToRange(start: Int, end: Int, style: SpanStyle) {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(0, text.length)
        if (s >= e) return
        spans = normalizeSpans(spans + Span(s, e, style), text.length)
    }

    private fun removeStyleFromRange(start: Int, end: Int, predicate: (SpanStyle) -> Boolean) {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(0, text.length)
        if (s >= e) return

        val result = mutableListOf<Span>()
        for (sp in spans) {
            if (!predicate(sp.style)) {
                result.add(sp)
                continue
            }
            if (sp.end <= s || sp.start >= e) {
                result.add(sp)
            } else if (sp.start >= s && sp.end <= e) {
                // Completely removed
            } else if (sp.start < s && sp.end > e) {
                result.add(Span(sp.start, s, sp.style))
                result.add(Span(e, sp.end, sp.style))
            } else if (sp.start < s && sp.end > s) {
                result.add(Span(sp.start, s, sp.style))
            } else if (sp.start < e && sp.end > e) {
                result.add(Span(e, sp.end, sp.style))
            }
        }
        spans = normalizeSpans(result, text.length)
    }

    fun toHtml(): String = HtmlSpanCodec.toHtml(text, spans)

    companion object {
        fun fromHtml(html: String): RichTextEditorState {
            val (text, spans) = HtmlSpanCodec.fromHtml(html)
            return RichTextEditorState(text, spans)
        }
    }
}
