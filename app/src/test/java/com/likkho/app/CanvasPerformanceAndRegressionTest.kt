package com.likkho.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.likkho.app.editor.BlockDecorationLayoutCalculator
import com.likkho.app.editor.HighlightLayoutCalculator
import com.likkho.app.editor.RichTextEditorState
import com.likkho.app.editor.RichTextVisualTransformation
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle
import com.likkho.app.editor.model.TextAlignment
import com.likkho.app.editor.model.normalizeSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * Step 0: Characterization tests + reproducible JVM CPU benchmarks across
 * 500, 5,000, 20,000, and 50,000-word documents.
 */
class CanvasPerformanceAndRegressionTest {

    // =========================================================================
    // Step 0b: Characterization Tests (must remain green across every step)
    // =========================================================================

    @Test
    fun characterization_englishAndHindiImeComposition_preservesSpansAndText() {
        val editor = RichTextEditorState()
        editor.toggleBold()
        // Simulate English typing + IME composing range
        editor.onTextFieldValueChange(
            TextFieldValue("Hello ", selection = TextRange(6), composition = TextRange(0, 5))
        )
        editor.toggleBold()
        // Simulate Hindi Devanagari word typing with matras via IME composition -> commit
        editor.onTextFieldValueChange(
            TextFieldValue("Hello नम", selection = TextRange(8), composition = TextRange(6, 8))
        )
        editor.onTextFieldValueChange(
            TextFieldValue("Hello नमस्ते", selection = TextRange(12), composition = null)
        )
        assertEquals("Hello नमस्ते", editor.text)
        assertEquals(listOf(Span(0, 6, SpanStyle.Bold)), editor.spans)

        val vt = RichTextVisualTransformation(spans = editor.spans, fontResolver = { null })
        val transformed = vt.filter(AnnotatedString(editor.text))
        assertEquals("Hello नमस्ते", transformed.text.text)
        assertEquals(12, transformed.offsetMapping.originalToTransformed(12))
        assertEquals(12, transformed.offsetMapping.transformedToOriginal(12))
    }

    @Test
    fun characterization_formattingListsSpoilersAndSelectionWorkTogether() {
        val editor = RichTextEditorState(
            initialText = "Title\nItem one\nSecret",
            initialSpans = listOf(
                Span(0, 5, SpanStyle.Heading(1)),
                Span(6, 14, SpanStyle.Align(TextAlignment.CENTER)),
                Span(6, 14, SpanStyle.Indent(2))
            )
        )
        // Toggle todo on line 2
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(8)))
        editor.toggleTodo()
        assertTrue(editor.text.contains("☐ Item one"))

        // Toggle checkbox directly
        val todoSpan = editor.spans.first { it.style is SpanStyle.Todo }
        assertTrue(editor.toggleTodoAt(todoSpan.start))
        assertTrue(editor.text.contains("☑ Item one"))

        // Apply spoiler to "Secret"
        val secStart = editor.text.indexOf("Secret")
        val secEnd = secStart + "Secret".length
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(secStart, secEnd)))
        assertTrue(editor.applySpoilerToRange("1234"))
        val copyBlocked = editor.prepareClipboardCopy(TextRange(secStart, secEnd))
        assertTrue(copyBlocked is RichTextEditorState.SpoilerCopyResult.Blocked)
        val spoiler = (copyBlocked as RichTextEditorState.SpoilerCopyResult.Blocked).blockingSpoiler
        assertTrue(editor.unlockSpoiler(spoiler.id, "1234"))
        val copyPlain = editor.prepareClipboardCopy(TextRange(secStart, secEnd))
        assertEquals(RichTextEditorState.SpoilerCopyResult.AllowPlain("Secret"), copyPlain)
    }

    @Test
    fun characterization_numberedListInsertionsAndDeletion_renumberInOrder() {
        val editor = RichTextEditorState()
        editor.toggleNumbered()
        editor.onTextFieldValueChange(TextFieldValue("1. First", selection = TextRange(8)))
        editor.onTextFieldValueChange(TextFieldValue("1. First\n", selection = TextRange(9)))
        assertEquals("1. First\n2. ", editor.text)
        editor.onTextFieldValueChange(TextFieldValue("1. First\n2. Second", selection = TextRange(18)))
        editor.onTextFieldValueChange(TextFieldValue("1. First\n2. Second\n", selection = TextRange(19)))
        assertEquals("1. First\n2. Second\n3. ", editor.text)

        // Move cursor before "First" -> must clamp out of prefix
        editor.onTextFieldValueChange(editor.textFieldValue.copy(selection = TextRange(1)))
        assertEquals(3, editor.selection.start)

        // Simulate HTML roundtrip preserves spans & text
        val html = editor.toHtml()
        val restored = RichTextEditorState.fromHtml(html)
        assertEquals(editor.text, restored.text)
        assertEquals(editor.spans, restored.spans)
    }

    @Test
    fun characterization_mediaDragBoundsAndHorizontalOverflowLogic() {
        val viewportW = 390f
        val inBoundsImg = CanvasImage(id = "i1", x = 20f, y = 40f, width = 160f, height = 120f)
        val mediaMaxRightIn = inBoundsImg.x + inBoundsImg.width // 180 < 390 - 32
        assertFalse(mediaMaxRightIn > viewportW - 32f)

        val overflowImg = CanvasImage(id = "i2", x = 280f, y = 40f, width = 160f, height = 120f)
        val mediaMaxRightOut = overflowImg.x + overflowImg.width // 440 > 390 - 32
        assertTrue(mediaMaxRightOut > viewportW - 32f)

        val expanded = computeExpandedDragBounds(
            itemRightDp = 420f,
            itemBottomDp = 900f,
            currentCanvasWidthDp = viewportW,
            currentCanvasHeightDp = 800f,
            currentBounds = null
        )
        assertNotNull(expanded)
        assertTrue(expanded!!.first > 420f)
        assertTrue(expanded.second > 900f)
    }

    @Test
    fun characterization_visualTransformation_paragraphSweepAndOverlappingSpans() {
        val text = "Heading\nAligned Indented x2\nCode"
        val spans = listOf(
            Span(0, 7, SpanStyle.Heading(1)),
            Span(8, 28, SpanStyle.Align(TextAlignment.CENTER)),
            Span(8, 28, SpanStyle.Align(TextAlignment.RIGHT)), // later span wins tie-break
            Span(8, 28, SpanStyle.Indent(2)),
            Span(16, 24, SpanStyle.Bold),
            Span(25, 27, SpanStyle.Superscript),
            Span(28, 32, SpanStyle.Code)
        )
        val vt = RichTextVisualTransformation(spans = spans, fontResolver = { null })
        val transformed = vt.filter(AnnotatedString(text))
        assertEquals(text, transformed.text.text)
        assertEquals(1, transformed.text.paragraphStyles.size)
        val para = transformed.text.paragraphStyles.first()
        assertEquals(8, para.start)
        assertEquals(28, para.end)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Right, para.item.textAlign)
        assertNotNull(para.item.textIndent)
        assertTrue(transformed.text.spanStyles.any { it.start == 25 && it.end == 27 && it.item.baselineShift != null })
    }

    @Test
    fun caretFollowTargetScrollY_handlesBottomMarginImeShrinkTopClipClampAndReadingMode() {
        val lineH = 40f // 1.5x bottom margin = 60f, 0.5x top margin = 20f

        // 1. Caret safely inside visible window -> returns null (no-op)
        assertEquals(
            null,
            computeCaretFollowTargetScrollY(
                currentScrollY = 1000f,
                maxScrollY = 5000f,
                caretTopInContentPx = 1200f,
                caretBottomInContentPx = 1240f,
                lineHeightPx = lineH,
                visibleTopInContentPx = 1000f,
                visibleBottomInContentPx = 1800f,
                isReading = false
            )
        )

        // 2. Caret near/past bottom edge -> scrolls down keeping ~1.5 lineH margin above bottom
        val scrollDownTarget = computeCaretFollowTargetScrollY(
            currentScrollY = 1000f,
            maxScrollY = 5000f,
            caretTopInContentPx = 1750f,
            caretBottomInContentPx = 1790f,
            lineHeightPx = lineH,
            visibleTopInContentPx = 1000f,
            visibleBottomInContentPx = 1800f, // safeBottom = 1800 - 60 = 1740
            isReading = false
        )
        // Expected: 1000 + (1790 - 1740) = 1050
        assertEquals(1050, scrollDownTarget)

        // 3. IME height shrink / toolbar overlap shrinks visibleBottom from 1800 -> 1300
        val imeShrinkTarget = computeCaretFollowTargetScrollY(
            currentScrollY = 1000f,
            maxScrollY = 5000f,
            caretTopInContentPx = 1400f,
            caretBottomInContentPx = 1440f,
            lineHeightPx = lineH,
            visibleTopInContentPx = 1000f,
            visibleBottomInContentPx = 1300f, // safeBottom = 1300 - 60 = 1240
            isReading = false
        )
        // Expected: 1000 + (1440 - 1240) = 1200
        assertEquals(1200, imeShrinkTarget)

        // 4. Caret clipped at top -> scrolls up keeping ~0.5 lineH margin from top
        val scrollUpTarget = computeCaretFollowTargetScrollY(
            currentScrollY = 1000f,
            maxScrollY = 5000f,
            caretTopInContentPx = 990f,
            caretBottomInContentPx = 1030f,
            lineHeightPx = lineH,
            visibleTopInContentPx = 1000f, // safeTop = 1000 + 20 = 1020
            visibleBottomInContentPx = 1800f,
            isReading = false
        )
        // Expected: 1000 + (990 - 1020) = 970
        assertEquals(970, scrollUpTarget)

        // 5. Clamping to [0, maxScrollY]
        assertEquals(
            5000,
            computeCaretFollowTargetScrollY(
                currentScrollY = 4900f,
                maxScrollY = 5000f,
                caretTopInContentPx = 5600f,
                caretBottomInContentPx = 5640f,
                lineHeightPx = lineH,
                visibleTopInContentPx = 4900f,
                visibleBottomInContentPx = 5500f,
                isReading = false
            )
        )
        assertEquals(
            0,
            computeCaretFollowTargetScrollY(
                currentScrollY = 20f,
                maxScrollY = 5000f,
                caretTopInContentPx = 5f,
                caretBottomInContentPx = 45f,
                lineHeightPx = lineH,
                visibleTopInContentPx = 20f,
                visibleBottomInContentPx = 820f,
                isReading = false
            )
        )

        // 6. Read-only mode -> always null
        assertEquals(
            null,
            computeCaretFollowTargetScrollY(
                currentScrollY = 1000f,
                maxScrollY = 5000f,
                caretTopInContentPx = 1790f,
                caretBottomInContentPx = 1830f,
                lineHeightPx = lineH,
                visibleTopInContentPx = 1000f,
                visibleBottomInContentPx = 1800f,
                isReading = true
            )
        )
    }

    @Test
    fun equivalence_normalizeSpans_fastPathIdentityAndComplexMergeOrder() {
        // 1. Already-normalized input returns the exact same List reference (zero copy / zero sort)
        val cleanInput = listOf(
            Span(0, 5, SpanStyle.Bold),
            Span(0, 5, SpanStyle.Italic),
            Span(5, 9, SpanStyle.Bold), // note: Bold at 5..9 is adjacent to 0..5 -> NOT already normalized!
        )
        val mergedAdjacent = normalizeSpans(cleanInput, 20)
        assertEquals(
            listOf(Span(0, 5, SpanStyle.Italic), Span(0, 9, SpanStyle.Bold)),
            mergedAdjacent
        )

        val strictlyNormalized = listOf(
            Span(0, 4, SpanStyle.Bold),
            Span(0, 8, SpanStyle.Italic),
            Span(5, 9, SpanStyle.Bold),
            Span(9, 12, SpanStyle.Underline)
        )
        assertTrue(normalizeSpans(strictlyNormalized, 12) === strictlyNormalized)

        // 2. Complex unsorted, overlapping, out-of-bounds, empty, and sort-order-flipping merge
        val chaotic = listOf(
            Span(10, 12, SpanStyle.Bold),
            Span(-3, 4, SpanStyle.Italic),     // clamps to 0..4
            Span(5, 5, SpanStyle.Underline),   // dropped empty
            Span(2, 6, SpanStyle.Italic),      // merges with 0..4 -> 0..6
            Span(0, 3, SpanStyle.Bold),        // merges later with 3..10
            Span(0, 5, SpanStyle.Underline),   // sits at start=0, end=5 (initially longer than Bold 0..3)
            Span(3, 10, SpanStyle.Bold),       // merges Bold 0..3 + 3..10 + 10..12 -> 0..12 (now longer than Underline 0..5!)
            Span(15, 25, SpanStyle.Code)       // clamps end to textLength=18
        )
        val expected = listOf(
            Span(0, 5, SpanStyle.Underline),
            Span(0, 6, SpanStyle.Italic),
            Span(0, 12, SpanStyle.Bold),
            Span(15, 18, SpanStyle.Code)
        )
        assertEquals(expected, normalizeSpans(chaotic, 18))
    }

    @Test
    fun equivalence_paragraphSnapOverlapTieBreak_dividerExempt_andMultiNumberedLists() {
        val div = RichTextEditorState.DIVIDER_MARKER
        // Lines:
        // 0: "1. Alpha\n" [0..9)
        // 1: "1. Beta\n"  [9..17)  -> renumbers to "2. Beta"
        // 2: "$div\n"     [17..19) -> Divider exempt from Align/Indent
        // 3: "1. Gamma\n" [19..28) -> separate list after divider, stays "1. Gamma"
        // 4: "Plain"      [28..33)
        val text = "1. Alpha\n1. Beta\n$div\n1. Gamma\nPlain"
        val spans = listOf(
            Span(0, 3, SpanStyle.Numbered),
            Span(9, 12, SpanStyle.Numbered),
            Span(17, 18, SpanStyle.Divider),
            Span(19, 22, SpanStyle.Numbered),
            // Two overlapping Align spans across line 1 & divider: later span (RIGHT) must win tie-break
            Span(0, 28, SpanStyle.Align(TextAlignment.CENTER)),
            Span(9, 19, SpanStyle.Align(TextAlignment.RIGHT)),
            Span(9, 28, SpanStyle.Indent(2)),
            Span(9, 17, SpanStyle.Indent(1)) // max indent = 2 wins
        )
        val editor = RichTextEditorState(text, spans)
        // Trigger typing on Plain line to run onTextFieldValueChange -> renumber + snapLineParagraphSpans + normalizeSpans
        editor.onTextFieldValueChange(TextFieldValue(text + "!", TextRange(text.length + 1)))

        assertEquals("1. Alpha\n2. Beta\n$div\n1. Gamma\nPlain!", editor.text)
        // Divider line [17..19) must NOT receive Align or Indent
        assertFalse(editor.spans.any { (it.style is SpanStyle.Align || it.style is SpanStyle.Indent) && it.start == 17 })
        // Line 1 ("2. Beta\n") [9..17) gets RIGHT (later span wins tie-break) and Indent(2)
        assertTrue(editor.spans.contains(Span(9, 17, SpanStyle.Align(TextAlignment.RIGHT))))
        assertTrue(editor.spans.contains(Span(9, 17, SpanStyle.Indent(2))))
        // Line 0 ("1. Alpha\n") [0..9) gets CENTER
        assertTrue(editor.spans.contains(Span(0, 9, SpanStyle.Align(TextAlignment.CENTER))))
        // Backspace numbered prefix on line 0 removes prefix and renumbers line 1 back to 1
        editor.onTextFieldValueChange(TextFieldValue("1 Alpha\n2. Beta\n$div\n1. Gamma\nPlain!", TextRange(1)))
        assertEquals("Alpha\n1. Beta\n$div\n1. Gamma\nPlain!", editor.text)
    }

    // =========================================================================
    // Step 0a / Step 5: Reproducible JVM CPU Benchmarks
    // =========================================================================

    private data class LargeDocFixture(
        val text: String,
        val spans: List<Span>,
        val lineStarts: IntArray,
        val lineEnds: IntArray
    )

    private fun buildDocumentFixture(wordCount: Int): LargeDocFixture {
        val wordsPerLine = 10
        val lineCount = (wordCount / wordsPerLine).coerceAtLeast(1)
        val sb = StringBuilder(wordCount * 7)
        val rawSpans = mutableListOf<Span>()
        val starts = IntArray(lineCount)
        val ends = IntArray(lineCount)

        for (lineIdx in 0 until lineCount) {
            val ls = sb.length
            starts[lineIdx] = ls
            if (lineIdx % 25 == 0) {
                sb.append("${(lineIdx / 25) + 1}. ")
                rawSpans.add(Span(ls, sb.length, SpanStyle.Numbered))
            }
            for (w in 0 until wordsPerLine) {
                val ws = sb.length
                sb.append("word")
                sb.append(w)
                val we = sb.length
                when ((lineIdx + w) % 12) {
                    0 -> rawSpans.add(Span(ws, we, SpanStyle.Bold))
                    3 -> rawSpans.add(Span(ws, we, SpanStyle.Highlight("#fef08a")))
                    6 -> rawSpans.add(Span(ws, we, SpanStyle.Color("#3366cc")))
                    9 -> rawSpans.add(Span(ws, we, SpanStyle.Code))
                }
                if (w < wordsPerLine - 1) sb.append(' ')
            }
            val le = sb.length
            ends[lineIdx] = le
            if (lineIdx % 10 == 0) {
                rawSpans.add(Span(ls, le, SpanStyle.Quote))
            }
            if (lineIdx % 8 == 0) {
                val paraEnd = if (lineIdx < lineCount - 1) le + 1 else le
                rawSpans.add(Span(ls, paraEnd, SpanStyle.Align(TextAlignment.LEFT)))
                rawSpans.add(Span(ls, paraEnd, SpanStyle.Indent(1)))
            }
            if (lineIdx < lineCount - 1) sb.append('\n')
        }
        val text = sb.toString()
        return LargeDocFixture(text, normalizeSpans(rawSpans, text.length), starts, ends)
    }

    private fun lineForOffsetBinary(starts: IntArray, offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= offset) lo = mid + 1 else hi = mid - 1
        }
        return hi.coerceIn(0, starts.size - 1)
    }

    @Test
    fun benchmark_typingAndVisualTransformationAndDecorationsAcrossDocSizes() {
        val sizes = listOf(500, 5_000, 20_000, 50_000)
        val report = StringBuilder("\n=== JVM CPU BENCHMARK REPORT ===\n")
        for (words in sizes) {
            val fixture = buildDocumentFixture(words)

            // 1. Typing at end (15 iterations after warmup)
            val editor = RichTextEditorState(fixture.text, fixture.spans)
            repeat(5) { idx ->
                val t = editor.text + ('a' + idx)
                editor.onTextFieldValueChange(TextFieldValue(t, TextRange(t.length)))
            }
            val typeSamplesMs = mutableListOf<Double>()
            repeat(15) { idx ->
                val nextText = editor.text + ('a' + (idx % 26))
                val nextVal = TextFieldValue(nextText, TextRange(nextText.length))
                val ns = measureNanoTime {
                    editor.onTextFieldValueChange(nextVal)
                }
                typeSamplesMs.add(ns / 1_000_000.0)
            }
            typeSamplesMs.sort()

            // 2. Typing in middle
            val midEditor = RichTextEditorState(fixture.text, fixture.spans)
            val midPos = fixture.text.length / 2
            midEditor.onTextFieldValueChange(midEditor.textFieldValue.copy(selection = TextRange(midPos)))
            val midTypeSamplesMs = mutableListOf<Double>()
            repeat(15) { idx ->
                val cur = midEditor.text
                val p = (midPos + idx).coerceAtMost(cur.length)
                val nextText = cur.substring(0, p) + 'x' + cur.substring(p)
                val ns = measureNanoTime {
                    midEditor.onTextFieldValueChange(TextFieldValue(nextText, TextRange(p + 1)))
                }
                midTypeSamplesMs.add(ns / 1_000_000.0)
            }
            midTypeSamplesMs.sort()

            // 3. VisualTransformation filter
            val vt = RichTextVisualTransformation(spans = fixture.spans, fontResolver = { null })
            val inputAnnotated = AnnotatedString(fixture.text)
            repeat(3) { vt.filter(inputAnnotated) }
            val vtSamplesMs = mutableListOf<Double>()
            repeat(10) {
                val ns = measureNanoTime { vt.filter(inputAnnotated) }
                vtSamplesMs.add(ns / 1_000_000.0)
            }
            vtSamplesMs.sort()

            // 4. Decoration geometry computation (full doc highlight + quote/code)
            val lineCount = fixture.lineStarts.size
            val decoSamplesMs = mutableListOf<Double>()
            repeat(5) {
                val ns = measureNanoTime {
                    HighlightLayoutCalculator.computeHighlightRects(
                        text = fixture.text,
                        spans = fixture.spans,
                        lineCount = lineCount,
                        ascentPx = -28f,
                        descentPx = 8f,
                        getLineForOffset = { lineForOffsetBinary(fixture.lineStarts, it) },
                        getLineStart = { fixture.lineStarts[it] },
                        getLineEnd = { fixture.lineEnds[it] },
                        getLineBaseline = { it * 40f + 30f },
                        getLineTop = { it * 40f },
                        getLineBottom = { (it + 1) * 40f },
                        getCharLeft = { idx ->
                            val l = lineForOffsetBinary(fixture.lineStarts, idx)
                            (idx - fixture.lineStarts[l]) * 10f
                        },
                        getCharRight = { idx ->
                            val l = lineForOffsetBinary(fixture.lineStarts, idx)
                            (idx - fixture.lineStarts[l] + 1) * 10f
                        }
                    )
                    BlockDecorationLayoutCalculator.computeQuoteBlocks(
                        text = fixture.text,
                        spans = fixture.spans,
                        lineCount = lineCount,
                        canvasWidth = 900f,
                        getLineForOffset = { lineForOffsetBinary(fixture.lineStarts, it) },
                        getLineStart = { fixture.lineStarts[it] },
                        getLineEnd = { fixture.lineEnds[it] },
                        getLineTop = { it * 40f },
                        getLineBottom = { (it + 1) * 40f },
                        getCharLeft = { idx ->
                            val l = lineForOffsetBinary(fixture.lineStarts, idx)
                            (idx - fixture.lineStarts[l]) * 10f
                        },
                        getCharRight = { idx ->
                            val l = lineForOffsetBinary(fixture.lineStarts, idx)
                            (idx - fixture.lineStarts[l] + 1) * 10f
                        }
                    )
                }
                decoSamplesMs.add(ns / 1_000_000.0)
            }
            decoSamplesMs.sort()

            report.append(
                String.format(
                    "%6d words (%4d spans): typeEnd p50=%.2fms p95=%.2fms | typeMid p50=%.2fms | VT p50=%.2fms p95=%.2fms | fullDeco p50=%.2fms\n",
                    words,
                    fixture.spans.size,
                    typeSamplesMs[typeSamplesMs.size / 2],
                    typeSamplesMs[(typeSamplesMs.size * 95 / 100).coerceAtMost(typeSamplesMs.lastIndex)],
                    midTypeSamplesMs[midTypeSamplesMs.size / 2],
                    vtSamplesMs[vtSamplesMs.size / 2],
                    vtSamplesMs[(vtSamplesMs.size * 95 / 100).coerceAtMost(vtSamplesMs.lastIndex)],
                    decoSamplesMs[decoSamplesMs.size / 2]
                )
            )
        }
        println(report.toString())
        assertTrue(report.isNotEmpty())
    }
}
