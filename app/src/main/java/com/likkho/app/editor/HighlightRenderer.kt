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
        lineMetrics: ((lineStart: Int, lineEnd: Int) -> Pair<Float, Float>)? = null,
        visibleLineRange: IntRange? = null
    ): List<HighlightRect> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val visFirst = visibleLineRange?.first?.coerceIn(0, lineCount - 1) ?: 0
        val visLast = visibleLineRange?.last?.coerceIn(0, lineCount - 1) ?: (lineCount - 1)
        if (visFirst > visLast) return emptyList()

        val rects = mutableListOf<HighlightRect>()

        for (span in spans) {
            val style = span.style as? SpanStyle.Highlight ?: continue
            val s = span.start.coerceIn(0, text.length)
            val e = span.end.coerceIn(0, text.length)
            if (s >= e) continue

            val firstLine = getLineForOffset(s).coerceIn(0, lineCount - 1)
            if (firstLine > visLast) continue
            val lastLine = getLineForOffset((e - 1).coerceAtLeast(s)).coerceIn(firstLine, lineCount - 1)
            if (lastLine < visFirst) continue

            val rangeStart = maxOf(firstLine, visFirst)
            val rangeEnd = minOf(lastLine, visLast)
            for (line in rangeStart..rangeEnd) {
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
        lineMetrics: ((lineStart: Int, lineEnd: Int) -> Pair<Float, Float>)? = null,
        visibleLineRange: IntRange? = null
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
            lineMetrics = lineMetrics,
            visibleLineRange = visibleLineRange
        )
    }

    fun computeSpoilerRects(
        layoutResult: TextLayoutResult,
        spans: List<Span>,
        unlockedSpoilerIds: Set<String>,
        ascentPx: Float,
        descentPx: Float,
        lineMetrics: ((lineStart: Int, lineEnd: Int) -> Pair<Float, Float>)? = null,
        visibleLineRange: IntRange? = null
    ): List<HighlightRect> {
        val text = layoutResult.layoutInput.text.text
        val lineCount = layoutResult.lineCount
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val visFirst = visibleLineRange?.first?.coerceIn(0, lineCount - 1) ?: 0
        val visLast = visibleLineRange?.last?.coerceIn(0, lineCount - 1) ?: (lineCount - 1)
        if (visFirst > visLast) return emptyList()

        val rects = mutableListOf<HighlightRect>()
        for (span in spans) {
            val st = span.style as? SpanStyle.Spoiler ?: continue
            if (st.id in unlockedSpoilerIds) continue
            val s = span.start.coerceIn(0, text.length)
            val e = span.end.coerceIn(0, text.length)
            if (s >= e) continue

            val firstLine = layoutResult.getLineForOffset(s).coerceIn(0, lineCount - 1)
            if (firstLine > visLast) continue
            val lastLine = layoutResult.getLineForOffset((e - 1).coerceAtLeast(s)).coerceIn(firstLine, lineCount - 1)
            if (lastLine < visFirst) continue

            val rangeStart = maxOf(firstLine, visFirst)
            val rangeEnd = minOf(lastLine, visLast)
            for (line in rangeStart..rangeEnd) {
                val lineStart = layoutResult.getLineStart(line)
                val lineEnd = layoutResult.getLineEnd(line, visibleEnd = false)
                val segStart = maxOf(s, lineStart)
                var segEnd = minOf(e, lineEnd)

                while (segEnd > segStart && segEnd <= text.length && text[segEnd - 1] == '\n') {
                    segEnd--
                }
                if (segStart >= segEnd) continue

                var minLeft = Float.POSITIVE_INFINITY
                var maxRight = Float.NEGATIVE_INFINITY

                for (idx in segStart until segEnd) {
                    val cLeft = layoutResult.getBoundingBox(idx).left
                    val cRight = layoutResult.getBoundingBox(idx).right
                    val boxLeft = minOf(cLeft, cRight)
                    val boxRight = maxOf(cLeft, cRight)
                    if (boxLeft < minLeft) minLeft = boxLeft
                    if (boxRight > maxRight) maxRight = boxRight
                }

                if (!minLeft.isFinite() || !maxRight.isFinite() || maxRight <= minLeft) continue

                val baseline = layoutResult.getLineBaseline(line)
                val lineTop = layoutResult.getLineTop(line)
                val lineBottom = layoutResult.getLineBottom(line)
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
                        colorHex = "#000000"
                    )
                )
            }
        }
        if (rects.size > 1) rects.sortBy { it.top }
        return rects
    }
}

data class CachedHighlightRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val color: Color
)

private fun parseHighlightHex(hex: String): Color? = try {
    val clean = if (hex.startsWith("#")) hex else "#$hex"
    Color(android.graphics.Color.parseColor(clean))
} catch (_: Exception) {
    null
}

fun computeCachedHighlightRects(
    layoutResult: TextLayoutResult,
    spans: List<Span>,
    ascentPx: Float,
    descentPx: Float,
    lineMetrics: ((lineStart: Int, lineEnd: Int) -> Pair<Float, Float>)? = null,
    visibleLineRange: IntRange? = null
): List<CachedHighlightRect> {
    val rects = HighlightLayoutCalculator.computeFromTextLayout(
        layoutResult = layoutResult,
        spans = spans,
        ascentPx = ascentPx,
        descentPx = descentPx,
        lineMetrics = lineMetrics,
        visibleLineRange = visibleLineRange
    )
    if (rects.isEmpty()) return emptyList()
    val colorCache = HashMap<String, Color?>(8)
    val out = ArrayList<CachedHighlightRect>(rects.size)
    for (r in rects) {
        val color = colorCache.getOrPut(r.colorHex) { parseHighlightHex(r.colorHex) } ?: continue
        out.add(CachedHighlightRect(r.left, r.top, r.right, r.bottom, color))
    }
    if (out.size > 1) out.sortBy { it.top }
    return out
}

/**
 * Given items sorted by [getTop], finds the first index whose [getTop] >= [thresholdTop].
 */
internal inline fun <T> lowerBoundByTop(items: List<T>, thresholdTop: Float, getTop: (T) -> Float): Int {
    var lo = 0
    var hi = items.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (getTop(items[mid]) < thresholdTop) lo = mid + 1 else hi = mid
    }
    return lo
}

internal fun collectVisibleHighlightIndices(
    cachedRects: List<CachedHighlightRect>,
    visibleTopPx: Float,
    visibleBottomPx: Float,
    maxLineHeightMarginPx: Float = 320f
): IntRange {
    if (cachedRects.isEmpty() || visibleBottomPx < visibleTopPx) return IntRange.EMPTY
    if (!visibleTopPx.isFinite() && !visibleBottomPx.isFinite()) return cachedRects.indices
    val startIdx = if (visibleTopPx.isFinite()) {
        lowerBoundByTop(cachedRects, visibleTopPx - maxLineHeightMarginPx) { it.top }
    } else 0
    var endIdx = startIdx
    if (visibleBottomPx.isFinite()) {
        while (endIdx < cachedRects.size && cachedRects[endIdx].top <= visibleBottomPx) {
            endIdx++
        }
    } else {
        endIdx = cachedRects.size
    }
    return startIdx until endIdx
}

fun DrawScope.drawCachedTextHighlights(
    cachedRects: List<CachedHighlightRect>,
    visibleTopPx: Float = Float.NEGATIVE_INFINITY,
    visibleBottomPx: Float = Float.POSITIVE_INFINITY
) {
    if (cachedRects.isEmpty()) return
    val useCull = visibleTopPx.isFinite() || visibleBottomPx.isFinite()
    val startIdx = if (useCull && visibleTopPx.isFinite()) {
        lowerBoundByTop(cachedRects, visibleTopPx - 320f) { it.top }
    } else 0
    for (i in startIdx until cachedRects.size) {
        val r = cachedRects[i]
        if (useCull && r.top > visibleBottomPx) break
        if (useCull && r.bottom < visibleTopPx) continue
        drawRect(
            color = r.color,
            topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
            size = androidx.compose.ui.geometry.Size(r.right - r.left, r.bottom - r.top)
        )
    }
}

fun DrawScope.drawCachedTextSpoilers(
    spoilerRects: List<HighlightRect>,
    visibleTopPx: Float = Float.NEGATIVE_INFINITY,
    visibleBottomPx: Float = Float.POSITIVE_INFINITY
) {
    if (spoilerRects.isEmpty()) return
    val useCull = visibleTopPx.isFinite() || visibleBottomPx.isFinite()
    val startIdx = if (useCull && visibleTopPx.isFinite()) {
        lowerBoundByTop(spoilerRects, visibleTopPx - 320f) { it.top }
    } else 0
    for (i in startIdx until spoilerRects.size) {
        val r = spoilerRects[i]
        if (useCull && r.top > visibleBottomPx) break
        if (useCull && r.bottom < visibleTopPx) continue
        drawRect(
            color = Color.Black,
            topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
            size = androidx.compose.ui.geometry.Size(r.right - r.left, r.bottom - r.top)
        )
    }
}

fun DrawScope.drawTextHighlights(
    layoutResult: TextLayoutResult,
    spans: List<Span>,
    ascentPx: Float,
    descentPx: Float,
    lineMetrics: ((lineStart: Int, lineEnd: Int) -> Pair<Float, Float>)? = null
) {
    drawCachedTextHighlights(
        computeCachedHighlightRects(
            layoutResult = layoutResult,
            spans = spans,
            ascentPx = ascentPx,
            descentPx = descentPx,
            lineMetrics = lineMetrics
        )
    )
}

fun DrawScope.drawTextSpoilers(
    layoutResult: TextLayoutResult,
    spans: List<Span>,
    unlockedSpoilerIds: Set<String>,
    ascentPx: Float,
    descentPx: Float,
    lineMetrics: ((lineStart: Int, lineEnd: Int) -> Pair<Float, Float>)? = null
) {
    val rects = HighlightLayoutCalculator.computeSpoilerRects(
        layoutResult = layoutResult,
        spans = spans,
        unlockedSpoilerIds = unlockedSpoilerIds,
        ascentPx = ascentPx,
        descentPx = descentPx,
        lineMetrics = lineMetrics
    )
    drawCachedTextSpoilers(rects)
}
