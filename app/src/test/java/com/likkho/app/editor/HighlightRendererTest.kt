package com.likkho.app.editor

import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightRendererTest {

    @Test
    fun highlightBounds_strictlyAscenderToDescender_excludesExtraLineHeightSpace() {
        // Line height = 54px (e.g. 27.sp), while typographic ascender..descender is -28px..+8px (total 36px)
        // Baseline at y = 40f inside a line from 0f..54f -> 12px of extra leading above ascender, 6px below descender
        val text = "Hello World"
        val spans = listOf(Span(6, 11, SpanStyle.Highlight("#fef08a")))

        val rects = HighlightLayoutCalculator.computeHighlightRects(
            text = text,
            spans = spans,
            lineCount = 1,
            ascentPx = -28f,
            descentPx = 8f,
            getLineForOffset = { 0 },
            getLineStart = { 0 },
            getLineEnd = { text.length },
            getLineBaseline = { 40f },
            getLineTop = { 0f },
            getLineBottom = { 54f },
            getCharLeft = { idx -> idx * 10f },
            getCharRight = { idx -> (idx + 1) * 10f }
        )

        assertEquals(1, rects.size)
        val r = rects[0]
        assertEquals(60f, r.left, 0.01f)
        assertEquals(110f, r.right, 0.01f)
        // Must NOT start at lineTop (0f) or end at lineBottom (54f)!
        // Must be baseline + ascentPx = 40 - 28 = 12f, and baseline + descentPx = 40 + 8 = 48f
        assertEquals(12f, r.top, 0.01f)
        assertEquals(48f, r.bottom, 0.01f)
        assertEquals("#fef08a", r.colorHex)
    }

    @Test
    fun multiLineWrappedHighlight_splitsPerLine_keepsGapBetweenLines() {
        // Line 0: "Hello " (0..6), top=0, baseline=40, bottom=54
        // Line 1: "World"  (6..11), top=54, baseline=94, bottom=108
        val text = "Hello World"
        val spans = listOf(Span(3, 9, SpanStyle.Highlight("#bbf7d0")))

        val rects = HighlightLayoutCalculator.computeHighlightRects(
            text = text,
            spans = spans,
            lineCount = 2,
            ascentPx = -28f,
            descentPx = 8f,
            getLineForOffset = { off -> if (off < 6) 0 else 1 },
            getLineStart = { line -> if (line == 0) 0 else 6 },
            getLineEnd = { line -> if (line == 0) 6 else 11 },
            getLineBaseline = { line -> if (line == 0) 40f else 94f },
            getLineTop = { line -> if (line == 0) 0f else 54f },
            getLineBottom = { line -> if (line == 0) 54f else 108f },
            getCharLeft = { idx -> if (idx < 6) idx * 10f else (idx - 6) * 10f },
            getCharRight = { idx -> if (idx < 6) (idx + 1) * 10f else (idx - 5) * 10f }
        )

        assertEquals(2, rects.size)
        // Line 0 rect: offsets 3..6 -> left 30, right 60, top 12, bottom 48
        assertEquals(30f, rects[0].left, 0.01f)
        assertEquals(60f, rects[0].right, 0.01f)
        assertEquals(12f, rects[0].top, 0.01f)
        assertEquals(48f, rects[0].bottom, 0.01f)

        // Line 1 rect: offsets 6..9 -> left 0, right 30, top 66, bottom 102
        assertEquals(0f, rects[1].left, 0.01f)
        assertEquals(30f, rects[1].right, 0.01f)
        assertEquals(66f, rects[1].top, 0.01f)
        assertEquals(102f, rects[1].bottom, 0.01f)

        // Notice gap between line 0 bottom (48) and line 1 top (66) -> 18px line spacing untouched!
        assertTrue(rects[1].top > rects[0].bottom)
    }

    @Test
    fun newlineOnlySegment_isNotHighlighted() {
        val text = "Hello\n\nWorld"
        // Span covers entire text including empty middle line '\n' at index 6
        val spans = listOf(Span(0, text.length, SpanStyle.Highlight("#fde047")))

        val rects = HighlightLayoutCalculator.computeHighlightRects(
            text = text,
            spans = spans,
            lineCount = 3,
            ascentPx = -28f,
            descentPx = 8f,
            getLineForOffset = { off ->
                when {
                    off <= 5 -> 0
                    off == 6 -> 1
                    else -> 2
                }
            },
            getLineStart = { line ->
                when (line) {
                    0 -> 0
                    1 -> 6
                    else -> 7
                }
            },
            getLineEnd = { line ->
                when (line) {
                    0 -> 6
                    1 -> 7
                    else -> 12
                }
            },
            getLineBaseline = { line -> 40f + line * 54f },
            getLineTop = { line -> line * 54f },
            getLineBottom = { line -> (line + 1) * 54f },
            getCharLeft = { idx ->
                when {
                    idx < 5 -> idx * 10f
                    idx >= 7 -> (idx - 7) * 10f
                    else -> 0f
                }
            },
            getCharRight = { idx ->
                when {
                    idx < 5 -> (idx + 1) * 10f
                    idx >= 7 -> (idx - 6) * 10f
                    else -> 0f
                }
            }
        )

        // Only lines 0 ("Hello") and 2 ("World") should have highlight rects; empty line 1 skipped
        assertEquals(2, rects.size)
        assertEquals(0f, rects[0].left, 0.01f)
        assertEquals(50f, rects[0].right, 0.01f)
        assertEquals(0f, rects[1].left, 0.01f)
        assertEquals(50f, rects[1].right, 0.01f)
    }

    @Test
    fun headingLineHighlight_usesPerLineHeadingAscenderToDescender() {
        val text = "Title\nBody"
        val spans = listOf(
            Span(0, 5, SpanStyle.Heading(1)),
            Span(0, 5, SpanStyle.Highlight("#fef08a")),
            Span(6, 10, SpanStyle.Highlight("#bbf7d0"))
        )

        val rects = HighlightLayoutCalculator.computeHighlightRects(
            text = text,
            spans = spans,
            lineCount = 2,
            ascentPx = -28f,
            descentPx = 8f,
            getLineForOffset = { off -> if (off < 6) 0 else 1 },
            getLineStart = { line -> if (line == 0) 0 else 6 },
            getLineEnd = { line -> if (line == 0) 6 else 10 },
            getLineBaseline = { line -> if (line == 0) 64f else 120f },
            getLineTop = { line -> if (line == 0) 0f else 80f },
            getLineBottom = { line -> if (line == 0) 80f else 134f },
            getCharLeft = { idx -> if (idx < 6) idx * 14f else (idx - 6) * 10f },
            getCharRight = { idx -> if (idx < 6) (idx + 1) * 14f else (idx - 5) * 10f },
            lineMetrics = { segStart, segEnd ->
                if (segStart < 5 && segEnd > 0) -48f to 12f else -28f to 8f
            }
        )

        assertEquals(2, rects.size)
        // Line 0 (H1): baseline=64, ascent=-48, descent=+12 -> top=16, bottom=76
        assertEquals(16f, rects[0].top, 0.01f)
        assertEquals(76f, rects[0].bottom, 0.01f)
        // Line 1 (Body): baseline=120, ascent=-28, descent=+8 -> top=92, bottom=128
        assertEquals(92f, rects[1].top, 0.01f)
        assertEquals(128f, rects[1].bottom, 0.01f)
    }

    @Test
    fun visibleLineRange_matchesFullComputationSubset_andBinarySearchCulls10kRects() {
        val text = (0 until 100).joinToString("\n") { "Line $it" }
        val lineStarts = IntArray(100)
        val lineEnds = IntArray(100)
        var pos = 0
        for (i in 0 until 100) {
            lineStarts[i] = pos
            val lineLen = "Line $i".length
            pos += lineLen + if (i < 99) 1 else 0
            lineEnds[i] = pos
        }
        fun lineFor(off: Int): Int {
            var lo = 0
            var hi = 99
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (lineStarts[mid] <= off) lo = mid + 1 else hi = mid - 1
            }
            return hi.coerceIn(0, 99)
        }
        val spans = listOf(
            Span(0, text.length, SpanStyle.Highlight("#fef08a"))
        )
        val full = HighlightLayoutCalculator.computeHighlightRects(
            text = text,
            spans = spans,
            lineCount = 100,
            ascentPx = -28f,
            descentPx = 8f,
            getLineForOffset = ::lineFor,
            getLineStart = { lineStarts[it] },
            getLineEnd = { lineEnds[it] },
            getLineBaseline = { it * 40f + 30f },
            getLineTop = { it * 40f },
            getLineBottom = { (it + 1) * 40f },
            getCharLeft = { idx -> (idx - lineStarts[lineFor(idx)]) * 10f },
            getCharRight = { idx -> (idx - lineStarts[lineFor(idx)] + 1) * 10f }
        )
        val band = 20..35
        val windowed = HighlightLayoutCalculator.computeHighlightRects(
            text = text,
            spans = spans,
            lineCount = 100,
            ascentPx = -28f,
            descentPx = 8f,
            getLineForOffset = ::lineFor,
            getLineStart = { lineStarts[it] },
            getLineEnd = { lineEnds[it] },
            getLineBaseline = { it * 40f + 30f },
            getLineTop = { it * 40f },
            getLineBottom = { (it + 1) * 40f },
            getCharLeft = { idx -> (idx - lineStarts[lineFor(idx)]) * 10f },
            getCharRight = { idx -> (idx - lineStarts[lineFor(idx)] + 1) * 10f },
            visibleLineRange = band
        )
        assertEquals(full.subList(20, 36), windowed)

        // Binary-search culling over 10,000 sorted rects
        val many = List(10_000) { i ->
            CachedHighlightRect(
                left = 0f,
                top = i * 40f,
                right = 100f,
                bottom = i * 40f + 30f,
                color = androidx.compose.ui.graphics.Color.Yellow
            )
        }
        val visibleSlice = collectVisibleHighlightIndices(many, visibleTopPx = 200_000f, visibleBottomPx = 201_000f)
        // Expected rects start around index 4992 (with margin) and end at top <= 201_000 (index 5025)
        assertTrue(5000 in visibleSlice)
        assertTrue(5025 in visibleSlice)
        assertTrue(visibleSlice.last < 5030)
        assertTrue(visibleSlice.count() < 50)
    }
}
