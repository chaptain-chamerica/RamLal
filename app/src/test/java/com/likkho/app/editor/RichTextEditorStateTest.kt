package com.likkho.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import org.junit.Assert.*
import org.junit.Test

class RichTextEditorStateTest {

    @Test
    fun tool_enable_typesWithStyle_disable_typesDefault_neverUndosPreviousStyle() {
        val editor = RichTextEditorState()

        // 1. Initially all tools are disabled
        assertFalse(editor.isBoldActive)
        assertFalse(editor.isItalicActive)
        assertFalse(editor.isUnderlineActive)
        assertFalse(editor.isStrikeActive)
        assertNull(editor.activeTextColor)
        assertNull(editor.activeHighlightColor)

        // 2. Type "Hello " in default state
        editor.onTextFieldValueChange(TextFieldValue("Hello ", TextRange(6)))
        assertTrue(editor.spans.isEmpty())
        assertEquals("Hello ", editor.text)

        // 3. User taps Bold to enable it
        editor.toggleBold()
        assertTrue(editor.isBoldActive)

        // 4. User types "World" while Bold is enabled
        editor.onTextFieldValueChange(TextFieldValue("Hello World", TextRange(11)))
        assertEquals("Hello World", editor.text)
        assertEquals(1, editor.spans.size)
        val boldSpan = editor.spans[0]
        assertEquals(6, boldSpan.start)
        assertEquals(11, boldSpan.end)
        assertEquals(SpanStyle.Bold, boldSpan.style)

        // 5. User taps Bold to DISABLE it
        editor.toggleBold()
        assertFalse(editor.isBoldActive)

        // 6. User types " Again" while Bold is disabled
        editor.onTextFieldValueChange(TextFieldValue("Hello World Again", TextRange(17)))
        assertEquals("Hello World Again", editor.text)

        // CRITICAL REQUIREMENT 1: "World" MUST STILL BE BOLD (NOT UNDONE)!
        assertEquals(1, editor.spans.size)
        assertEquals(Span(6, 11, SpanStyle.Bold), editor.spans[0])

        // CRITICAL REQUIREMENT 2: " Again" MUST BE IN DEFAULT STATE (NO BOLD)!
        assertFalse(editor.spans.any { it.start >= 11 })
    }

    @Test
    fun cursor_placed_inside_word_after_disable_never_auto_enables_tool() {
        val editor = RichTextEditorState()

        // Type "BoldWord" with Bold active
        editor.toggleBold()
        assertTrue(editor.isBoldActive)
        editor.onTextFieldValueChange(TextFieldValue("BoldWord", TextRange(8)))
        assertEquals(1, editor.spans.size)
        assertEquals(Span(0, 8, SpanStyle.Bold), editor.spans[0])

        // User explicitly disables Bold
        editor.toggleBold()
        assertFalse(editor.isBoldActive)

        // CRITICAL REQUIREMENT: "Disable karne ke baad user agar cursor ko word ke beech me kahin rakhta hai tab bhi tool enable nhi hona chahiye."
        // User moves cursor to index 4 (right inside "BoldWord")
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(4)))

        // Tool MUST REMAIN DISABLED!
        assertFalse(editor.isBoldActive)
        assertFalse(editor.isItalicActive)
        assertFalse(editor.isUnderlineActive)
        assertFalse(editor.isStrikeActive)

        // If user now types "XYZ" at index 4 while Bold is disabled:
        editor.onTextFieldValueChange(TextFieldValue("BoldXYZWord", TextRange(7)))

        // "Bold" [0, 4) stays bold, "XYZ" [4, 7) is NOT bold, "Word" [7, 11) stays bold!
        // The previous bold was NOT undone, and new text is in default unstyled state!
        assertFalse(editor.isBoldActive)
        val boldSpans = editor.spans.filter { it.style == SpanStyle.Bold }
        assertEquals(2, boldSpans.size)
        assertEquals(Span(0, 4, SpanStyle.Bold), boldSpans[0])
        assertEquals(Span(7, 11, SpanStyle.Bold), boldSpans[1])
        assertFalse(editor.spans.any { it.start == 4 && it.end == 7 })
    }

    @Test
    fun italic_underline_strike_typing_and_disable() {
        val editor = RichTextEditorState()

        // Enable Italic and Underline
        editor.toggleItalic()
        editor.toggleUnderline()
        assertTrue(editor.isItalicActive)
        assertTrue(editor.isUnderlineActive)

        // Type "Styled"
        editor.onTextFieldValueChange(TextFieldValue("Styled", TextRange(6)))
        assertEquals(2, editor.spans.size)
        assertTrue(editor.spans.contains(Span(0, 6, SpanStyle.Italic)))
        assertTrue(editor.spans.contains(Span(0, 6, SpanStyle.Underline)))

        // Disable Underline, enable Strike
        editor.toggleUnderline()
        editor.toggleStrike()
        assertFalse(editor.isUnderlineActive)
        assertTrue(editor.isStrikeActive)
        assertTrue(editor.isItalicActive)

        // Type " Text"
        editor.onTextFieldValueChange(TextFieldValue("Styled Text", TextRange(11)))

        // "Styled" keeps Underline; " Text" has Italic + Strike (not Underline)
        val underlineSpans = editor.spans.filter { it.style == SpanStyle.Underline }
        assertEquals(1, underlineSpans.size)
        assertEquals(Span(0, 6, SpanStyle.Underline), underlineSpans[0])

        val strikeSpans = editor.spans.filter { it.style == SpanStyle.Strike }
        assertEquals(1, strikeSpans.size)
        assertEquals(Span(6, 11, SpanStyle.Strike), strikeSpans[0])
    }

    @Test
    fun text_color_and_highlight_enable_typing_and_disable() {
        val editor = RichTextEditorState()

        // Set active text color to blue and highlight to yellow
        editor.setTextColor("#1d4ed8")
        editor.setHighlightColor("#fef08a")
        assertEquals("#1d4ed8", editor.activeTextColor)
        assertEquals("#fef08a", editor.activeHighlightColor)

        // Type "Colored"
        editor.onTextFieldValueChange(TextFieldValue("Colored", TextRange(7)))
        assertEquals(2, editor.spans.size)
        assertTrue(editor.spans.contains(Span(0, 7, SpanStyle.Color("#1d4ed8"))))
        assertTrue(editor.spans.contains(Span(0, 7, SpanStyle.Highlight("#fef08a"))))

        // Disable highlight (set to null / transparent), keep color
        editor.setHighlightColor(null)
        assertNull(editor.activeHighlightColor)
        assertEquals("#1d4ed8", editor.activeTextColor)

        // Type " More"
        editor.onTextFieldValueChange(TextFieldValue("Colored More", TextRange(12)))

        // "Colored" still has highlight; " More" has color but NO highlight
        val highlights = editor.spans.filter { it.style is SpanStyle.Highlight }
        assertEquals(1, highlights.size)
        assertEquals(Span(0, 7, SpanStyle.Highlight("#fef08a")), highlights[0])

        // Disable color (set to null / default)
        editor.setTextColor(null)
        assertNull(editor.activeTextColor)

        // Type " Plain"
        editor.onTextFieldValueChange(TextFieldValue("Colored More Plain", TextRange(18)))

        // " Plain" has no color and no highlight
        val colors = editor.spans.filter { it.style is SpanStyle.Color }
        assertEquals(1, colors.size)
        assertEquals(Span(0, 12, SpanStyle.Color("#1d4ed8")), colors[0])

        // Moving cursor inside "Colored" does not set activeTextColor
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(2)))
        assertNull(editor.activeTextColor)
        assertNull(editor.activeHighlightColor)
    }

    @Test
    fun selection_formatting_worksCorrectly() {
        val editor = RichTextEditorState("Quick fox jumps")

        // Select "fox" (indices 6 to 9)
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(6, 9)))

        // Apply Bold to selection
        editor.toggleBold()
        assertEquals(1, editor.spans.size)
        assertEquals(Span(6, 9, SpanStyle.Bold), editor.spans[0])

        // Apply Color to selection
        editor.setTextColor("#e11d48")
        val colorSpans = editor.spans.filter { it.style is SpanStyle.Color }
        assertEquals(1, colorSpans.size)
        assertEquals(Span(6, 9, SpanStyle.Color("#e11d48")), colorSpans[0])

        // Apply Highlight to selection
        editor.setHighlightColor("#bbf7d0")
        val highlightSpans = editor.spans.filter { it.style is SpanStyle.Highlight }
        assertEquals(1, highlightSpans.size)
        assertEquals(Span(6, 9, SpanStyle.Highlight("#bbf7d0")), highlightSpans[0])
    }

    @Test
    fun html_roundTrip_preservesAllStyles() {
        val originalHtml = "Hello <b>Bold</b> <i>Italic</i> <u>Under</u> <s>Strike</s> <span style=\"color:#1d4ed8\">Blue</span> <mark style=\"background-color:#fef08a\">Yellow</mark>"
        val editor = RichTextEditorState.fromHtml(originalHtml)

        assertEquals("Hello Bold Italic Under Strike Blue Yellow", editor.text)
        assertTrue(editor.spans.any { it.style == SpanStyle.Bold })
        assertTrue(editor.spans.any { it.style == SpanStyle.Italic })
        assertTrue(editor.spans.any { it.style == SpanStyle.Underline })
        assertTrue(editor.spans.any { it.style == SpanStyle.Strike })
        assertTrue(editor.spans.any { it.style == SpanStyle.Color("#1d4ed8") })
        assertTrue(editor.spans.any { it.style == SpanStyle.Highlight("#fef08a") })

        val exportedHtml = editor.toHtml()
        val reimported = RichTextEditorState.fromHtml(exportedHtml)
        assertEquals(editor.text, reimported.text)
        assertEquals(editor.spans.size, reimported.spans.size)
    }
}
