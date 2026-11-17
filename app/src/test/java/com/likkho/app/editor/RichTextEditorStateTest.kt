package com.likkho.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import com.likkho.app.editor.model.TextAlignment
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

    @Test
    fun quote_and_code_oneClickEnableDisable_multiLine_neverUndo_neverAutoEnableOnCursorMove() {
        val editor = RichTextEditorState()
        assertFalse(editor.isQuoteActive)
        assertFalse(editor.isCodeActive)

        // 1. One click enables Quote and typing across lines preserves Quote without turning off on newline
        editor.toggleQuote()
        assertTrue(editor.isQuoteActive)
        assertFalse(editor.isCodeActive)
        editor.onTextFieldValueChange(TextFieldValue("Quote line 1\nQuote line 2", TextRange(25)))
        assertEquals(listOf(Span(0, 25, SpanStyle.Quote)), editor.spans)

        // 2. One click disables Quote: previously typed quote MUST NOT be undone, future typing is default
        editor.toggleQuote()
        assertFalse(editor.isQuoteActive)
        editor.onTextFieldValueChange(TextFieldValue("Quote line 1\nQuote line 2\nNormal", TextRange(32)))
        assertTrue(editor.spans.contains(Span(0, 25, SpanStyle.Quote)))
        assertFalse(editor.spans.any { it.end > 25 })

        // 3. Moving cursor inside Quote when disabled MUST NOT auto-enable Quote
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(5)))
        assertFalse(editor.isQuoteActive)

        // 4. One click enables Code at end and typing applies Code without converting prior line
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(32)))
        editor.onTextFieldValueChange(TextFieldValue("Quote line 1\nQuote line 2\nNormal\n", TextRange(33)))
        editor.toggleCode()
        assertTrue(editor.isCodeActive)
        assertFalse(editor.isQuoteActive)
        editor.onTextFieldValueChange(TextFieldValue("Quote line 1\nQuote line 2\nNormal\nval x = 1", TextRange(42)))
        assertTrue(editor.spans.contains(Span(33, 42, SpanStyle.Code)))

        // 5. One click disables Code: previous code stays intact, future typing is normal
        editor.toggleCode()
        assertFalse(editor.isCodeActive)
        editor.onTextFieldValueChange(TextFieldValue("Quote line 1\nQuote line 2\nNormal\nval x = 1 end", TextRange(46)))
        assertEquals(listOf(Span(0, 25, SpanStyle.Quote), Span(33, 42, SpanStyle.Code)), editor.spans)

        // 6. Placing cursor inside Code when disabled MUST NOT auto-enable Code
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(36)))
        assertFalse(editor.isCodeActive)
    }

    @Test
    fun quote_and_code_selectionToggle_and_htmlRoundTrip() {
        val text = "Say: सत्यमेव जयते and val a = 1"
        val quoteSub = "सत्यमेव जयते"
        val codeSub = "val a = 1"
        val qStart = text.indexOf(quoteSub)
        val qEnd = qStart + quoteSub.length
        val cStart = text.indexOf(codeSub)
        val cEnd = cStart + codeSub.length

        val editor = RichTextEditorState(text)
        // Select Hindi quote substring
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(qStart, qEnd)))
        editor.toggleQuote()
        assertTrue(editor.isQuoteActive)
        assertTrue(editor.spans.contains(Span(qStart, qEnd, SpanStyle.Quote)))

        // Select code substring "val a = 1"
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(cStart, cEnd)))
        editor.toggleCode()
        assertTrue(editor.isCodeActive)
        assertFalse(editor.isQuoteActive)
        assertTrue(editor.spans.contains(Span(cStart, cEnd, SpanStyle.Code)))

        val html = editor.toHtml()
        assertTrue(html.contains("<blockquote>सत्यमेव जयते</blockquote>"))
        assertTrue(html.contains("<code>val a = 1</code>"))

        val reimported = RichTextEditorState.fromHtml(html)
        assertEquals(editor.text, reimported.text)
        assertEquals(editor.spans, reimported.spans)

        // Toggle off Code on selection with one click
        reimported.onTextFieldValueChange(reimported.textFieldValue.copy(selection = TextRange(cStart, cEnd)))
        reimported.toggleCode()
        assertFalse(reimported.spans.any { it.style == SpanStyle.Code })
        assertTrue(reimported.spans.contains(Span(qStart, qEnd, SpanStyle.Quote)))
    }

    @Test
    fun quote_enablingMidLineDoesNotConvertPrecedingDefaultText_and_noParagraphStyleSplitOnNewline() {
        val editor = RichTextEditorState()
        // 1. Write default text first
        editor.onTextFieldValueChange(TextFieldValue("Default text ", TextRange(13)))
        assertTrue(editor.spans.isEmpty())

        // 2. Enable Quote at collapsed cursor -> MUST NOT quote "Default text "
        editor.toggleQuote()
        assertTrue(editor.isQuoteActive)
        assertTrue(editor.spans.isEmpty())

        // 3. Write quoted text after default text
        val full = "Default text quoted\nnext line"
        editor.onTextFieldValueChange(TextFieldValue(full, TextRange(full.length)))
        assertEquals(listOf(Span(13, full.length, SpanStyle.Quote)), editor.spans)

        // 4. VisualTransformation must not inject ParagraphStyle splits (which caused 2-line Enter jump)
        val transformed = RichTextVisualTransformation(editor.spans)
            .filter(androidx.compose.ui.text.AnnotatedString(full))
            .text
        assertTrue(transformed.paragraphStyles.isEmpty())
    }

    @Test
    fun textPx_enable_sliderUpdates_disableNeverUndos_cursorMoveNeverAutoEnables_and_htmlRoundTrip() {
        val editor = RichTextEditorState()
        assertNull(editor.activeFontSizePx)

        // 1. Enable at 24px and type
        editor.setFontSizePx(24)
        assertEquals(24, editor.activeFontSizePx)
        editor.onTextFieldValueChange(TextFieldValue("Big", TextRange(3)))
        assertEquals(listOf(Span(0, 3, SpanStyle.SizePx(24f))), editor.spans)

        // 2. Slider changes to 36px and type more
        editor.setFontSizePx(36)
        assertEquals(36, editor.activeFontSizePx)
        editor.onTextFieldValueChange(TextFieldValue("Big Huge", TextRange(8)))
        assertTrue(editor.spans.contains(Span(0, 3, SpanStyle.SizePx(24f))))
        assertTrue(editor.spans.contains(Span(3, 8, SpanStyle.SizePx(36f))))

        // 3. Disable (second click on tool icon): previous sizes stay intact, future typing is default
        editor.setFontSizePx(null)
        assertNull(editor.activeFontSizePx)
        editor.onTextFieldValueChange(TextFieldValue("Big Huge normal", TextRange(15)))
        assertEquals(
            listOf(Span(0, 3, SpanStyle.SizePx(24f)), Span(3, 8, SpanStyle.SizePx(36f))),
            editor.spans
        )

        // 4. Placing cursor inside "Big" while disabled MUST NOT auto-enable Text Px
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(1)))
        assertNull(editor.activeFontSizePx)

        // 5. HTML round-trip preserves font-size px spans
        val html = editor.toHtml()
        assertTrue(html.contains("font-size:24px"))
        assertTrue(html.contains("font-size:36px"))
        val reimported = RichTextEditorState.fromHtml(html)
        assertEquals(editor.text, reimported.text)
        assertEquals(editor.spans, reimported.spans)

        // 6. Selection update via slider replaces size cleanly
        reimported.onTextFieldValueChange(reimported.textFieldValue.copy(selection = TextRange(9, 15)))
        reimported.setFontSizePx(18)
        assertTrue(reimported.spans.contains(Span(9, 15, SpanStyle.SizePx(18f))))
    }

    @Test
    fun divider_insertEmptyMidEnd_resetsHeading_and_htmlRoundTrip() {
        // 1. Insert divider on empty editor
        val emptyEditor = RichTextEditorState()
        emptyEditor.insertDivider()
        assertEquals("${RichTextEditorState.DIVIDER_MARKER}\n", emptyEditor.text)
        assertEquals(TextRange(2), emptyEditor.selection)
        assertEquals(listOf(Span(0, 1, SpanStyle.Divider)), emptyEditor.spans)

        // 2. Insert divider after H1 heading resets activeHeadingLevel and places cursor on new line
        val editor = RichTextEditorState()
        editor.toggleHeading(1)
        assertTrue(editor.isH1Active)
        editor.onTextFieldValueChange(TextFieldValue("Title", TextRange(5)))
        editor.insertDivider()

        assertFalse(editor.isH1Active)
        assertNull(editor.activeHeadingLevel)
        val expectedText = "Title\n${RichTextEditorState.DIVIDER_MARKER}\n"
        assertEquals(expectedText, editor.text)
        assertEquals(TextRange(expectedText.length), editor.selection)
        assertTrue(editor.spans.contains(Span(0, 5, SpanStyle.Heading(1))))
        assertTrue(editor.spans.contains(Span(6, 7, SpanStyle.Divider)))

        // Type paragraph below divider
        editor.onTextFieldValueChange(TextFieldValue("${expectedText}Next", TextRange(expectedText.length + 4)))

        // 3. Insert divider mid-line between "Hello" and "World"
        val midEditor = RichTextEditorState("HelloWorld")
        midEditor.onTextFieldValueChange(midEditor.textFieldValue.copy(selection = TextRange(5)))
        midEditor.insertDivider()
        assertEquals("Hello\n${RichTextEditorState.DIVIDER_MARKER}\nWorld", midEditor.text)
        assertEquals(TextRange(8), midEditor.selection)
        assertEquals(listOf(Span(6, 7, SpanStyle.Divider)), midEditor.spans)

        // 4. HTML round-trip for <hr>, <hr/>, and <hr />
        val html = editor.toHtml()
        assertTrue(html.contains("<hr>"))
        val reimported = RichTextEditorState.fromHtml(html)
        assertEquals(editor.text, reimported.text)
        assertEquals(editor.spans, reimported.spans)

        val selfClosing = RichTextEditorState.fromHtml("Top<br><hr/><br>Mid<br><hr /><br>Bottom")
        assertEquals(
            "Top\n${RichTextEditorState.DIVIDER_MARKER}\nMid\n${RichTextEditorState.DIVIDER_MARKER}\nBottom",
            selfClosing.text
        )
        assertEquals(2, selfClosing.spans.count { it.style is SpanStyle.Divider })
    }

    @Test
    fun bulletList_toggle_enterContinue_enterEmptyExit_backspaceExit_and_cursorSnap() {
        val editor = RichTextEditorState()

        // 1. Toggle Bullet on empty doc
        editor.toggleBullet()
        assertTrue(editor.isBulletActive)
        assertEquals("• ", editor.text)
        assertEquals(TextRange(2), editor.selection)
        assertEquals(listOf(Span(0, 2, SpanStyle.Bullet)), editor.spans)

        // 2. Type "First" and press Enter -> auto-adds "• " on line 2
        editor.onTextFieldValueChange(TextFieldValue("• First", TextRange(7)))
        editor.onTextFieldValueChange(TextFieldValue("• First\n", TextRange(8)))
        assertTrue(editor.isBulletActive)
        assertEquals("• First\n• ", editor.text)
        assertEquals(TextRange(10), editor.selection)
        assertEquals(listOf(Span(0, 2, SpanStyle.Bullet), Span(8, 10, SpanStyle.Bullet)), editor.spans)

        // 3. Press Enter on empty bullet line 2 -> exits bullet list cleanly
        editor.onTextFieldValueChange(TextFieldValue("• First\n• \n", TextRange(11)))
        assertFalse(editor.isBulletActive)
        assertEquals("• First\n", editor.text)
        assertEquals(TextRange(8), editor.selection)
        assertEquals(listOf(Span(0, 2, SpanStyle.Bullet)), editor.spans)

        // 4. Cursor placed inside "• " prefix snaps forward to after prefix
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(0)))
        assertEquals(TextRange(2), editor.selection)
        assertTrue(editor.isBulletActive)

        // 5. Backspace inside prefix ("• First" -> "•First") removes entire bullet prefix
        editor.onTextFieldValueChange(TextFieldValue("•First\n", TextRange(1)))
        assertFalse(editor.isBulletActive)
        assertEquals("First\n", editor.text)
        assertEquals(TextRange(0), editor.selection)
        assertTrue(editor.spans.isEmpty())
    }

    @Test
    fun numberedList_autoIncrement_renumbering_toggleOff_and_multiLineSelection() {
        val editor = RichTextEditorState()
        editor.toggleNumbered()
        assertTrue(editor.isNumberedActive)
        assertEquals("1. ", editor.text)

        // Type first item and press Enter -> becomes "2. "
        editor.onTextFieldValueChange(TextFieldValue("1. One", TextRange(6)))
        editor.onTextFieldValueChange(TextFieldValue("1. One\n", TextRange(7)))
        assertEquals("1. One\n2. ", editor.text)
        assertEquals(TextRange(10), editor.selection)

        // Type second and third items
        editor.onTextFieldValueChange(TextFieldValue("1. One\n2. Two", TextRange(13)))
        editor.onTextFieldValueChange(TextFieldValue("1. One\n2. Two\n", TextRange(14)))
        editor.onTextFieldValueChange(TextFieldValue("1. One\n2. Two\n3. Three", TextRange(22)))

        // Move cursor onto item 2 and toggle Numbered off -> item 3 becomes a fresh list starting at 1!
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(11)))
        assertTrue(editor.isNumberedActive)
        editor.toggleNumbered()
        assertFalse(editor.isNumberedActive)
        assertEquals("1. One\nTwo\n1. Three", editor.text)
        val numSpans = editor.spans.filter { it.style is SpanStyle.Numbered }
        assertEquals(listOf(Span(0, 3, SpanStyle.Numbered), Span(11, 14, SpanStyle.Numbered)), numSpans)

        // Re-enabling Numbered on "Two" bridges the list back into 1., 2., 3.
        editor.toggleNumbered()
        assertTrue(editor.isNumberedActive)
        assertEquals("1. One\n2. Two\n3. Three", editor.text)

        // Select multiple plain lines and convert to Numbered
        val multi = RichTextEditorState("Alpha\nBeta\nGamma")
        multi.onTextFieldValueChange(multi.textFieldValue.copy(selection = TextRange(0, multi.text.length)))
        multi.toggleNumbered()
        assertEquals("1. Alpha\n2. Beta\n3. Gamma", multi.text)
    }

    @Test
    fun checkBox_toggle_canvasClickCheckUncheck_strikethroughVisual_and_htmlRoundTrip() {
        val editor = RichTextEditorState()
        editor.toggleTodo()
        assertTrue(editor.isTodoActive)
        assertEquals("☐ ", editor.text)
        assertEquals(listOf(Span(0, 2, SpanStyle.Todo(checked = false))), editor.spans)

        // Type item & press Enter -> next checkbox is always unchecked
        editor.onTextFieldValueChange(TextFieldValue("☐ Milk", TextRange(6)))
        editor.onTextFieldValueChange(TextFieldValue("☐ Milk\n", TextRange(7)))
        editor.onTextFieldValueChange(TextFieldValue("☐ Milk\n☐ Eggs", TextRange(13)))

        // Clicking first checkbox on canvas checks it without changing cursor or selection
        val preSelection = editor.selection
        assertTrue(editor.toggleTodoAt(0))
        assertEquals("☑ Milk\n☐ Eggs", editor.text)
        assertEquals(preSelection, editor.selection)
        assertTrue(editor.spans.contains(Span(0, 2, SpanStyle.Todo(checked = true))))
        assertTrue(editor.spans.contains(Span(7, 9, SpanStyle.Todo(checked = false))))

        // RichTextVisualTransformation strikes through checked line content "Milk"
        val transformed = RichTextVisualTransformation(editor.spans)
            .filter(androidx.compose.ui.text.AnnotatedString(editor.text))
            .text
        assertTrue(transformed.spanStyles.any {
            it.start == 2 && it.end == 6 &&
                it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough
        })

        // Clicking first checkbox again unchecks it
        assertTrue(editor.toggleTodoAt(0))
        assertEquals("☐ Milk\n☐ Eggs", editor.text)
        assertTrue(editor.spans.contains(Span(0, 2, SpanStyle.Todo(checked = false))))

        // Check second item and combine with Bullet + Numbered for full HTML round-trip
        assertTrue(editor.toggleTodoAt(7))
        editor.onTextFieldValueChange(TextFieldValue("☐ Milk\n☑ Eggs\n", TextRange(14)))
        // Enter auto-adds "☐ " at line 3; exit line 3 with Enter -> "☐ Milk\n☑ Eggs\n"
        editor.onTextFieldValueChange(TextFieldValue("${editor.text}\n", TextRange(editor.text.length + 1)))
        // Switch line 3 to Bullet then Numbered on another line
        val doc = RichTextEditorState.fromHtml(
            "Intro<br><ul><li><b>Bullet1</b></li><li>Bullet2</li></ul><br><ol><li>Step1</li><li>Step2</li></ol><br><ul data-checklist=\"true\"><li data-checked=\"false\">Milk</li><li data-checked=\"true\">Eggs</li></ul>"
        )
        assertEquals("Intro\n• Bullet1\n• Bullet2\n1. Step1\n2. Step2\n☐ Milk\n☑ Eggs", doc.text)
        val reHtml = doc.toHtml()
        val reimported = RichTextEditorState.fromHtml(reHtml)
        assertEquals(doc.text, reimported.text)
        assertEquals(doc.spans, reimported.spans)
    }

    @Test
    fun alignment_defaultLeft_canvasWideCollapsed_and_perParagraphSelectionOverride() {
        val editor = RichTextEditorState("First line\nSecond line\nThird line")

        // 1. Default canvas alignment is LEFT
        assertEquals(TextAlignment.LEFT, editor.canvasAlignment)
        assertEquals(TextAlignment.LEFT, editor.effectiveAlignment)

        // 2. Collapsed cursor sets canvas-wide alignment across RIGHT, CENTER, JUSTIFY, LEFT
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(2)))
        editor.setAlignment(TextAlignment.RIGHT)
        assertEquals(TextAlignment.RIGHT, editor.canvasAlignment)
        assertEquals(TextAlignment.RIGHT, editor.effectiveAlignment)
        assertFalse(editor.spans.any { it.style is SpanStyle.Align })

        // 3. Select Second line ("Second line", indices 11..22) and give it CENTER alignment
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(11, 22)))
        editor.setAlignment(TextAlignment.CENTER)
        // Canvas alignment remains RIGHT, while Second line gets [11, 23) Align(CENTER) inclusive of '\n'
        assertEquals(TextAlignment.RIGHT, editor.canvasAlignment)
        assertEquals(TextAlignment.CENTER, editor.effectiveAlignment)
        assertTrue(editor.spans.contains(Span(11, 23, SpanStyle.Align(TextAlignment.CENTER))))

        // 4. Changing canvas alignment to JUSTIFY while collapsed on line 3 leaves line 2 CENTER intact
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(26)))
        editor.setAlignment(TextAlignment.JUSTIFY)
        assertEquals(TextAlignment.JUSTIFY, editor.canvasAlignment)
        assertEquals(TextAlignment.JUSTIFY, editor.effectiveAlignment)
        assertTrue(editor.spans.contains(Span(11, 23, SpanStyle.Align(TextAlignment.CENTER))))

        // Cursor moving back onto line 2 reports effectiveAlignment = CENTER
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(15)))
        assertEquals(TextAlignment.CENTER, editor.effectiveAlignment)

        // Tapping LEFT while collapsed inside overridden line 2 clears its override and sets canvas LEFT
        editor.setAlignment(TextAlignment.LEFT)
        assertEquals(TextAlignment.LEFT, editor.canvasAlignment)
        assertEquals(TextAlignment.LEFT, editor.effectiveAlignment)
        assertFalse(editor.spans.any { it.style is SpanStyle.Align })
    }

    @Test
    fun indent_and_outdent_singleMultiLine_clamping_emptyCanvasPending_and_enterCarry() {
        // 1. Empty canvas pending indent applied when typing starts, and outdent reduces it
        val emptyEditor = RichTextEditorState()
        assertEquals(0, emptyEditor.currentIndentLevel)
        emptyEditor.increaseIndent()
        emptyEditor.increaseIndent()
        assertEquals(2, emptyEditor.currentIndentLevel)
        emptyEditor.decreaseIndent()
        assertEquals(1, emptyEditor.currentIndentLevel)

        emptyEditor.onTextFieldValueChange(TextFieldValue("Alpha", TextRange(5)))
        assertEquals(listOf(Span(0, 5, SpanStyle.Indent(1))), emptyEditor.spans)
        assertEquals(1, emptyEditor.currentIndentLevel)

        // Pressing Enter carries indent level onto next line once typed
        emptyEditor.onTextFieldValueChange(TextFieldValue("Alpha\n", TextRange(6)))
        assertTrue(emptyEditor.spans.contains(Span(0, 6, SpanStyle.Indent(1))))
        assertEquals(1, emptyEditor.activePendingIndent)
        emptyEditor.onTextFieldValueChange(TextFieldValue("Alpha\nBeta", TextRange(10)))
        assertEquals(listOf(Span(0, 10, SpanStyle.Indent(1))), emptyEditor.spans)

        // 2. Clamping 1..MAX_INDENT and Outdent back to 0
        repeat(10) { emptyEditor.increaseIndent() } // cursor at line 2 "Beta"
        assertEquals(RichTextEditorState.MAX_INDENT, emptyEditor.currentIndentLevel)
        assertTrue(emptyEditor.spans.contains(Span(6, 10, SpanStyle.Indent(RichTextEditorState.MAX_INDENT))))

        repeat(10) { emptyEditor.decreaseIndent() }
        assertEquals(0, emptyEditor.currentIndentLevel)
        assertEquals(listOf(Span(0, 6, SpanStyle.Indent(1))), emptyEditor.spans)

        // 3. Multi-line selection adjusts each selected line individually
        val multi = RichTextEditorState("One\nTwo\nThree")
        multi.onTextFieldValueChange(multi.textFieldValue.copy(selection = TextRange(0, 7))) // lines 1 & 2
        multi.increaseIndent()
        assertEquals(listOf(Span(0, 8, SpanStyle.Indent(1))), multi.spans)
    }

    @Test
    fun visualTransformation_mergesAlignAndIndent_and_htmlRoundTripPreservesCanvasAndParagraphAlignAndIndent() {
        val editor = RichTextEditorState()
        // Set canvas alignment to CENTER
        editor.setAlignment(TextAlignment.CENTER)
        editor.onTextFieldValueChange(TextFieldValue("Header", TextRange(6)))
        editor.onTextFieldValueChange(TextFieldValue("Header\nBody line", TextRange(16)))
        editor.onTextFieldValueChange(TextFieldValue("Header\nBody line\n", TextRange(17)))

        // Select "Body line" and align RIGHT + Indent level 2
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(7, 16)))
        editor.setAlignment(TextAlignment.RIGHT)
        editor.increaseIndent()
        editor.increaseIndent()

        // Add an indented & aligned checklist item on line 3
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(17)))
        editor.toggleTodo()
        editor.onTextFieldValueChange(TextFieldValue("Header\nBody line\n☐ Task", TextRange(23)))
        editor.increaseIndent()

        // Verify VisualTransformation merges Align + Indent into non-overlapping ParagraphStyles covering '\n'
        val transformed = RichTextVisualTransformation(
            spans = editor.spans,
            defaultAlignment = editor.canvasAlignment
        ).filter(androidx.compose.ui.text.AnnotatedString(editor.text)).text

        val bodyPara = transformed.paragraphStyles.firstOrNull { it.start == 7 && it.end == 17 }
        assertNotNull(bodyPara)
        assertEquals(TextAlign.Right, bodyPara!!.item.textAlign)
        assertEquals(TextIndent(48.sp, 48.sp), bodyPara.item.textIndent)

        val taskPara = transformed.paragraphStyles.firstOrNull { it.start == 17 && it.end == 23 }
        assertNotNull(taskPara)
        assertEquals(TextAlign.Center, taskPara!!.item.textAlign)
        assertEquals(TextIndent(24.sp, 24.sp), taskPara.item.textIndent)

        // Verify non-overlapping ParagraphStyles
        val sortedParas = transformed.paragraphStyles.sortedBy { it.start }
        for (i in 1 until sortedParas.size) {
            assertTrue(sortedParas[i].start >= sortedParas[i - 1].end)
        }

        // Full HTML round-trip preserves canvasAlignment, per-line Align, Indent, and Todo
        val html = editor.toHtml()
        assertTrue(html.contains("data-canvas-align=\"center\""))
        assertTrue(html.contains("text-align:right"))
        assertTrue(html.contains("data-indent=\"2\""))

        val reimported = RichTextEditorState.fromHtml(html)
        assertEquals(TextAlignment.CENTER, reimported.canvasAlignment)
        assertEquals(editor.text, reimported.text)
        assertEquals(editor.spans, reimported.spans)
    }

    @Test
    fun superscript_and_subscript_toggle_mutualExclusion_visualBaselineShift_and_htmlRoundTrip() {
        val editor = RichTextEditorState()
        assertFalse(editor.isSuperscriptActive)
        assertFalse(editor.isSubscriptActive)

        // 1. Type "H" normally, enable Subscript and type "2", disable Subscript and type "O + x"
        editor.onTextFieldValueChange(TextFieldValue("H", TextRange(1)))
        editor.toggleSubscript()
        assertTrue(editor.isSubscriptActive)
        assertFalse(editor.isSuperscriptActive)
        editor.onTextFieldValueChange(TextFieldValue("H2", TextRange(2)))
        assertEquals(listOf(Span(1, 2, SpanStyle.Subscript)), editor.spans)

        // Disabling Subscript never undoes "2"
        editor.toggleSubscript()
        assertFalse(editor.isSubscriptActive)
        editor.onTextFieldValueChange(TextFieldValue("H2O + x", TextRange(7)))
        assertEquals(listOf(Span(1, 2, SpanStyle.Subscript)), editor.spans)

        // 2. Enable Superscript and type "2", disable and type end
        editor.toggleSuperscript()
        assertTrue(editor.isSuperscriptActive)
        assertFalse(editor.isSubscriptActive)
        editor.onTextFieldValueChange(TextFieldValue("H2O + x2", TextRange(8)))
        assertTrue(editor.spans.contains(Span(7, 8, SpanStyle.Superscript)))

        editor.toggleSuperscript()
        assertFalse(editor.isSuperscriptActive)

        // Moving cursor inside Subscript/Superscript must NEVER auto-enable tools
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(1)))
        assertFalse(editor.isSubscriptActive)
        assertFalse(editor.isSuperscriptActive)

        // 3. Switching selection from Superscript to Subscript replaces mutually exclusively
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(7, 8)))
        editor.toggleSubscript()
        assertTrue(editor.isSubscriptActive)
        assertFalse(editor.isSuperscriptActive)
        assertTrue(editor.spans.contains(Span(7, 8, SpanStyle.Subscript)))
        assertFalse(editor.spans.any { it.style == SpanStyle.Superscript })

        // Switch back to Superscript on selection [7, 8)
        editor.toggleSuperscript()
        assertTrue(editor.isSuperscriptActive)
        assertFalse(editor.isSubscriptActive)
        assertTrue(editor.spans.contains(Span(7, 8, SpanStyle.Superscript)))

        // 4. VisualTransformation applies BaselineShift and scaled fontSize
        val transformed = RichTextVisualTransformation(editor.spans)
            .filter(androidx.compose.ui.text.AnnotatedString(editor.text))
            .text
        val subSpan = transformed.spanStyles.firstOrNull { it.start == 1 && it.end == 2 }
        assertNotNull(subSpan)
        assertEquals(androidx.compose.ui.text.style.BaselineShift(-0.25f), subSpan!!.item.baselineShift)
        assertTrue(subSpan.item.fontSize < 16.sp)

        val supSpan = transformed.spanStyles.firstOrNull { it.start == 7 && it.end == 8 }
        assertNotNull(supSpan)
        assertEquals(androidx.compose.ui.text.style.BaselineShift(0.45f), supSpan!!.item.baselineShift)
        assertTrue(supSpan.item.fontSize < 16.sp)

        // 5. HTML round-trip uses <sub> and <sup>
        val html = editor.toHtml()
        assertTrue(html.contains("<sub>2</sub>"))
        assertTrue(html.contains("<sup>2</sup>"))
        val reimported = RichTextEditorState.fromHtml(html)
        assertEquals(editor.text, reimported.text)
        assertEquals(editor.spans, reimported.spans)
    }

    @Test
    fun weblink_selectionOnly_urlNormalization_visualUnderline_offsetHit_delink_and_htmlRoundTrip() {
        val editor = RichTextEditorState("Visit Google today")

        // 1. Collapsed cursor (no selection): applyLinkToRange & removeLinkFromRange MUST NOT work
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(6)))
        assertFalse(editor.hasSelection)
        assertFalse(editor.applyLinkToRange("google.com"))
        assertTrue(editor.spans.isEmpty())

        // 2. Select "Google" [6, 12) and apply link without scheme -> normalizes to https://
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(6, 12)))
        assertTrue(editor.hasSelection)
        assertTrue(editor.applyLinkToRange("google.com"))
        assertTrue(editor.hasLinkOnSelection)
        assertEquals("https://google.com", editor.selectedLinkUrl())
        assertEquals(listOf(Span(6, 12, SpanStyle.Link("https://google.com"))), editor.spans)

        // 3. Offset hit-test matches only inside [6, 12)
        assertNull(editor.linkAtOffset(5))
        assertEquals("https://google.com", editor.linkAtOffset(6))
        assertEquals("https://google.com", editor.linkAtOffset(11))
        assertNull(editor.linkAtOffset(12))

        // 4. VisualTransformation renders link with linkColor and Underline
        val linkCol = androidx.compose.ui.graphics.Color(0xFF3366CC)
        val transformed = RichTextVisualTransformation(editor.spans, linkColor = linkCol)
            .filter(androidx.compose.ui.text.AnnotatedString(editor.text))
            .text
        val linkStyle = transformed.spanStyles.firstOrNull { it.start == 6 && it.end == 12 }
        assertNotNull(linkStyle)
        assertEquals(linkCol, linkStyle!!.item.color)
        assertEquals(androidx.compose.ui.text.style.TextDecoration.Underline, linkStyle.item.textDecoration)

        // 5. HTML round-trip uses <a href="...">
        val html = editor.toHtml()
        assertEquals("Visit <a href=\"https://google.com\">Google</a> today", html)
        val reimported = RichTextEditorState.fromHtml(html)
        assertEquals(editor.text, reimported.text)
        assertEquals(editor.spans, reimported.spans)

        // 6. Delinking removes link cleanly without deleting text
        reimported.onTextFieldValueChange(reimported.textFieldValue.copy(selection = TextRange(6, 12)))
        assertTrue(reimported.removeLinkFromRange())
        assertEquals("Visit Google today", reimported.text)
        assertTrue(reimported.spans.isEmpty())
        assertNull(reimported.selectedLinkUrl())
    }

    @Test
    fun textSpoiler_selectionOnly_locksDeleteAndCopy_garbageOnParagraphCopy_unlockAndHtmlRoundTrip() {
        val editor = RichTextEditorState("Secret pin is 4321 ok")
        val blockedCallbacks = mutableListOf<SpanStyle.Spoiler>()
        editor.onLockedSpoilerBlocked = { blockedCallbacks.add(it) }

        // 1. Collapsed cursor or blank passcode MUST NOT create a spoiler
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(14)))
        assertFalse(editor.hasSelection)
        assertFalse(editor.applySpoilerToRange("1234"))
        assertTrue(editor.spans.isEmpty())

        // 2. Select "4321" [14, 18) and apply spoiler with blank -> rejected; valid passcode -> applied & locked
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(14, 18)))
        assertFalse(editor.applySpoilerToRange("   "))
        assertTrue(editor.applySpoilerToRange("pass99"))
        assertEquals(1, editor.spans.size)
        val spoilerStyle = editor.spans.first().style as SpanStyle.Spoiler
        assertFalse(editor.isSpoilerUnlocked(spoilerStyle))
        assertEquals(spoilerStyle, editor.lockedSpoilerAtOffset(15))
        assertNull(editor.lockedSpoilerAtOffset(10))

        // 3. VisualTransformation hides locked spoiler text with Color.Transparent
        val lockedVisual = RichTextVisualTransformation(
            spans = editor.spans,
            unlockedSpoilerIds = editor.unlockedSpoilerIds
        ).filter(androidx.compose.ui.text.AnnotatedString(editor.text)).text
        assertTrue(
            lockedVisual.spanStyles.any {
                it.start == 14 && it.end == 18 && it.item.color == androidx.compose.ui.graphics.Color.Transparent
            }
        )

        // 4. Locked spoiler CANNOT be deleted (backspace, selection delete, blind middle edit, or divider replace)
        editor.onTextFieldValueChange(TextFieldValue("Secret pin is 432 ok", TextRange(17)))
        assertEquals("Secret pin is 4321 ok", editor.text)
        assertEquals(1, blockedCallbacks.size)

        editor.onTextFieldValueChange(TextFieldValue("Secret pin is  ok", TextRange(14)))
        assertEquals("Secret pin is 4321 ok", editor.text)
        assertEquals(2, blockedCallbacks.size)

        editor.onTextFieldValueChange(TextFieldValue("Secret pin is 43X21 ok", TextRange(17)))
        assertEquals("Secret pin is 4321 ok", editor.text)
        assertEquals(3, blockedCallbacks.size)

        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(12, 19)))
        editor.insertDivider()
        assertEquals("Secret pin is 4321 ok", editor.text)
        assertEquals(4, blockedCallbacks.size)

        // 5. Clipboard Copy:
        //    - Only spoiler selected -> Blocked
        val onlyCopy = editor.prepareClipboardCopy(TextRange(14, 18))
        assertTrue(onlyCopy is RichTextEditorState.SpoilerCopyResult.Blocked)

        //    - Full paragraph selected -> Masked with normal text intact and garbage chars replacing "4321"
        val paraCopy = editor.prepareClipboardCopy(TextRange(0, editor.text.length))
        assertTrue(paraCopy is RichTextEditorState.SpoilerCopyResult.Masked)
        val maskedText = (paraCopy as RichTextEditorState.SpoilerCopyResult.Masked).text
        assertTrue(maskedText.startsWith("Secret pin is "))
        assertTrue(maskedText.endsWith(" ok"))
        assertFalse(maskedText.contains("4321"))
        val replacedPart = maskedText.substring(14, 18)
        assertEquals(4, replacedPart.length)
        assertTrue(replacedPart.all { it in RichTextEditorState.SPOILER_GARBAGE_CHARS })

        // Preview also masks the secret
        val preview = editor.maskedPlainPreview(140)
        assertFalse(preview.contains("4321"))

        // 6. HTML round-trip preserves locked Spoiler span
        val html = editor.toHtml()
        assertTrue(html.contains("data-spoiler="))
        val reimported = RichTextEditorState.fromHtml(html)
        assertEquals(editor.text, reimported.text)
        assertEquals(editor.spans, reimported.spans)

        // 7. Wrong passcode fails; correct passcode unlocks -> reveals text, allows plain copy, deletion & removal
        assertFalse(reimported.unlockSpoiler(spoilerStyle.id, "wrong"))
        assertFalse(reimported.isSpoilerUnlocked(spoilerStyle))
        assertTrue(reimported.unlockSpoiler(spoilerStyle.id, "pass99"))
        assertTrue(reimported.isSpoilerUnlocked(spoilerStyle))

        val unlockedVisual = RichTextVisualTransformation(
            spans = reimported.spans,
            unlockedSpoilerIds = reimported.unlockedSpoilerIds
        ).filter(androidx.compose.ui.text.AnnotatedString(reimported.text)).text
        assertFalse(
            unlockedVisual.spanStyles.any {
                it.start == 14 && it.end == 18 && it.item.color == androidx.compose.ui.graphics.Color.Transparent
            }
        )

        val unlockedCopy = reimported.prepareClipboardCopy(TextRange(14, 18))
        assertEquals(RichTextEditorState.SpoilerCopyResult.AllowPlain("4321"), unlockedCopy)

        // Re-lock works, then unlock again and remove spoiler cleanly
        reimported.lockSpoiler(spoilerStyle.id)
        assertFalse(reimported.isSpoilerUnlocked(spoilerStyle))
        assertFalse(reimported.removeSpoilerFromRange(TextRange(14, 18)))
        assertTrue(reimported.unlockSpoiler(spoilerStyle.id, "pass99"))
        assertTrue(reimported.removeSpoilerFromRange(TextRange(14, 18)))
        assertTrue(reimported.spans.isEmpty())
    }
}

