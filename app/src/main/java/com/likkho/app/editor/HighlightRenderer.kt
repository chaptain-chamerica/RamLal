package com.likkho.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle

data class HighlightRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val colorHex: String
)

/**
 * Pure geometry calculator for text highlights bounded strictly from the font's
 * typographic ascender (baseline + ascent) to descender (baseline + descent),
 * avoiding highlighting extra line-height spacing above or below the glyphs.
 */
object HighlightLayoutCalculator {

    fun computeHighlightRects(
        text: String,
        spans: List<Span>,
        lineCount: Int,
        ascentPx: Float,
        descentPx: Float,
        getLineForOffset: (Int) -> Int,
        getLineStart: (Int) -> Int,
        getLineEnd: (Int) -> Int,
        getLineBaseline: (Int) -> Float,
        getLineTop: (Int) -> Float,
        getLineBottom: (Int) -> Float,
        getCharLeft: (Int) -> Float,
        getCharRight: (Int) -> Float,
        lineMetrics: ((lineStart: Int, lineEnd: Int) -> Pair<Float, Float>)? = null
    ): List<HighlightRect> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()

        val highlightSpans = spans.filter { it.style is SpanStyle.Highlight }
        if (highlightSpans.isEmpty()) return emptyList()

        val rects = mutableListOf<HighlightRect>()

        for (span in highlightSpans) {
            val style = span.style as SpanStyle.Highlight
            val s = span.start.coerceIn(0, text.length)
            val e = span.end.coerceIn(0, text.length)
            if (s >= e) continue

            val firstLine = getLineForOffset(s).coerceIn(0, lineCount - 1)
            val lastLine = getLineForOffset((e - 1).coerceAtLeast(s)).coerceIn(firstLine, lineCount - 1)

            for (line in firstLine..lastLine) {
                val lineStart = getLineStart(line)
                val lineEnd = getLineEnd(line)
                val segStart = maxOf(s, lineStart)
                var segEnd = minOf(e, lineEnd)

                // Do not highlight trailing newline characters
                while (segEnd > segStart && segEnd <= text.length && text[segEnd - 1] == '\n') {
                    segEnd--
                }
                if (segStart >= segEnd) continue

                var minLeft = Float.POSITIVE_INFINITY
                var maxRight = Float.NEGATIVE_INFINITY

                for (idx in segStart until segEnd) {
                    val cLeft = getCharLeft(idx)
                    val cRight = getCharRight(idx)
                    val boxLeft = minOf(cLeft, cRight)
                    val boxRight = maxOf(cLeft, cRight)
                    if (boxLeft < minLeft) minLeft = boxLeft
                    if (boxRight > maxRight) maxRight = boxRight
                }

                if (!minLeft.isFinite() || !maxRight.isFinite() || maxRight <= minLeft) continue

                val baseline = getLineBaseline(line)
                val lineTop = getLineTop(line)
                val lineBottom = getLineBottom(line)
                val (lineAscent, lineDescent) = lineMetrics?.invoke(segStart, segEnd) ?: (ascentPx to descentPx)

                val top = (baseline + lineAscent).coerceIn(lineTop, lineBottom)
                val bottom = (baseline + lineDescent).coerceIn(top, lineBottom)
                if (bottom <= top) continue

                rects.add(
                    HighlightRect(
                        left = minLeft,
                        top = top,
                        right = maxRight,
                        bottom = bottom,
                        colorHex = style.hex
                    )
                )
            }
        }

        return rects
    }

    fun computeFromTextLayout(
        layoutResult: TextLayoutResult,
        spans: List<Span>,
        ascentPx: Float,
        descentPx: Float,
        lineMetrics: ((lineStart: Int, lineEnd: Int) -> Pair<Float, Float>)? = null
    ): List<HighlightRect> {
        val text = layoutResult.layoutInput.text.text
        return computeHighlightRects(
            text = text,
            spans = spans,
            lineCount = layoutResult.lineCount,
            ascentPx = ascentPx,
            descentPx = descentPx,
            getLineForOffset = { layoutResult.getLineForOffset(it) },
            getLineStart = { layoutResult.getLineStart(it) },
            getLineEnd = { layoutResult.getLineEnd(it, visibleEnd = false) },
            getLineBaseline = { layoutResult.getLineBaseline(it) },
            getLineTop = { layoutResult.getLineTop(it) },
            getLineBottom = { layoutResult.getLineBottom(it) },
            getCharLeft = { layoutResult.getBoundingBox(it).left },
            getCharRight = { layoutResult.getBoundingBox(it).right },
            lineMetrics = lineMetrics
        )
    }
}

private fun parseHighlightHex(hex: String): Color? = try {
    val clean = if (hex.startsWith("#")) hex else "#$hex"
    Color(android.graphics.Color.parseColor(clean))
} catch (_: Exception) {
    null
}

fun DrawScope.drawTextHighlights(
    layoutResult: TextLayoutResult,
    spans: List<Span>,
    ascentPx: Float,
    descentPx: Float,
    lineMetrics: ((lineStart: Int, lineEnd: Int) -> Pair<Float, Float>)? = null
) {
    val rects = HighlightLayoutCalculator.computeFromTextLayout(
        layoutResult = layoutResult,
        spans = spans,
        ascentPx = ascentPx,
        descentPx = descentPx,
        lineMetrics = lineMetrics
    )
    for (r in rects) {
        val color = parseHighlightHex(r.colorHex) ?: continue
        drawRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
            size = androidx.compose.ui.geometry.Size(r.right - r.left, r.bottom - r.top)
        )
    }
}
