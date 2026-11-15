package com.likkho.app.editor

import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import org.junit.Assert.*
import org.junit.Test

class BlockDecorationRendererTest {

    @Test
    fun quoteBlocks_hugTextWidth_mergeContiguousLines_andSeparateNonContiguousBlocks() {
        // Lines:
        // 0: "q1\n" [0..3) -> visible chars [0..2), x: 0..20
        // 1: "q2\n" [3..6) -> visible chars [3..5), x: 0..20
        // 2: "body\n" [6..11)
        // 3: "q3" [11..13) -> visible chars [11..13), x: 0..20
        val text = "q1\nq2\nbody\nq3"
        val lineRanges = listOf(0 to 3, 3 to 6, 6 to 11, 11 to 13)
        val spans = listOf(
            Span(0, 5, SpanStyle.Quote),
            Span(11, 13, SpanStyle.Quote)
        )

        val blocks = BlockDecorationLayoutCalculator.computeQuoteBlocks(
            text = text,
            spans = spans,
            lineCount = lineRanges.size,
            canvasWidth = 300f,
            blockOutsetLeftPx = 8f,
            blockOutsetRightPx = 4f,
            inlinePadPx = 5f,
            barWidthPx = 3f,
            gapFromPrevPx = 1f,
            verticalInsetPx = 1f,
            getLineForOffset = { off -> lineRanges.indexOfFirst { off in it.first until it.second }.coerceAtLeast(0) },
            getLineStart = { lineRanges[it].first },
            getLineEnd = { lineRanges[it].second },
            getLineTop = { it * 30f },
            getLineBottom = { (it + 1) * 30f },
            getCharLeft = { idx ->
                val line = lineRanges.indexOfFirst { idx in it.first until it.second }
                (idx - lineRanges[line].first) * 10f
            },
            getCharRight = { idx ->
                val line = lineRanges.indexOfFirst { idx in it.first until it.second }
                (idx - lineRanges[line].first + 1) * 10f
            }
        )

        assertEquals(2, blocks.size)
        // Tightly wraps text [0..20] + padding (-8..24), NOT full 300f line width!
        assertEquals(
            QuoteBlockRect(left = -8f, top = 1f, right = 24f, bottom = 59f, barWidth = 3f, isFullLine = true),
            blocks[0]
        )
        assertEquals(
            QuoteBlockRect(left = -8f, top = 91f, right = 24f, bottom = 119f, barWidth = 3f, isFullLine = true),
            blocks[1]
        )
    }

    @Test
    fun quoteBlocks_midLineQuote_neverOverlapsPreviousText_withOrWithoutSpace() {
        // Case 1: With space: "normal quoted" -> 'l' right is 60, ' ' is [60..70), 'q' left is 70
        val textWithSpace = "normal quoted"
        val blocksWithSpace = BlockDecorationLayoutCalculator.computeQuoteBlocks(
            text = textWithSpace,
            spans = listOf(Span(7, 13, SpanStyle.Quote)),
            lineCount = 1,
            canvasWidth = 300f,
            blockOutsetLeftPx = 8f,
            blockOutsetRightPx = 4f,
            inlinePadPx = 15f, // intentionally huge pad to prove clamping protects 'l'
            barWidthPx = 3f,
            gapFromPrevPx = 2f,
            verticalInsetPx = 1f,
            getLineForOffset = { 0 },
            getLineStart = { 0 },
            getLineEnd = { textWithSpace.length },
            getLineTop = { 0f },
            getLineBottom = { 30f },
            getCharLeft = { idx -> idx * 10f },
            getCharRight = { idx -> (idx + 1) * 10f }
        )

        assertEquals(1, blocksWithSpace.size)
        assertFalse(blocksWithSpace[0].isFullLine)
        // 'l' ends at 60; left must be >= 60 + 2 = 62 (never overlapping "normal")
        assertTrue(blocksWithSpace[0].left >= 62f)
        assertEquals(134f, blocksWithSpace[0].right, 0.01f)

        // Case 2: No space: "normalquoted" -> 'l' ends at 60, 'q' starts at 60
        val textNoSpace = "normalquoted"
        val blocksNoSpace = BlockDecorationLayoutCalculator.computeQuoteBlocks(
            text = textNoSpace,
            spans = listOf(Span(6, 12, SpanStyle.Quote)),
            lineCount = 1,
            canvasWidth = 300f,
            blockOutsetLeftPx = 8f,
            blockOutsetRightPx = 4f,
            inlinePadPx = 10f,
            barWidthPx = 3f,
            gapFromPrevPx = 2f,
            verticalInsetPx = 1f,
            getLineForOffset = { 0 },
            getLineStart = { 0 },
            getLineEnd = { textNoSpace.length },
            getLineTop = { 0f },
            getLineBottom = { 30f },
            getCharLeft = { idx -> idx * 10f },
            getCharRight = { idx -> (idx + 1) * 10f }
        )
        assertEquals(1, blocksNoSpace.size)
        // Left must not go before 60 ('l' right edge)
        assertTrue(blocksNoSpace[0].left >= 60f)
    }

    @Test
    fun codeBoxes_hugTextWidth_forShortBlockAndInline_andNeverOverlapPreviousText() {
        // Lines:
        // 0: "c1\n" [0..3) -> x: 0..20
        // 1: "c23\n" [3..7) -> x: 0..30
        // 2: "say hi" [7..13) -> inline code "hi" [11..13)
        val text = "c1\nc23\nsay hi"
        val lineRanges = listOf(0 to 3, 3 to 7, 7 to 13)
        val spans = listOf(
            Span(0, 6, SpanStyle.Code),
            Span(11, 13, SpanStyle.Code)
        )

        val boxes = BlockDecorationLayoutCalculator.computeCodeBoxes(
            text = text,
            spans = spans,
            lineCount = lineRanges.size,
            canvasWidth = 300f,
            blockOutsetLeftPx = 5f,
            blockOutsetRightPx = 5f,
            inlinePadPx = 12f, // intentionally huge pad to verify clamping against 'y' (right=30)
            gapFromPrevPx = 2f,
            verticalInsetPx = 1f,
            getLineForOffset = { off -> lineRanges.indexOfFirst { off in it.first until it.second }.coerceAtLeast(0) },
            getLineStart = { lineRanges[it].first },
            getLineEnd = { lineRanges[it].second },
            getLineTop = { it * 30f },
            getLineBottom = { (it + 1) * 30f },
            getCharLeft = { idx ->
                val line = lineRanges.indexOfFirst { idx in it.first until it.second }
                (idx - lineRanges[line].first) * 10f
            },
            getCharRight = { idx ->
                val line = lineRanges.indexOfFirst { idx in it.first until it.second }
                (idx - lineRanges[line].first + 1) * 10f
            }
        )

        assertEquals(2, boxes.size)
        // Multi-line code block wraps strictly around widest line "c23" (0..30) + outsets (-5..35), NOT 300f!
        assertTrue(boxes[0].isBlock)
        assertEquals(-5f, boxes[0].left, 0.01f)
        assertEquals(35f, boxes[0].right, 0.01f)
        assertEquals(1f, boxes[0].top, 0.01f)
        assertEquals(59f, boxes[0].bottom, 0.01f)

        // Inline code "hi" starts at x=40; 'y' ends at x=30; left must be >= 32 (no overlap with "say")
        assertFalse(boxes[1].isBlock)
        assertTrue(boxes[1].left >= 32f)
        assertEquals(72f, boxes[1].right, 0.01f) // 60 + 12 pad
    }

    @Test
    fun dividerLines_computeParagraphDividers_headingDividers_activeHeadingCursorDivider_andDedup() {
        // Lines:
        // 0: "H1\n" [0..3) -> top=0, bottom=40 (Heading 1)
        // 1: "\u200B\n" [3..5) -> top=40, bottom=60 (Divider -> midY = 50f)
        // 2: "H2\n" [5..8) -> top=60, bottom=90 (Heading 2)
        // 3: "H3\n" [8..11) -> top=90, bottom=115 (Heading 3)
        // 4: "Body" [11..15) -> top=115, bottom=140 (activeHeadingLevel preview on cursor line 4)
        val div = RichTextEditorState.DIVIDER_MARKER
        val text = "H1\n$div\nH2\nH3\nBody"
        val lineRanges = listOf(0 to 3, 3 to 5, 5 to 8, 8 to 11, 11 to 15)
        val lineTops = listOf(0f, 40f, 60f, 90f, 115f)
        val lineBottoms = listOf(40f, 60f, 90f, 115f, 140f)
        val spans = listOf(
            Span(0, 2, SpanStyle.Heading(1)),
            Span(3, 4, SpanStyle.Divider),
            Span(5, 7, SpanStyle.Heading(2)),
            Span(8, 10, SpanStyle.Heading(3))
        )

        val dividers = BlockDecorationLayoutCalculator.computeDividerLines(
            text = text,
            spans = spans,
            lineCount = lineRanges.size,
            activeHeadingLevel = 1,
            cursorOffset = 13,
            getLineForOffset = { off ->
                val clamped = off.coerceIn(0, text.length)
                lineRanges.indexOfFirst { clamped in it.first until it.second }
                    .let { if (it == -1) lineRanges.lastIndex else it }
            },
            getLineTop = { lineTops[it] },
            getLineBottom = { lineBottoms[it] }
        )

        assertEquals(
            listOf(
                DividerLine(39f),  // H1 bottom (40 - 1)
                DividerLine(50f),  // Explicit paragraph divider midpoint ((40 + 60) / 2)
                DividerLine(89f),  // H2 bottom (90 - 1)
                DividerLine(114f), // H3 bottom (115 - 1)
                DividerLine(139f)  // Active heading cursor line 4 preview (140 - 1)
            ),
            dividers
        )

        // When cursor is on an empty document with H1/H2/H3 enabled, immediate divider still renders
        val emptyDocDividers = BlockDecorationLayoutCalculator.computeDividerLines(
            text = "",
            spans = emptyList(),
            lineCount = 1,
            activeHeadingLevel = 2,
            cursorOffset = 0,
            getLineForOffset = { 0 },
            getLineTop = { 0f },
            getLineBottom = { 32f }
        )
        assertEquals(listOf(DividerLine(31f)), emptyDocDividers)

        // When cursor is already inside an existing heading line, dedup prevents double divider
        val dedupedDividers = BlockDecorationLayoutCalculator.computeDividerLines(
            text = "Title",
            spans = listOf(Span(0, 5, SpanStyle.Heading(1))),
            lineCount = 1,
            activeHeadingLevel = 1,
            cursorOffset = 5,
            getLineForOffset = { 0 },
            getLineTop = { 0f },
            getLineBottom = { 40f }
        )
        assertEquals(listOf(DividerLine(39f)), dedupedDividers)
    }
}

