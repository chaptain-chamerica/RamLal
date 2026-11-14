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

    @Test
    fun headings_h1_h2_h3_enable_enterResets_disableNeverUndos() {
        val editor = RichTextEditorState()
        assertNull(editor.activeHeadingLevel)
        assertFalse(editor.isH1Active)
        assertFalse(editor.isH2Active)
        assertFalse(editor.isH3Active)

        // Enable H1 and type heading
        editor.toggleHeading(1)
        assertTrue(editor.isH1Active)
        assertFalse(editor.isH2Active)
        editor.onTextFieldValueChange(TextFieldValue("Main Title", TextRange(10)))
        assertEquals(listOf(Span(0, 10, SpanStyle.Heading(1))), editor.spans)

        // Pressing Enter keeps "Main Title" as H1 and resets new line to normal paragraph
        editor.onTextFieldValueChange(TextFieldValue("Main Title\n", TextRange(11)))
        assertNull(editor.activeHeadingLevel)
        editor.onTextFieldValueChange(TextFieldValue("Main Title\nNormal paragraph", TextRange(27)))
        assertEquals(listOf(Span(0, 10, SpanStyle.Heading(1))), editor.spans)

        // Type on current line then convert it to H2, then switch mutually exclusively to H3
        editor.onTextFieldValueChange(TextFieldValue("Main Title\nNormal paragraph\nSection", TextRange(35)))
        editor.toggleHeading(2)
        assertTrue(editor.isH2Active)
        assertFalse(editor.isH1Active)
        assertTrue(editor.spans.contains(Span(28, 35, SpanStyle.Heading(2))))

        editor.toggleHeading(3)
        assertTrue(editor.isH3Active)
        assertFalse(editor.isH2Active)
        assertFalse(editor.spans.any { it.start == 28 && it.style == SpanStyle.Heading(2) })
        assertTrue(editor.spans.contains(Span(28, 35, SpanStyle.Heading(3))))

        // Disabling H3 does NOT undo "Section" heading, and future typing is normal
        editor.toggleHeading(3)
        assertNull(editor.activeHeadingLevel)
        editor.onTextFieldValueChange(TextFieldValue("Main Title\nNormal paragraph\nSection note", TextRange(40)))
        assertTrue(editor.spans.contains(Span(28, 35, SpanStyle.Heading(3))))
        assertFalse(editor.spans.any { it.start >= 35 })

        // Placing cursor inside "Main Title" when disabled must NOT auto-enable H1
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(4)))
        assertNull(editor.activeHeadingLevel)
        assertFalse(editor.isH1Active)
    }

    @Test
    fun headings_html_roundTrip_and_selectionToggle() {
        val html = "<h1><b>Chapter 1</b></h1><br><h2>Subheading</h2><br><h3>Details</h3><br>Body"
        val editor = RichTextEditorState.fromHtml(html)

        assertEquals("Chapter 1\nSubheading\nDetails\nBody", editor.text)
        assertTrue(editor.spans.contains(Span(0, 9, SpanStyle.Heading(1))))
        assertTrue(editor.spans.contains(Span(0, 9, SpanStyle.Bold)))
        assertTrue(editor.spans.contains(Span(10, 20, SpanStyle.Heading(2))))
        assertTrue(editor.spans.contains(Span(21, 28, SpanStyle.Heading(3))))

        val exported = editor.toHtml()
        assertTrue(exported.contains("<h1><b>Chapter 1</b></h1>"))
        assertTrue(exported.contains("<h2>Subheading</h2>"))
        assertTrue(exported.contains("<h3>Details</h3>"))

        val reimported = RichTextEditorState.fromHtml(exported)
        assertEquals(editor.text, reimported.text)
        assertEquals(editor.spans, reimported.spans)

        // Toggle off H2 via selection
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(10, 20)))
        editor.toggleHeading(2)
        assertFalse(editor.spans.any { it.style == SpanStyle.Heading(2) })
    }

    @Test
    fun font_enable_typesEnglishAndHindi_disable_neverUndos_and_cursorMoveNeverAutoEnables() {
        val editor = RichTextEditorState()
        assertNull(editor.activeFontId)

        // 1. Enable built-in font and type English
        editor.setFont(FontRegistry.BUILTIN_MONO)
        assertEquals(FontRegistry.BUILTIN_MONO, editor.activeFontId)
        editor.onTextFieldValueChange(TextFieldValue("Mono ", TextRange(5)))
        assertEquals(listOf(Span(0, 5, SpanStyle.FontId(FontRegistry.BUILTIN_MONO))), editor.spans)

        // 2. Switch to custom Hindi/English font and type Hindi text
        val customId = "custom_font_devanagari"
        editor.setFont(customId)
        assertEquals(customId, editor.activeFontId)
        val withHindi = "Mono मेरा नाम "
        editor.onTextFieldValueChange(TextFieldValue(withHindi, TextRange(withHindi.length)))
        assertTrue(editor.spans.contains(Span(0, 5, SpanStyle.FontId(FontRegistry.BUILTIN_MONO))))
        assertTrue(editor.spans.contains(Span(5, withHindi.length, SpanStyle.FontId(customId))))

        // 3. Disable font tool: previous styled text must NOT be undone, future text must be default
        editor.setFont(null)
        assertNull(editor.activeFontId)
        val afterDisable = withHindi + "Normal"
        editor.onTextFieldValueChange(TextFieldValue(afterDisable, TextRange(afterDisable.length)))
        assertTrue(editor.spans.contains(Span(0, 5, SpanStyle.FontId(FontRegistry.BUILTIN_MONO))))
        assertTrue(editor.spans.contains(Span(5, withHindi.length, SpanStyle.FontId(customId))))
        assertFalse(editor.spans.any { it.end > withHindi.length })

        // 4. Moving cursor inside font-styled word when disabled must NOT auto-enable font tool
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(2)))
        assertNull(editor.activeFontId)

        // 5. Typing at cursor inside "Mono" while disabled splits the font span without styling inserted char
        editor.onTextFieldValueChange(TextFieldValue("MoXno मेरा नाम Normal", TextRange(3)))
        assertNull(editor.activeFontId)
        val monoSpans = editor.spans.filter { it.style == SpanStyle.FontId(FontRegistry.BUILTIN_MONO) }
        assertEquals(listOf(Span(0, 2, SpanStyle.FontId(FontRegistry.BUILTIN_MONO)), Span(3, 6, SpanStyle.FontId(FontRegistry.BUILTIN_MONO))), monoSpans)
    }

    @Test
    fun font_selection_applyReplaceAndClear_and_htmlRoundTrip() {
        val editor = RichTextEditorState("Hello दुनिया")
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(6, 12)))
        editor.setFont(FontRegistry.BUILTIN_SERIF)
        assertEquals(listOf(Span(6, 12, SpanStyle.FontId(FontRegistry.BUILTIN_SERIF))), editor.spans)

        // Replace font on same selection
        editor.setFont("custom_font_abc")
        assertEquals(listOf(Span(6, 12, SpanStyle.FontId("custom_font_abc"))), editor.spans)

        // Combine with Bold + Heading and verify HTML round-trip
        editor.toggleBold()
        editor.toggleHeading(2)
        val html = editor.toHtml()
        assertTrue(html.contains("data-font=\"custom_font_abc\""))
        assertTrue(html.contains("<h2>"))
        assertTrue(html.contains("<b>"))

        val reimported = RichTextEditorState.fromHtml(html)
        assertEquals("Hello दुनिया", reimported.text)
        assertTrue(reimported.spans.contains(Span(6, 12, SpanStyle.FontId("custom_font_abc"))))
        assertTrue(reimported.spans.contains(Span(6, 12, SpanStyle.Bold)))
        assertTrue(reimported.spans.contains(Span(6, 12, SpanStyle.Heading(2))))

        // Clear font on selection
        reimported.onTextFieldValueChange(reimported.textFieldValue.copy(selection = TextRange(6, 12)))
        reimported.setFont(null)
        assertFalse(reimported.spans.any { it.style is SpanStyle.FontId })
    }
}

