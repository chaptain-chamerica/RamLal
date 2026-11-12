package com.likkho.app.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentModelTest {

    @Test
    fun fromPlainText_and_plainPreview_worksCorrectly() {
        val plain = "First line of diary\nSecond line with some thoughts\nThird line"
        val doc = Document.fromPlainText(plain)

        assertEquals(3, doc.blocks.size)
        assertEquals("First line of diary", doc.blocks[0].text)
        assertEquals("Second line with some thoughts", doc.blocks[1].text)
        assertEquals("Third line", doc.blocks[2].text)

        val preview = doc.plainPreview(maxLength = 30)
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.length <= 31)

        val emptyDoc = Document.fromPlainText("")
        assertEquals(1, emptyDoc.blocks.size)
        assertEquals("", emptyDoc.blocks[0].text)
        assertEquals("", emptyDoc.plainPreview())
    }

    @Test
    fun applyStyle_mergesOverlappingAndAdjacentSpans() {
        val block = Block(id = "b1", text = "The quick brown fox jumps over the lazy dog")
        var doc = Document(blocks = listOf(block))

        // Apply bold to [4, 9) "quick"
        doc = doc.applyStyle("b1", 4, 9, SpanStyle.Bold)
        assertEquals(1, doc.blocks[0].spans.size)
        assertEquals(Span(4, 9, SpanStyle.Bold), doc.blocks[0].spans[0])

        // Apply adjacent bold to [9, 15) " brown" -> should merge into [4, 15)
        doc = doc.applyStyle("b1", 9, 15, SpanStyle.Bold)
        assertEquals(1, doc.blocks[0].spans.size)
        assertEquals(Span(4, 15, SpanStyle.Bold), doc.blocks[0].spans[0])

        // Apply overlapping bold to [10, 20) -> should extend to [4, 20)
        doc = doc.applyStyle("b1", 10, 20, SpanStyle.Bold)
        assertEquals(1, doc.blocks[0].spans.size)
        assertEquals(Span(4, 20, SpanStyle.Bold), doc.blocks[0].spans[0])

        // Apply different style (Italic) to [4, 10) -> both preserved
        doc = doc.applyStyle("b1", 4, 10, SpanStyle.Italic)
        assertEquals(2, doc.blocks[0].spans.size)
        assertTrue(doc.blocks[0].spans.contains(Span(4, 20, SpanStyle.Bold)))
        assertTrue(doc.blocks[0].spans.contains(Span(4, 10, SpanStyle.Italic)))
    }

    @Test
    fun applyStyle_inHindiAndEnglishMixedText() {
        // "Hello नमस्ते World"
        // "Hello " = 6 chars
        // "नमस्ते" = 6 UTF-16 code units (न म स ् त े)
        // " World" = 6 chars
        // Total = 18 chars
        val mixedText = "Hello नमस्ते World"
        val block = Block(id = "b_hindi", text = mixedText)
        var doc = Document(blocks = listOf(block))

        val hindiStart = 6
        val hindiEnd = 6 + "नमस्ते".length // 12
        assertEquals(12, hindiEnd)

        // Apply color to Hindi word
        val hindiColor = SpanStyle.Color("#FF5722")
        doc = doc.applyStyle("b_hindi", hindiStart, hindiEnd, hindiColor)

        assertEquals(1, doc.blocks[0].spans.size)
        val span = doc.blocks[0].spans[0]
        assertEquals(6, span.start)
        assertEquals(12, span.end)
        assertEquals(hindiColor, span.style)

        // Apply italic to "World" [13, 18)
        doc = doc.applyStyle("b_hindi", 13, 18, SpanStyle.Italic)
        assertEquals(2, doc.blocks[0].spans.size)
    }

    @Test
    fun removeStyle_splitsAndShrinksSpansProperly() {
        val block = Block(
            id = "b1",
            text = "0123456789ABCDEF",
            spans = listOf(Span(2, 14, SpanStyle.Bold))
        )
        var doc = Document(blocks = listOf(block))

        // Remove bold from middle [6, 10) -> splits into [2, 6) and [10, 14)
        doc = doc.removeStyle("b1", 6, 10, SpanStyle.Bold)
        assertEquals(2, doc.blocks[0].spans.size)
        assertEquals(Span(2, 6, SpanStyle.Bold), doc.blocks[0].spans[0])
        assertEquals(Span(10, 14, SpanStyle.Bold), doc.blocks[0].spans[1])

        // Remove from start of second span [8, 12) -> shrinks second span to [12, 14)
        doc = doc.removeStyle("b1", 8, 12, SpanStyle.Bold)
        assertEquals(2, doc.blocks[0].spans.size)
        assertEquals(Span(2, 6, SpanStyle.Bold), doc.blocks[0].spans[0])
        assertEquals(Span(12, 14, SpanStyle.Bold), doc.blocks[0].spans[1])

        // Remove completely by enclosing range [0, 16)
        doc = doc.removeStyle("b1", 0, 16, SpanStyle.Bold)
        assertTrue(doc.blocks[0].spans.isEmpty())
    }

    @Test
    fun toggleStyle_appliesWhenMissing_andRemovesWhenPresent() {
        val block = Block(id = "b1", text = "Selection Test")
        var doc = Document(blocks = listOf(block))

        // Toggle on: range [0, 9)
        doc = doc.toggleStyle("b1", 0, 9, SpanStyle.Underline)
        assertEquals(1, doc.blocks[0].spans.size)
        assertEquals(Span(0, 9, SpanStyle.Underline), doc.blocks[0].spans[0])

        // Toggle off: exact range [0, 9)
        doc = doc.toggleStyle("b1", 0, 9, SpanStyle.Underline)
        assertTrue(doc.blocks[0].spans.isEmpty())
    }

    @Test
    fun insertText_shiftsSpansAndHandlesEmojiCorrectly() {
        // Emoji 😀 is a surrogate pair (2 UTF-16 code units)
        val text = "Hello 😀 World"
        // 'H','e','l','l','o',' ' (6)
        // 😀 (2 code units, offsets 6 and 7)
        // ' ', 'W','o','r','l','d' (offsets 8 to 14)
        val block = Block(
            id = "b1",
            text = text,
            spans = listOf(
                Span(0, 5, SpanStyle.Bold),           // "Hello"
                Span(9, 14, SpanStyle.Italic)         // "World"
            )
        )
        var doc = Document(blocks = listOf(block))

        // 1. Insert emoji "🌟" (length 2) + " " before "World" at offset 9
        doc = doc.insertText("b1", 9, "🌟 ")
        val updatedText = doc.blocks[0].text
        assertEquals("Hello 😀 🌟 World", updatedText)

        // "Hello" span [0, 5) is before insertion -> stays [0, 5)
        val boldSpan = doc.blocks[0].spans.find { it.style == SpanStyle.Bold }
        assertNotNull(boldSpan)
        assertEquals(0, boldSpan!!.start)
        assertEquals(5, boldSpan.end)

        // "World" span originally at [9, 14) shifted by 3 ("🌟 " = 2 + 1 = 3 chars) -> [12, 17)
        val italicSpan = doc.blocks[0].spans.find { it.style == SpanStyle.Italic }
        assertNotNull(italicSpan)
        assertEquals(9 + 3, italicSpan!!.start)
        assertEquals(14 + 3, italicSpan.end)

        // 2. Insert inside a span: insert "Big " inside "World" at offset 13 (after 'W')
        doc = doc.insertText("b1", 13, "Big ")
        val expandedItalic = doc.blocks[0].spans.find { it.style == SpanStyle.Italic }
        assertNotNull(expandedItalic)
        // Span started at 12, ends at 17 + 4 = 21
        assertEquals(12, expandedItalic!!.start)
        assertEquals(17 + 4, expandedItalic.end)
    }

    @Test
    fun deleteRange_shiftsAndShrinksSpansCorrectly() {
        val text = "Apple Banana Cherry"
        val block = Block(
            id = "b1",
            text = text,
            spans = listOf(
                Span(0, 5, SpanStyle.Bold),     // "Apple"
                Span(6, 12, SpanStyle.Italic),  // "Banana"
                Span(13, 19, SpanStyle.Strike)  // "Cherry"
            )
        )
        var doc = Document(blocks = listOf(block))

        // Delete " Banana" (offset 5 to 12)
        doc = doc.deleteRange("b1", 5, 12)
        assertEquals("Apple Cherry", doc.blocks[0].text)

        // "Apple" span untouched
        assertEquals(Span(0, 5, SpanStyle.Bold), doc.blocks[0].spans[0])

        // "Banana" span was completely inside deleted range -> removed
        assertFalse(doc.blocks[0].spans.any { it.style == SpanStyle.Italic })

        // "Cherry" was [13, 19), shifted by -7 -> [6, 12)
        val strikeSpan = doc.blocks[0].spans.find { it.style == SpanStyle.Strike }
        assertNotNull(strikeSpan)
        assertEquals(6, strikeSpan!!.start)
        assertEquals(12, strikeSpan.end)
    }

    @Test
    fun splitBlock_partitionsSpansAndPreservesTypes() {
        val text = "FirstPartSecondPart"
        val block = Block(
            id = "b1",
            type = BlockType.Heading(1),
            text = text,
            spans = listOf(Span(5, 15, SpanStyle.Highlight("#FFFF00")))
        )
        val doc = Document(blocks = listOf(block))

        // Split at offset 9 ("FirstPart" / "SecondPart")
        val splitDoc = doc.splitBlock("b1", 9, newBlockId = "b2")
        assertEquals(2, splitDoc.blocks.size)

        val b1 = splitDoc.blocks[0]
        val b2 = splitDoc.blocks[1]

        assertEquals("FirstPart", b1.text)
        assertEquals("SecondPart", b2.text)

        // b1 is Heading(1), b2 transitions to Paragraph
        assertEquals(BlockType.Heading(1), b1.type)
        assertEquals(BlockType.Paragraph, b2.type)

        // Span originally [5, 15) crossing split point 9:
        // b1 gets [5, 9)
        assertEquals(1, b1.spans.size)
        assertEquals(Span(5, 9, SpanStyle.Highlight("#FFFF00")), b1.spans[0])

        // b2 gets [0, 6) (15 - 9 = 6)
        assertEquals(1, b2.spans.size)
        assertEquals(Span(0, 6, SpanStyle.Highlight("#FFFF00")), b2.spans[0])
    }

    @Test
    fun mergeBlocks_appendsTextAndShiftsSpans() {
        val b1 = Block(
            id = "b1",
            text = "Hello ",
            spans = listOf(Span(0, 5, SpanStyle.Bold))
        )
        val b2 = Block(
            id = "b2",
            text = "World",
            spans = listOf(Span(0, 5, SpanStyle.Italic))
        )
        val doc = Document(blocks = listOf(b1, b2))

        val merged = doc.mergeBlocks("b1", "b2")
        assertEquals(1, merged.blocks.size)

        val block = merged.blocks[0]
        assertEquals("Hello World", block.text)
        assertEquals(2, block.spans.size)

        assertEquals(Span(0, 5, SpanStyle.Bold), block.spans[0])
        // Second span shifted by 6 ("Hello ".length) -> [6, 11)
        assertEquals(Span(6, 11, SpanStyle.Italic), block.spans[1])
    }

    @Test
    fun changeBlockType_switchesTypeAndPreservesData() {
        val block = Block(id = "b1", text = "Complete task")
        var doc = Document(blocks = listOf(block))

        // Change to Todo checked
        doc = doc.changeBlockType("b1", BlockType.Todo(checked = true))
        assertTrue(doc.blocks[0].type is BlockType.Todo)
        assertTrue(doc.blocks[0].checked)

        // Change to Heading level 2
        doc = doc.changeBlockType("b1", BlockType.Heading(2))
        assertEquals(BlockType.Heading(2), doc.blocks[0].type)
        assertEquals("Complete task", doc.blocks[0].text)

        // Change to LaTeX formula
        doc = doc.changeBlockType("b1", BlockType.Latex("\\int x dx"))
        assertEquals(BlockType.Latex("\\int x dx"), doc.blocks[0].type)

        // Change to Spoiler with passcode hash
        doc = doc.changeBlockType("b1", BlockType.Spoiler(passcodeHash = "hash123", text = "Secret text"))
        val spoiler = doc.blocks[0].type as BlockType.Spoiler
        assertEquals("hash123", spoiler.passcodeHash)
    }

    @Test
    fun invalidRange_isSafelyClamped_neverCrashes() {
        val block = Block(id = "b1", text = "Safe text")
        var doc = Document(blocks = listOf(block))

        // Negative start, massive end
        doc = doc.applyStyle("b1", -10, 500, SpanStyle.Bold)
        assertEquals(1, doc.blocks[0].spans.size)
        assertEquals(0, doc.blocks[0].spans[0].start)
        assertEquals("Safe text".length, doc.blocks[0].spans[0].end)

        // start > end (inverted range)
        doc = doc.applyStyle("b1", 7, 2, SpanStyle.Italic)
        // min/max coerce makes it [2, 7)
        assertTrue(doc.blocks[0].spans.any { it.style == SpanStyle.Italic && it.start == 2 && it.end == 7 })

        // Out of bounds delete
        doc = doc.deleteRange("b1", -50, 1000)
        assertEquals("", doc.blocks[0].text)
        assertTrue(doc.blocks[0].spans.isEmpty())

        // Non-existent block ID: no-op, safe
        val untouched = doc.applyStyle("non_existent_id", 0, 5, SpanStyle.Bold)
        assertEquals(doc, untouched)
    }

    @Test
    fun jsonSaveLoad_roundTrip_preservesAllBlockTypesAndSpans() {
        val originalDoc = Document(
            schemaVersion = 1,
            blocks = listOf(
                Block(
                    id = "b_head",
                    type = BlockType.Heading(1),
                    text = "Likkho Editorial Spec",
                    spans = listOf(Span(0, 6, SpanStyle.Bold), Span(7, 16, SpanStyle.FontId("cormorant"))),
                    alignment = TextAlignment.CENTER
                ),
                Block(
                    id = "b_todo",
                    type = BlockType.Todo(checked = true),
                    text = "Finish data model",
                    spans = listOf(Span(0, 6, SpanStyle.Strike)),
                    checked = true,
                    indent = 1
                ),
                Block(
                    id = "b_bullet",
                    type = BlockType.Bullet,
                    text = "Bullet point item",
                    indent = 2
                ),
                Block(
                    id = "b_latex",
                    type = BlockType.Latex("E = mc^2"),
                    text = "E = mc^2",
                    formula = "E = mc^2"
                ),
                Block(
                    id = "b_spoiler",
                    type = BlockType.Spoiler(passcodeHash = "abc123salt", text = "Top secret confession"),
                    text = "Top secret confession",
                    passcodeHash = "abc123salt"
                ),
                Block(
                    id = "b_divider",
                    type = BlockType.Divider,
                    text = ""
                )
            )
        )

        // JSON Serialization round trip
        val jsonString = originalDoc.toJson()
        assertTrue(jsonString.contains("\"schemaVersion\":1"))

        val decodedDoc = Document.fromJson(jsonString)
        assertEquals(originalDoc.schemaVersion, decodedDoc.schemaVersion)
        assertEquals(originalDoc.blocks.size, decodedDoc.blocks.size)

        for (i in originalDoc.blocks.indices) {
            val orig = originalDoc.blocks[i]
            val dec = decodedDoc.blocks[i]
            assertEquals(orig.id, dec.id)
            assertEquals(orig.type, dec.type)
            assertEquals(orig.text, dec.text)
            assertEquals(orig.spans, dec.spans)
            assertEquals(orig.alignment, dec.alignment)
            assertEquals(orig.indent, dec.indent)
        }
    }

    @Test
    fun storageLayer_contentBlockRecord_interoperabilityRoundTrip() {
        val doc = Document(
            blocks = listOf(
                Block(
                    id = "b_rec1",
                    type = BlockType.Heading(2),
                    text = "Section Title",
                    spans = listOf(
                        Span(0, 7, SpanStyle.Color("#336699")),
                        Span(8, 13, SpanStyle.Link("https://likkho.app"))
                    ),
                    alignment = TextAlignment.RIGHT,
                    indent = 1
                ),
                Block(
                    id = "b_rec2",
                    type = BlockType.Todo(checked = false),
                    text = "Unchecked item",
                    checked = false
                )
            )
        )

        // Convert to ContentBlockRecords
        val records = doc.toContentBlockRecords()
        assertEquals(2, records.size)
        assertEquals("heading2", records[0].type)
        assertEquals("right", records[0].alignment)
        assertEquals(2, records[0].spans.size)
        assertEquals("todo", records[1].type)
        assertEquals(false, records[1].checked)

        // Convert back from records
        val reconstructedDoc = Document.fromContentBlockRecords(records)
        assertEquals(2, reconstructedDoc.blocks.size)
        assertEquals(BlockType.Heading(2), reconstructedDoc.blocks[0].type)
        assertEquals(TextAlignment.RIGHT, reconstructedDoc.blocks[0].alignment)
        assertEquals(2, reconstructedDoc.blocks[0].spans.size)
        assertEquals("https://likkho.app", (reconstructedDoc.blocks[0].spans[1].style as SpanStyle.Link).url)
        assertEquals(BlockType.Todo(checked = false), reconstructedDoc.blocks[1].type)
    }
}
