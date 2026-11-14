package com.likkho.app.editor

import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import org.junit.Assert.*
import org.junit.Test

class BlockDecorationRendererTest {

    @Test
    fun quoteBlocks_mergeContiguousLines_andSeparateNonContiguousBlocks() {
        // Lines:
        // 0: "q1\n" [0..3) top 0..30
        // 1: "q2\n" [3..6) top 30..60
        // 2: "body\n" [6..11) top 60..90
        // 3: "q3" [11..13) top 90..120
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
            getLineForOffset = { off -> lineRanges.indexOfFirst { off in it.first until it.second }.coerceAtLeast(0) },
            getLineStart = { lineRanges[it].first },
            getLineEnd = { lineRanges[it].second },
            getLineTop = { it * 30f },
            getLineBottom = { (it + 1) * 30f }
        )

        assertEquals(2, blocks.size)
        assertEquals(QuoteBlockRect(left = -8f, top = 0f, right = 304f, bottom = 60f, isFullLine = true), blocks[0])
        assertEquals(QuoteBlockRect(left = -8f, top = 90f, right = 304f, bottom = 120f, isFullLine = true), blocks[1])
    }

    @Test
    fun quoteBlocks_midLineQuote_onlyCoversQuotedRangeAfterDefaultText() {
        val text = "normal quoted"
        val spans = listOf(Span(7, 13, SpanStyle.Quote))

        val blocks = BlockDecorationLayoutCalculator.computeQuoteBlocks(
            text = text,
            spans = spans,
            lineCount = 1,
            canvasWidth = 300f,
            blockOutsetLeftPx = 8f,
            blockOutsetRightPx = 4f,
            inlinePadPx = 5f,
            getLineForOffset = { 0 },
            getLineStart = { 0 },
            getLineEnd = { text.length },
            getLineTop = { 0f },
            getLineBottom = { 30f },
            getCharLeft = { idx -> idx * 10f },
            getCharRight = { idx -> (idx + 1) * 10f }
        )

        assertEquals(1, blocks.size)
        assertFalse(blocks[0].isFullLine)
        assertEquals(65f, blocks[0].left, 0.01f) // char 7 left=70 - 5 pad
        assertEquals(135f, blocks[0].right, 0.01f) // char 12 right=130 + 5 pad
    }

    @Test
    fun codeBoxes_detectsMultiLineBlock_andInlineCodeSpan() {
        // Lines:
        // 0: "c1\n" [0..3)
        // 1: "c2\n" [3..6)
        // 2: "say hi" [6..12) -> inline code "hi" [10..12)
        val text = "c1\nc2\nsay hi"
        val lineRanges = listOf(0 to 3, 3 to 6, 6 to 12)
        val spans = listOf(
            Span(0, 5, SpanStyle.Code),
            Span(10, 12, SpanStyle.Code)
        )

        val boxes = BlockDecorationLayoutCalculator.computeCodeBoxes(
            text = text,
            spans = spans,
            lineCount = lineRanges.size,
            canvasWidth = 300f,
            inlinePadPx = 4f,
            getLineForOffset = { off -> lineRanges.indexOfFirst { off in it.first until it.second }.coerceAtLeast(0) },
            getLineStart = { lineRanges[it].first },
            getLineEnd = { lineRanges[it].second },
            getLineTop = { it * 30f },
            getLineBottom = { (it + 1) * 30f },
            getCharLeft = { idx -> (idx - lineRanges[2].first) * 10f },
            getCharRight = { idx -> (idx - lineRanges[2].first + 1) * 10f }
        )

        assertEquals(2, boxes.size)
        assertTrue(boxes[0].isBlock)
        assertEquals(0f, boxes[0].left, 0.01f)
        assertEquals(300f, boxes[0].right, 0.01f)
        assertEquals(0f, boxes[0].top, 0.01f)
        assertEquals(60f, boxes[0].bottom, 0.01f)

        assertFalse(boxes[1].isBlock)
        assertEquals(36f, boxes[1].left, 0.01f) // char 10 left=40 - 4 pad
        assertEquals(64f, boxes[1].right, 0.01f) // char 11 right=60 + 4 pad
    }
}
