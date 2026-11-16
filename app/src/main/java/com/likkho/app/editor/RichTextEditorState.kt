package com.likkho.app.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import com.likkho.app.editor.model.TextAlignment
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
    initialSpans: List<Span> = emptyList(),
    initialCanvasAlignment: TextAlignment = TextAlignment.LEFT
) {
    var textFieldValue by mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))
        private set

    var spans by mutableStateOf(normalizeSpans(initialSpans, initialText.length))
        private set

    var canvasAlignment by mutableStateOf(initialCanvasAlignment)
        private set

    var activePendingIndent by mutableStateOf(0)
        private set

    var currentLineAlignOverride by mutableStateOf<TextAlignment?>(null)
        private set

    var currentLineIndentLevel by mutableStateOf(0)
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

    var activeFontId by mutableStateOf<String?>(null)
        private set

    var activeFontSizePx by mutableStateOf<Int?>(null)
        private set

    var isQuoteActive by mutableStateOf(false)
        private set

    var isCodeActive by mutableStateOf(false)
        private set

    var isBulletActive by mutableStateOf(false)
        private set

    var isNumberedActive by mutableStateOf(false)
        private set

    var isTodoActive by mutableStateOf(false)
        private set

    val isH1Active: Boolean get() = activeHeadingLevel == 1
    val isH2Active: Boolean get() = activeHeadingLevel == 2
    val isH3Active: Boolean get() = activeHeadingLevel == 3

    val effectiveAlignment: TextAlignment get() = currentLineAlignOverride ?: canvasAlignment
    val currentIndentLevel: Int get() = maxOf(currentLineIndentLevel, activePendingIndent)

    val text: String get() = textFieldValue.text
    val selection: TextRange get() = textFieldValue.selection

    init {
        syncLineState(initialText.length)
    }

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

    fun setFont(fontId: String?) {
        val cleanId = if (fontId == null || fontId == "default" || fontId.isBlank()) null else fontId
        activeFontId = cleanId

        val sel = selection
        if (sel.min < sel.max) {
            removeStyleFromRange(sel.min, sel.max) { it is SpanStyle.FontId }
            if (cleanId != null) {
                applyStyleToRange(sel.min, sel.max, SpanStyle.FontId(cleanId))
            }
        }
    }

    fun setFontSizePx(px: Int?) {
        val cleanPx = px?.coerceIn(10, 48)
        activeFontSizePx = cleanPx

        val sel = selection
        if (sel.min < sel.max) {
            removeStyleFromRange(sel.min, sel.max) { it is SpanStyle.SizePx }
            if (cleanPx != null) {
                applyStyleToRange(sel.min, sel.max, SpanStyle.SizePx(cleanPx.toFloat()))
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

    fun toggleQuote() {
        val sel = selection
        if (sel.min < sel.max) {
            val hasQuote = spans.any { it.style == SpanStyle.Quote && it.start <= sel.min && it.end >= sel.max }
            if (hasQuote) {
                removeStyleFromRange(sel.min, sel.max) { it == SpanStyle.Quote }
                isQuoteActive = false
            } else {
                removeStyleFromRange(sel.min, sel.max) { it == SpanStyle.Code }
                applyStyleToRange(sel.min, sel.max, SpanStyle.Quote)
                isQuoteActive = true
                isCodeActive = false
            }
        } else {
            // Toggle quote strictly for future typing without altering prior default text
            if (isQuoteActive) {
                isQuoteActive = false
            } else {
                isQuoteActive = true
                isCodeActive = false
            }
        }
    }

    fun toggleCode() {
        val sel = selection
        if (sel.min < sel.max) {
            val hasCode = spans.any { it.style == SpanStyle.Code && it.start <= sel.min && it.end >= sel.max }
            if (hasCode) {
                removeStyleFromRange(sel.min, sel.max) { it == SpanStyle.Code }
                isCodeActive = false
            } else {
                removeStyleFromRange(sel.min, sel.max) { it == SpanStyle.Quote }
                applyStyleToRange(sel.min, sel.max, SpanStyle.Code)
                isCodeActive = true
                isQuoteActive = false
            }
        } else {
            if (isCodeActive) {
                // Disable for future typing without undoing already-typed code text
                isCodeActive = false
            } else {
                isCodeActive = true
                isQuoteActive = false
            }
        }
    }

    /**
     * Paragraph & Canvas Alignment:
     * - When text is selected (`sel.min < sel.max`), sets an independent per-paragraph alignment
     *   for all selected non-divider lines without altering [canvasAlignment].
     * - When cursor is collapsed, updates [canvasAlignment] so the whole canvas reflects it, and
     *   clears any paragraph-level override on the cursor's current line.
     */
    fun setAlignment(alignment: TextAlignment) {
        val currentText = text
        val sel = selection
        if (sel.min < sel.max) {
            val selectedLines = collectSelectedLines(currentText, sel.min, sel.max)
                .filter { (ls, le) -> !isDividerLine(spans, ls, le) }
            var workingSpans = spans
            var applied = false
            for ((ls, le) in selectedLines) {
                val paraEnd = lineParaEnd(currentText, le)
                if (ls < paraEnd) {
                    workingSpans = removeStyleFromList(workingSpans, ls, paraEnd) { it is SpanStyle.Align } +
                        Span(ls, paraEnd, SpanStyle.Align(alignment))
                    applied = true
                }
            }
            if (applied) {
                spans = normalizeSpans(snapLineParagraphSpans(currentText, workingSpans), currentText.length)
            } else {
                canvasAlignment = alignment
            }
        } else {
            canvasAlignment = alignment
            val (ls, le) = currentLineBounds(currentText, sel.start)
            val paraEnd = lineParaEnd(currentText, le)
            if (ls < paraEnd && spans.any { it.style is SpanStyle.Align && it.start < paraEnd && it.end > ls }) {
                removeStyleFromRange(ls, paraEnd) { it is SpanStyle.Align }
            }
        }
        syncLineState(sel.start)
    }

    fun increaseIndent() {
        val currentText = text
        val sel = selection
        val selectedLines = collectSelectedLines(currentText, sel.min, sel.max)
            .filter { (ls, le) -> !isDividerLine(spans, ls, le) }
        if (selectedLines.isEmpty()) return

        var workingSpans = spans
        for ((ls, le) in selectedLines) {
            val paraEnd = lineParaEnd(currentText, le)
            if (ls >= paraEnd) {
                activePendingIndent = (activePendingIndent + 1).coerceIn(0, MAX_INDENT)
                continue
            }
            val currentLvl = workingSpans
                .filter { it.style is SpanStyle.Indent && it.start < paraEnd && it.end > ls }
                .maxOfOrNull { (it.style as SpanStyle.Indent).level } ?: 0
            val newLvl = (currentLvl + 1).coerceIn(1, MAX_INDENT)
            workingSpans = removeStyleFromList(workingSpans, ls, paraEnd) { it is SpanStyle.Indent } +
                Span(ls, paraEnd, SpanStyle.Indent(newLvl))
        }
        spans = normalizeSpans(snapLineParagraphSpans(currentText, workingSpans), currentText.length)
        syncLineState(sel.start)
    }

    fun decreaseIndent() {
        val currentText = text
        val sel = selection
        val selectedLines = collectSelectedLines(currentText, sel.min, sel.max)
            .filter { (ls, le) -> !isDividerLine(spans, ls, le) }
        if (selectedLines.isEmpty()) return

        var workingSpans = spans
        for ((ls, le) in selectedLines) {
            val paraEnd = lineParaEnd(currentText, le)
            if (ls >= paraEnd) {
                activePendingIndent = (activePendingIndent - 1).coerceIn(0, MAX_INDENT)
                continue
            }
            val currentLvl = workingSpans
                .filter { it.style is SpanStyle.Indent && it.start < paraEnd && it.end > ls }
                .maxOfOrNull { (it.style as SpanStyle.Indent).level } ?: 0
            val newLvl = (currentLvl - 1).coerceIn(0, MAX_INDENT)
            workingSpans = removeStyleFromList(workingSpans, ls, paraEnd) { it is SpanStyle.Indent }
            if (newLvl > 0) {
                workingSpans = workingSpans + Span(ls, paraEnd, SpanStyle.Indent(newLvl))
            }
        }
        spans = normalizeSpans(snapLineParagraphSpans(currentText, workingSpans), currentText.length)
        syncLineState(sel.start)
    }

    private enum class ListKind { BULLET, NUMBERED, TODO }

    fun toggleBullet() = toggleListKind(ListKind.BULLET)

    fun toggleNumbered() = toggleListKind(ListKind.NUMBERED)

    fun toggleTodo() = toggleListKind(ListKind.TODO)

    /**
     * Toggles the checked state of a canvas checkbox whose prefix starts at [prefixStart].
     * Preserves text length, selection, and all other spans cleanly.
     */
    fun toggleTodoAt(prefixStart: Int): Boolean {
        val currentText = text
        val idx = spans.indexOfFirst { it.start == prefixStart && it.style is SpanStyle.Todo }
        if (idx == -1) return false
        val targetSpan = spans[idx]
        val oldTodo = targetSpan.style as SpanStyle.Todo
        val newChecked = !oldTodo.checked
        val newChar = if (newChecked) TODO_CHECKED_PREFIX[0] else TODO_UNCHECKED_PREFIX[0]
        val newText = if (prefixStart in currentText.indices) {
            currentText.substring(0, prefixStart) + newChar + currentText.substring(prefixStart + 1)
        } else {
            currentText
        }
        val updatedSpans = spans.mapIndexed { i, sp ->
            if (i == idx) sp.copy(style = SpanStyle.Todo(checked = newChecked)) else sp
        }
        spans = normalizeSpans(updatedSpans, newText.length)
        textFieldValue = textFieldValue.copy(text = newText)
        return true
    }

    private fun toggleListKind(target: ListKind) {
        val currentText = text
        val sel = selection
        val selectedLines = collectSelectedLines(currentText, sel.min, sel.max)

        // If cursor is collapsed on a divider line, insert a new list item line below the divider
        if (sel.collapsed && selectedLines.size == 1) {
            val (ls, le) = selectedLines[0]
            if (isDividerLine(spans, ls, le)) {
                val prefix = when (target) {
                    ListKind.BULLET -> BULLET_PREFIX
                    ListKind.NUMBERED -> "1. "
                    ListKind.TODO -> TODO_UNCHECKED_PREFIX
                }
                val style = when (target) {
                    ListKind.BULLET -> SpanStyle.Bullet
                    ListKind.NUMBERED -> SpanStyle.Numbered
                    ListKind.TODO -> SpanStyle.Todo(false)
                }
                val edits = listOf(TextEdit(le, le, "\n$prefix", null))
                val (editedText, shiftedSpans, _) = applyTextEdits(currentText, spans, edits, sel.start, sel.end)
                val newPrefixStart = le + 1
                val newSpans = (shiftedSpans + Span(newPrefixStart, newPrefixStart + prefix.length, style)).toMutableList()
                val newCursor = newPrefixStart + prefix.length
                val (finalText, finalSpans, finalCursor, _) = renumberNumberedLists(editedText, newSpans, newCursor, newCursor)
                spans = normalizeSpans(snapLineParagraphSpans(finalText, finalSpans), finalText.length)
                textFieldValue = TextFieldValue(finalText, TextRange(finalCursor))
                syncLineState(finalCursor)
                return
            }
        }

        val editableLines = selectedLines.filter { (ls, le) -> !isDividerLine(spans, ls, le) }
        if (editableLines.isEmpty()) return

        fun matchesTarget(style: SpanStyle?): Boolean = when (target) {
            ListKind.BULLET -> style is SpanStyle.Bullet
            ListKind.NUMBERED -> style is SpanStyle.Numbered
            ListKind.TODO -> style is SpanStyle.Todo
        }

        val allAlreadyTarget = editableLines.all { (ls, le) ->
            matchesTarget(findListPrefixOnLine(spans, ls, le)?.style)
        }

        val edits = mutableListOf<TextEdit>()
        val droppedPrefixes = mutableSetOf<Span>()

        for ((ls, le) in editableLines) {
            val existing = findListPrefixOnLine(spans, ls, le)
            if (allAlreadyTarget) {
                if (existing != null) {
                    droppedPrefixes.add(existing)
                    edits.add(TextEdit(existing.start, existing.end, "", null))
                }
            } else {
                val (newPrefix, newStyle) = when (target) {
                    ListKind.BULLET -> BULLET_PREFIX to SpanStyle.Bullet
                    ListKind.NUMBERED -> "1. " to SpanStyle.Numbered
                    ListKind.TODO -> TODO_UNCHECKED_PREFIX to SpanStyle.Todo(false)
                }
                if (existing == null) {
                    edits.add(TextEdit(ls, ls, newPrefix, newStyle))
                } else if (!matchesTarget(existing.style)) {
                    droppedPrefixes.add(existing)
                    edits.add(TextEdit(existing.start, existing.end, newPrefix, newStyle))
                }
            }
        }

        if (edits.isEmpty()) return

        val baseSpans = spans.filter { it !in droppedPrefixes }
        val (editedText, shiftedSpans, mappedSel) = applyTextEdits(currentText, baseSpans, edits, sel.min, sel.max)
        val finalSelStart = if (sel.start <= sel.end) mappedSel.first else mappedSel.second
        val finalSelEnd = if (sel.start <= sel.end) mappedSel.second else mappedSel.first
        val (renumText, renumSpans, rStart, rEnd) = renumberNumberedLists(
            editedText,
            shiftedSpans.toMutableList(),
            finalSelStart,
            finalSelEnd
        )
        spans = normalizeSpans(snapLineParagraphSpans(renumText, renumSpans), renumText.length)
        textFieldValue = TextFieldValue(renumText, TextRange(rStart, rEnd))
        syncLineState(rStart)
    }

    fun insertDivider() {
        val sel = selection
        val oldText = text
        val pos = sel.min.coerceIn(0, oldText.length)
        val selEnd = sel.max.coerceIn(pos, oldText.length)

        val needLeadingNewline = pos > 0 && oldText[pos - 1] != '\n'
        val needTrailingNewline = selEnd >= oldText.length || oldText[selEnd] != '\n'

        val prefix = if (needLeadingNewline) "\n" else ""
        val suffix = if (needTrailingNewline) "\n" else ""
        val inserted = "$prefix$DIVIDER_MARKER$suffix"

        val newText = oldText.substring(0, pos) + inserted + oldText.substring(selEnd)
        val deletedCount = selEnd - pos
        val insertedCount = inserted.length
        val markerIdx = pos + prefix.length
        val newCursor = (markerIdx + 2).coerceAtMost(newText.length)

        val updatedSpans = mutableListOf<Span>()
        for (sp in spans) {
            var s = sp.start
            var e = sp.end

            if (deletedCount > 0) {
                when {
                    e <= pos -> {}
                    s >= selEnd -> {
                        s -= deletedCount
                        e -= deletedCount
                    }
                    s >= pos && e <= selEnd -> continue
                    s < pos && e > selEnd -> e -= deletedCount
                    s < pos && e > pos -> e = pos
                    s < selEnd && e > selEnd -> {
                        s = pos
                        e -= deletedCount
                    }
                }
            }

            when {
                e <= pos -> updatedSpans.add(Span(s, e, sp.style))
                s >= pos -> updatedSpans.add(Span(s + insertedCount, e + insertedCount, sp.style))
                else -> {
                    if (pos > s) updatedSpans.add(Span(s, pos, sp.style))
                    if (e > pos) updatedSpans.add(Span(pos + insertedCount, e + insertedCount, sp.style))
                }
            }
        }

        updatedSpans.add(Span(markerIdx, markerIdx + 1, SpanStyle.Divider))
        activeHeadingLevel = null
        activePendingIndent = 0
        val (renumText, renumSpans, rCursor, _) = renumberNumberedLists(newText, updatedSpans, newCursor, newCursor)
        spans = normalizeSpans(snapLineParagraphSpans(renumText, renumSpans), renumText.length)
        textFieldValue = TextFieldValue(renumText, TextRange(rCursor))
        syncLineState(rCursor)
    }

    private fun currentLineBounds(str: String, cursor: Int): Pair<Int, Int> {
        val pos = cursor.coerceIn(0, str.length)
        var start = pos
        while (start > 0 && str[start - 1] != '\n') start--
        var end = pos
        while (end < str.length && str[end] != '\n') end++
        return start to end
    }

    private fun collectSelectedLines(str: String, selMin: Int, selMax: Int): List<Pair<Int, Int>> {
        if (str.isEmpty()) return listOf(0 to 0)
        val firstStart = currentLineBounds(str, selMin).first
        val effEnd = if (selMin < selMax && selMax > 0 && str[selMax - 1] == '\n') selMax - 1 else selMax
        val result = mutableListOf<Pair<Int, Int>>()
        var cur = firstStart
        while (cur <= str.length) {
            var le = cur
            while (le < str.length && str[le] != '\n') le++
            result.add(cur to le)
            if (le >= effEnd || le >= str.length) break
            cur = le + 1
        }
        return result
    }

    private fun allLines(str: String): List<Pair<Int, Int>> {
        if (str.isEmpty()) return listOf(0 to 0)
        val lines = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start <= str.length) {
            var end = start
            while (end < str.length && str[end] != '\n') end++
            lines.add(start to end)
            if (end >= str.length) break
            start = end + 1
        }
        return lines
    }

    private fun lineParaEnd(str: String, lineEnd: Int): Int =
        if (lineEnd < str.length && str[lineEnd] == '\n') lineEnd + 1 else lineEnd

    private fun isListPrefixStyle(style: SpanStyle): Boolean =
        style is SpanStyle.Bullet || style is SpanStyle.Numbered || style is SpanStyle.Todo

    private fun findListPrefixOnLine(spanList: List<Span>, lineStart: Int, lineEnd: Int): Span? =
        spanList.firstOrNull { sp ->
            sp.start == lineStart && sp.end <= lineEnd && isListPrefixStyle(sp.style)
        }

    private fun isDividerLine(spanList: List<Span>, lineStart: Int, lineEnd: Int): Boolean =
        spanList.any { it.style is SpanStyle.Divider && it.start >= lineStart && it.start < lineEnd }

    private fun syncLineState(cursorPos: Int) {
        val currentText = text
        val (ls, le) = currentLineBounds(currentText, cursorPos)
        val prefix = findListPrefixOnLine(spans, ls, le)
        isBulletActive = prefix?.style is SpanStyle.Bullet
        isNumberedActive = prefix?.style is SpanStyle.Numbered
        isTodoActive = prefix?.style is SpanStyle.Todo

        val paraEnd = lineParaEnd(currentText, le)
        if (ls < paraEnd) {
            currentLineAlignOverride = spans.lastOrNull {
                it.style is SpanStyle.Align && it.start < paraEnd && it.end > ls
            }?.let { (it.style as SpanStyle.Align).alignment }
            currentLineIndentLevel = spans.filter {
                it.style is SpanStyle.Indent && it.start < paraEnd && it.end > ls
            }.maxOfOrNull { (it.style as SpanStyle.Indent).level } ?: 0
        } else {
            currentLineAlignOverride = null
            currentLineIndentLevel = 0
        }
    }

    private fun snapLineParagraphSpans(rawText: String, rawSpans: List<Span>): List<Span> {
        if (rawText.isEmpty() || rawSpans.isEmpty()) return rawSpans
        val hasPara = rawSpans.any { it.style is SpanStyle.Align || it.style is SpanStyle.Indent }
        if (!hasPara) return rawSpans

        val lines = allLines(rawText)
        val others = rawSpans.filter { it.style !is SpanStyle.Align && it.style !is SpanStyle.Indent }.toMutableList()
        val paraSpans = rawSpans.filter { it.style is SpanStyle.Align || it.style is SpanStyle.Indent }

        for ((ls, le) in lines) {
            val paraEnd = lineParaEnd(rawText, le)
            if (ls >= paraEnd) continue
            if (others.any { it.style is SpanStyle.Divider && it.start >= ls && it.start < le }) continue

            var lineAlign: TextAlignment? = null
            var lineIndent = 0
            for (sp in paraSpans) {
                if (sp.start < paraEnd && sp.end > ls) {
                    when (val st = sp.style) {
                        is SpanStyle.Align -> lineAlign = st.alignment
                        is SpanStyle.Indent -> if (st.level > lineIndent) lineIndent = st.level
                        else -> {}
                    }
                }
            }
            if (lineAlign != null) {
                others.add(Span(ls, paraEnd, SpanStyle.Align(lineAlign)))
            }
            if (lineIndent > 0) {
                others.add(Span(ls, paraEnd, SpanStyle.Indent(lineIndent.coerceIn(1, MAX_INDENT))))
            }
        }
        return others
    }

    private data class TextEdit(
        val oldStart: Int,
        val oldEnd: Int,
        val newText: String,
        val createdSpanStyle: SpanStyle?
    )

    private data class EditOutcome(
        val text: String,
        val spans: List<Span>,
        val selection: Pair<Int, Int>
    )

    private data class RenumberOutcome(
        val text: String,
        val spans: MutableList<Span>,
        val selStart: Int,
        val selEnd: Int
    )

    private fun applyTextEdits(
        str: String,
        spanList: List<Span>,
        rawEdits: List<TextEdit>,
        selStart: Int,
        selEnd: Int
    ): EditOutcome {
        if (rawEdits.isEmpty()) return EditOutcome(str, spanList, selStart to selEnd)
        val edits = rawEdits.sortedBy { it.oldStart }

        val sb = StringBuilder()
        var lastIdx = 0
        val createdSpans = mutableListOf<Span>()
        val mappedRanges = ArrayList<Triple<Int, Int, Int>>(edits.size)
        val editNewBounds = ArrayList<IntRange>(edits.size)

        for (ed in edits) {
            val os = ed.oldStart.coerceIn(lastIdx, str.length)
            val oe = ed.oldEnd.coerceIn(os, str.length)
            sb.append(str, lastIdx, os)
            val newStart = sb.length
            sb.append(ed.newText)
            val newEnd = sb.length
            if (ed.createdSpanStyle != null && ed.newText.isNotEmpty()) {
                createdSpans.add(Span(newStart, newEnd, ed.createdSpanStyle))
            }
            mappedRanges.add(Triple(os, oe, newEnd))
            editNewBounds.add(newStart..newEnd)
            lastIdx = oe
        }
        sb.append(str, lastIdx, str.length)

        fun mapPoint(pt: Int, shiftAtBoundary: Boolean): Int {
            var delta = 0
            for (i in edits.indices) {
                val (os, oe, newEnd) = mappedRanges[i]
                val newStart = editNewBounds[i].first
                val replacementLen = newEnd - newStart
                if (os == oe) {
                    if (pt < os) return pt + delta
                    if (pt == os) {
                        return if (shiftAtBoundary) pt + delta + replacementLen else pt + delta
                    }
                    delta += replacementLen
                } else {
                    if (pt < os) return pt + delta
                    if (pt <= oe) return newEnd
                    delta += replacementLen - (oe - os)
                }
            }
            return pt + delta
        }

        val shiftedSpans = mutableListOf<Span>()
        for (sp in spanList) {
            val shiftStartBoundary = sp.style !is SpanStyle.Align && sp.style !is SpanStyle.Indent
            val ns = mapPoint(sp.start, shiftAtBoundary = shiftStartBoundary)
            val ne = mapPoint(sp.end, shiftAtBoundary = false)
            if (ns < ne) shiftedSpans.add(Span(ns, ne, sp.style))
        }
        shiftedSpans.addAll(createdSpans)

        val mappedStart = mapPoint(selStart, shiftAtBoundary = true)
        val mappedEnd = mapPoint(selEnd, shiftAtBoundary = true)
        return EditOutcome(sb.toString(), shiftedSpans, mappedStart to mappedEnd)
    }

    private fun renumberNumberedLists(
        rawText: String,
        rawSpans: MutableList<Span>,
        selStart: Int,
        selEnd: Int
    ): RenumberOutcome {
        if (rawSpans.none { it.style is SpanStyle.Numbered }) {
            return RenumberOutcome(rawText, rawSpans, selStart, selEnd)
        }
        val lines = allLines(rawText)
        var counter = 0
        val edits = mutableListOf<TextEdit>()
        val dropped = mutableSetOf<Span>()

        for ((ls, le) in lines) {
            val numSpan = rawSpans.firstOrNull { it.start == ls && it.end <= le && it.style is SpanStyle.Numbered }
            if (numSpan != null) {
                counter++
                val desired = "$counter. "
                val actual = rawText.substring(numSpan.start, numSpan.end)
                if (actual != desired) {
                    dropped.add(numSpan)
                    edits.add(TextEdit(numSpan.start, numSpan.end, desired, SpanStyle.Numbered))
                }
            } else {
                counter = 0
            }
        }

        if (edits.isEmpty()) {
            return RenumberOutcome(rawText, rawSpans, selStart, selEnd)
        }
        val remaining = rawSpans.filter { it !in dropped }
        val outcome = applyTextEdits(rawText, remaining, edits, selStart, selEnd)
        return RenumberOutcome(outcome.text, outcome.spans.toMutableList(), outcome.selection.first, outcome.selection.second)
    }

    // =========================================================================
    //  Input Handling & Non-Destructive Span Updates
    // =========================================================================

    fun onTextFieldValueChange(newValue: TextFieldValue) {
        val oldText = textFieldValue.text
        val newText = newValue.text

        // Case 1: Cursor moved or selection changed without text change
        if (oldText == newText) {
            var adjusted = newValue
            if (newValue.selection.collapsed) {
                val pos = newValue.selection.start.coerceIn(0, newText.length)
                val (ls, le) = currentLineBounds(newText, pos)
                val prefix = findListPrefixOnLine(spans, ls, le)
                if (prefix != null && pos < prefix.end) {
                    adjusted = newValue.copy(selection = TextRange(prefix.end))
                }
            }
            val prevPos = textFieldValue.selection.start.coerceIn(0, oldText.length)
            val oldLs = currentLineBounds(oldText, prevPos).first
            val newPos = adjusted.selection.start.coerceIn(0, newText.length)
            val (newLs, newLe) = currentLineBounds(newText, newPos)
            if (newLs < lineParaEnd(newText, newLe) || newLs != oldLs) {
                activePendingIndent = 0
            }
            textFieldValue = adjusted
            syncLineState(adjusted.selection.start)
            // CRITICAL: Do NOT auto-enable inline tools based on cursor position!
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

        // Special Case 2a: Backspace inside a list prefix ("• ", "1. ", "☐ ") cleanly removes the entire list item prefix
        if (deletedCount in 1..2 && insertedCount == 0) {
            val touchedPrefix = spans.firstOrNull { sp ->
                isListPrefixStyle(sp.style) &&
                    deletedStart >= sp.start &&
                    deletedEnd <= sp.end
            }
            if (touchedPrefix != null) {
                val edits = listOf(TextEdit(touchedPrefix.start, touchedPrefix.end, "", null))
                val remaining = spans.filter { it != touchedPrefix }
                val outcome = applyTextEdits(oldText, remaining, edits, touchedPrefix.start, touchedPrefix.start)
                val renumbered = renumberNumberedLists(
                    outcome.text,
                    outcome.spans.toMutableList(),
                    outcome.selection.first,
                    outcome.selection.second
                )
                spans = normalizeSpans(snapLineParagraphSpans(renumbered.text, renumbered.spans), renumbered.text.length)
                textFieldValue = TextFieldValue(renumbered.text, TextRange(renumbered.selStart))
                syncLineState(renumbered.selStart)
                return
            }
        }

        // Special Case 2b: Pressing Enter inside or at the end of a list item line
        val (oldLs, oldLe) = currentLineBounds(oldText, deletedStart)
        val prevLineParaEnd = lineParaEnd(oldText, oldLe)
        val prevLineIndent = if (oldLs < prevLineParaEnd) {
            spans.filter { it.style is SpanStyle.Indent && it.start < prevLineParaEnd && it.end > oldLs }
                .maxOfOrNull { (it.style as SpanStyle.Indent).level } ?: 0
        } else {
            activePendingIndent
        }
        val oldListPrefix = if (insertedText == "\n") findListPrefixOnLine(spans, oldLs, oldLe) else null
        if (oldListPrefix != null && deletedStart >= oldListPrefix.end) {
            // Check whether the item had no text other than its prefix (and any deleted selection was everything)
            val remainingBeforeCursor = oldText.substring(oldListPrefix.end, deletedStart)
            val remainingAfterCursor = if (deletedEnd < oldLe) oldText.substring(deletedEnd, oldLe) else ""
            if (remainingBeforeCursor.isEmpty() && remainingAfterCursor.isEmpty()) {
                // Exit list on Enter of an empty bullet / numbered / checkbox item
                val edits = listOf(TextEdit(oldListPrefix.start, oldListPrefix.end, "", null))
                val remaining = spans.filter { it != oldListPrefix }
                val outcome = applyTextEdits(oldText, remaining, edits, oldListPrefix.start, oldListPrefix.start)
                val renumbered = renumberNumberedLists(
                    outcome.text,
                    outcome.spans.toMutableList(),
                    outcome.selection.first,
                    outcome.selection.second
                )
                activeHeadingLevel = null
                activePendingIndent = 0
                spans = normalizeSpans(snapLineParagraphSpans(renumbered.text, renumbered.spans), renumbered.text.length)
                textFieldValue = TextFieldValue(renumbered.text, TextRange(renumbered.selStart))
                syncLineState(renumbered.selStart)
                return
            }
        }

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
                        if (sp.style is SpanStyle.Align || sp.style is SpanStyle.Indent) {
                            if (hasNewlineInInsert && e == insertedStart + 1) {
                                // Newline inserted right before existing trailing '\n' of this line: keep old line covered without bleeding onto new line
                                updatedSpans.add(Span(s, insertedStart + firstNewlineRel + 1, sp.style))
                            } else {
                                updatedSpans.add(Span(s, e + insertedCount, sp.style))
                            }
                        } else {
                            val isStyleActive = when (val style = sp.style) {
                                is SpanStyle.Bold -> isBoldActive
                                is SpanStyle.Italic -> isItalicActive
                                is SpanStyle.Underline -> isUnderlineActive
                                is SpanStyle.Strike -> isStrikeActive
                                is SpanStyle.Color -> activeTextColor == style.hex
                                is SpanStyle.Highlight -> activeHighlightColor == style.hex
                                is SpanStyle.FontId -> activeFontId == style.fontId
                                is SpanStyle.SizePx -> activeFontSizePx != null && activeFontSizePx == style.sizePx.toInt()
                                is SpanStyle.Quote -> isQuoteActive
                                is SpanStyle.Code -> isCodeActive
                                is SpanStyle.Heading -> !hasNewlineInInsert && activeHeadingLevel == style.level
                                else -> false
                            }

                            if (isStyleActive) {
                                // Style is active: span expands across inserted text
                                updatedSpans.add(Span(s, e + insertedCount, sp.style))
                            } else {
                                // Style is disabled (or Enter split a heading / prefix): split span so inserted text remains unstyled
                                if (insertedStart > s) {
                                    updatedSpans.add(Span(s, insertedStart, sp.style))
                                }
                                if (e > insertedStart) {
                                    updatedSpans.add(Span(insertedEnd, e + insertedCount, sp.style))
                                }
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

        // 2. Apply all currently active tools to newly inserted characters (skip inline styles when Enter creates a new line)
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
            activeFontId?.let { fontId ->
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.FontId(fontId)))
            }
            activeFontSizePx?.let { px ->
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.SizePx(px.toFloat())))
            }
            if (isQuoteActive) {
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.Quote))
            }
            if (isCodeActive) {
                updatedSpans.add(Span(insertedStart, insertedEnd, SpanStyle.Code))
            }
            activeHeadingLevel?.let { lvl ->
                val headingEnd = if (hasNewlineInInsert) insertedStart + firstNewlineRel else insertedEnd
                if (insertedStart < headingEnd) {
                    updatedSpans.add(Span(insertedStart, headingEnd, SpanStyle.Heading(lvl)))
                }
            }
            // Pressing Enter starts the new line back in default normal paragraph heading state
            if (hasNewlineInInsert) {
                activeHeadingLevel = null
                activePendingIndent = if (oldLs < oldLe) prevLineIndent else 0
            } else if (activePendingIndent > 0) {
                val (newLs, newLe) = currentLineBounds(newText, insertedStart)
                val newParaEnd = lineParaEnd(newText, newLe)
                if (newLs < newParaEnd) {
                    updatedSpans.add(Span(newLs, newParaEnd, SpanStyle.Indent(activePendingIndent.coerceIn(1, MAX_INDENT))))
                }
                activePendingIndent = 0
            }
        } else if (deletedCount > 0) {
            val pos = newValue.selection.start.coerceIn(0, newText.length)
            val (curLs, curLe) = currentLineBounds(newText, pos)
            if (curLs < lineParaEnd(newText, curLe)) {
                activePendingIndent = 0
            }
        }

        var workingText = newText
        var workingSpans = updatedSpans
        var workingSel = newValue.selection

        // Auto-continue list on Enter of a non-empty list item
        if (oldListPrefix != null && deletedStart >= oldListPrefix.end) {
            val (nextPrefix, nextStyle) = when (oldListPrefix.style) {
                is SpanStyle.Bullet -> BULLET_PREFIX to SpanStyle.Bullet
                is SpanStyle.Numbered -> "1. " to SpanStyle.Numbered
                is SpanStyle.Todo -> TODO_UNCHECKED_PREFIX to SpanStyle.Todo(checked = false)
                else -> "" to null
            }
            if (nextStyle != null) {
                val edits = listOf(TextEdit(insertedEnd, insertedEnd, nextPrefix, nextStyle))
                // Split any non-list spans crossing insertedEnd so they don't color/bold the new prefix marker
                val cleanBeforeContinue = mutableListOf<Span>()
                for (sp in workingSpans) {
                    if (!isListPrefixStyle(sp.style) &&
                        sp.style !is SpanStyle.Align &&
                        sp.style !is SpanStyle.Indent &&
                        sp.start < insertedEnd &&
                        sp.end > insertedEnd
                    ) {
                        if (sp.start < insertedStart) cleanBeforeContinue.add(Span(sp.start, insertedStart, sp.style))
                        cleanBeforeContinue.add(Span(insertedEnd, sp.end, sp.style))
                    } else {
                        cleanBeforeContinue.add(sp)
                    }
                }
                val outcome = applyTextEdits(workingText, cleanBeforeContinue, edits, insertedEnd, insertedEnd)
                workingText = outcome.text
                workingSpans = outcome.spans.toMutableList()
                workingSel = TextRange(outcome.selection.first)
            }
        }

        val renumbered = renumberNumberedLists(workingText, workingSpans, workingSel.start, workingSel.end)
        val snapped = snapLineParagraphSpans(renumbered.text, renumbered.spans)
        spans = normalizeSpans(snapped, renumbered.text.length)
        textFieldValue = TextFieldValue(renumbered.text, TextRange(renumbered.selStart, renumbered.selEnd))
        syncLineState(renumbered.selStart)
    }

    private fun applyStyleToRange(start: Int, end: Int, style: SpanStyle) {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(0, text.length)
        if (s >= e) return
        spans = normalizeSpans(spans + Span(s, e, style), text.length)
    }

    private fun removeStyleFromList(
        source: List<Span>,
        start: Int,
        end: Int,
        predicate: (SpanStyle) -> Boolean
    ): List<Span> {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(0, text.length)
        if (s >= e) return source

        val result = mutableListOf<Span>()
        for (sp in source) {
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
        return result
    }

    private fun removeStyleFromRange(start: Int, end: Int, predicate: (SpanStyle) -> Boolean) {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(0, text.length)
        if (s >= e) return
        spans = normalizeSpans(removeStyleFromList(spans, s, e, predicate), text.length)
    }

    fun toHtml(): String = HtmlSpanCodec.toHtml(text, spans, canvasAlignment)

    companion object {
        const val DIVIDER_MARKER = "\u200B"
        const val BULLET_PREFIX = "\u2022 "
        const val TODO_UNCHECKED_PREFIX = "\u2610 "
        const val TODO_CHECKED_PREFIX = "\u2611 "
        const val MAX_INDENT = 6

        fun fromHtml(html: String): RichTextEditorState {
            val decoded = HtmlSpanCodec.fromHtmlWithMeta(html)
            return RichTextEditorState(decoded.text, decoded.spans, decoded.canvasAlignment)
        }
    }
}
