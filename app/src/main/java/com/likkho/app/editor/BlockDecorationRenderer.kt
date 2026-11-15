package com.likkho.app.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.likkho.app.editor.model.Span
import com.likkho.app.editor.model.SpanStyle

data class QuoteBlockRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val barWidth: Float,
    val isFullLine: Boolean = true
)

data class CodeBoxRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val isBlock: Boolean
)

/**
 * Pure layout geometry calculator for Quote block bars/tints and Code blocks / inline code boxes.
 * Tightly wraps actual text bounds without stretching across empty line space or overlapping prior text.
 */
object BlockDecorationLayoutCalculator {

    private fun prevVisibleCharRight(
        text: String,
        lineStart: Int,
        segStart: Int,
        getCharLeft: (Int) -> Float,
        getCharRight: (Int) -> Float
    ): Float? {
        var idx = segStart - 1
        while (idx >= lineStart) {
            if (!text[idx].isWhitespace()) {
                val l = getCharLeft(idx)
                val r = getCharRight(idx)
                val maxEdge = maxOf(l, r)
                return if (maxEdge.isFinite()) maxEdge else null
            }
            idx--
        }
        return null
    }

    private fun lineSegmentHorizontalBounds(
        segStart: Int,
        segEnd: Int,
        getCharLeft: (Int) -> Float,
        getCharRight: (Int) -> Float
    ): Pair<Float, Float>? {
        var minLeft = Float.POSITIVE_INFINITY
        var maxRight = Float.NEGATIVE_INFINITY
        for (idx in segStart until segEnd) {
            val l = getCharLeft(idx)
            val r = getCharRight(idx)
            if (l.isFinite() && r.isFinite()) {
                minLeft = minOf(minLeft, minOf(l, r))
                maxRight = maxOf(maxRight, maxOf(l, r))
            }
        }
        if (!minLeft.isFinite() || !maxRight.isFinite() || maxRight <= minLeft) return null
        return minLeft to maxRight
    }

    fun computeQuoteBlocks(
        text: String,
        spans: List<Span>,
        lineCount: Int,
        canvasWidth: Float = 300f,
        blockOutsetLeftPx: Float = 0f,
        blockOutsetRightPx: Float = 0f,
        inlinePadPx: Float = 0f,
        barWidthPx: Float = 3f,
        gapFromPrevPx: Float = 1f,
        verticalInsetPx: Float = 1f,
        getLineForOffset: (Int) -> Int,
        getLineStart: (Int) -> Int,
        getLineEnd: (Int) -> Int,
        getLineTop: (Int) -> Float,
        getLineBottom: (Int) -> Float,
        getCharLeft: (Int) -> Float = { 0f },
        getCharRight: (Int) -> Float = { 0f }
    ): List<QuoteBlockRect> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val quoteSpans = spans.filter { it.style is SpanStyle.Quote }
        if (quoteSpans.isEmpty()) return emptyList()

        data class FullLineEntry(val line: Int, val minLeft: Float, val maxRight: Float)
        val fullLineMap = sortedMapOf<Int, Pair<Float, Float>>()
        val partialRects = mutableListOf<QuoteBlockRect>()

        for (sp in quoteSpans) {
            val s = sp.start.coerceIn(0, text.length)
            val e = sp.end.coerceIn(0, text.length)
            if (s >= e) continue
            val firstLine = getLineForOffset(s).coerceIn(0, lineCount - 1)
            val lastLine = getLineForOffset((e - 1).coerceAtLeast(s)).coerceIn(firstLine, lineCount - 1)
            for (line in firstLine..lastLine) {
                val ls = getLineStart(line)
                val le = getLineEnd(line)
                val segStart = maxOf(s, ls)
                var segEnd = minOf(e, le)
                while (segEnd > segStart && segEnd <= text.length && text[segEnd - 1] == '\n') {
                    segEnd--
                }
                if (segStart >= segEnd) continue

                val bounds = lineSegmentHorizontalBounds(segStart, segEnd, getCharLeft, getCharRight) ?: continue
                val (minLeft, maxRight) = bounds
                val prevRight = if (segStart > ls) prevVisibleCharRight(text, ls, segStart, getCharLeft, getCharRight) else null

                if (prevRight == null) {
                    val existing = fullLineMap[line]
                    fullLineMap[line] = if (existing == null) {
                        minLeft to maxRight
                    } else {
                        minOf(existing.first, minLeft) to maxOf(existing.second, maxRight)
                    }
                } else {
                    val safeStart = minOf(prevRight + gapFromPrevPx, minLeft)
                    val availableGap = (minLeft - safeStart).coerceAtLeast(0f)
                    val desiredLeft = (minLeft - inlinePadPx).coerceAtLeast(safeStart)
                    val effectiveBarWidth = if (availableGap >= 1.5f) {
                        minOf(barWidthPx, (minLeft - desiredLeft).coerceAtLeast(availableGap.coerceAtMost(barWidthPx)))
                    } else {
                        minOf(barWidthPx, (maxRight - minLeft).coerceAtLeast(1.5f))
                    }
                    val top = getLineTop(line) + verticalInsetPx
                    val bottom = (getLineBottom(line) - verticalInsetPx).coerceAtLeast(top)
                    partialRects.add(
                        QuoteBlockRect(
                            left = desiredLeft,
                            top = top,
                            right = (maxRight + blockOutsetRightPx).coerceAtMost(canvasWidth + blockOutsetRightPx),
                            bottom = bottom,
                            barWidth = effectiveBarWidth,
                            isFullLine = false
                        )
                    )
                }
            }
        }

        val blocks = mutableListOf<QuoteBlockRect>()
        if (fullLineMap.isNotEmpty()) {
            val entries = fullLineMap.entries.map { FullLineEntry(it.key, it.value.first, it.value.second) }
            var startIdx = 0
            for (i in 1..entries.size) {
                if (i == entries.size || entries[i].line > entries[i - 1].line + 1) {
                    val group = entries.subList(startIdx, i)
                    val blockLeft = (group.minOf { it.minLeft } - blockOutsetLeftPx)
                    val blockRight = (group.maxOf { it.maxRight } + blockOutsetRightPx)
                        .coerceAtMost(canvasWidth + blockOutsetRightPx)
                    val top = getLineTop(group.first().line) + verticalInsetPx
                    val bottom = (getLineBottom(group.last().line) - verticalInsetPx).coerceAtLeast(top)
                    blocks.add(
                        QuoteBlockRect(
                            left = blockLeft,
                            top = top,
                            right = blockRight,
                            bottom = bottom,
                            barWidth = barWidthPx,
                            isFullLine = true
                        )
                    )
                    startIdx = i
                }
            }
        }

        blocks.addAll(partialRects)
        return blocks
    }

    fun computeCodeBoxes(
        text: String,
        spans: List<Span>,
        lineCount: Int,
        canvasWidth: Float,
        blockOutsetLeftPx: Float = 0f,
        blockOutsetRightPx: Float = 0f,
        inlinePadPx: Float,
        gapFromPrevPx: Float = 1f,
        verticalInsetPx: Float = 1f,
        getLineForOffset: (Int) -> Int,
        getLineStart: (Int) -> Int,
        getLineEnd: (Int) -> Int,
        getLineTop: (Int) -> Float,
        getLineBottom: (Int) -> Float,
        getCharLeft: (Int) -> Float,
        getCharRight: (Int) -> Float
    ): List<CodeBoxRect> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val codeSpans = spans.filter { it.style is SpanStyle.Code }
        if (codeSpans.isEmpty()) return emptyList()

        data class BlockLineEntry(val line: Int, val minLeft: Float, val maxRight: Float)
        val blockLineMap = sortedMapOf<Int, Pair<Float, Float>>()
        val inlineBoxes = mutableListOf<CodeBoxRect>()

        for (sp in codeSpans) {
            val s = sp.start.coerceIn(0, text.length)
            val e = sp.end.coerceIn(0, text.length)
            if (s >= e) continue

            val firstLine = getLineForOffset(s).coerceIn(0, lineCount - 1)
            val lastLine = getLineForOffset((e - 1).coerceAtLeast(s)).coerceIn(firstLine, lineCount - 1)

            for (line in firstLine..lastLine) {
                val lineStart = getLineStart(line)
                val lineEnd = getLineEnd(line)
                val segStart = maxOf(s, lineStart)
                var segEnd = minOf(e, lineEnd)
                while (segEnd > segStart && segEnd <= text.length && text[segEnd - 1] == '\n') {
                    segEnd--
                }
                if (segStart >= segEnd) continue

                var visibleLineEnd = lineEnd
                while (visibleLineEnd > lineStart && visibleLineEnd <= text.length && text[visibleLineEnd - 1] == '\n') {
                    visibleLineEnd--
                }

                val bounds = lineSegmentHorizontalBounds(segStart, segEnd, getCharLeft, getCharRight) ?: continue
                val (minLeft, maxRight) = bounds
                val prevRight = if (segStart > lineStart) {
                    prevVisibleCharRight(text, lineStart, segStart, getCharLeft, getCharRight)
                } else null

                val isStandaloneLineBlock = prevRight == null && segEnd >= visibleLineEnd
                if (isStandaloneLineBlock) {
                    val existing = blockLineMap[line]
                    blockLineMap[line] = if (existing == null) {
                        minLeft to maxRight
                    } else {
                        minOf(existing.first, minLeft) to maxOf(existing.second, maxRight)
                    }
                } else {
                    val safeMinLeft = if (prevRight != null) {
                        minOf(prevRight + gapFromPrevPx, minLeft)
                    } else {
                        minLeft - inlinePadPx
                    }
                    val boxLeft = (minLeft - inlinePadPx).coerceAtLeast(safeMinLeft)
                    val top = getLineTop(line) + verticalInsetPx
                    val bottom = (getLineBottom(line) - verticalInsetPx).coerceAtLeast(top)
                    inlineBoxes.add(
                        CodeBoxRect(
                            left = boxLeft,
                            top = top,
                            right = (maxRight + inlinePadPx).coerceAtMost(canvasWidth + blockOutsetRightPx),
                            bottom = bottom,
                            isBlock = false
                        )
                    )
                }
            }
        }

        val result = mutableListOf<CodeBoxRect>()
        if (blockLineMap.isNotEmpty()) {
            val entries = blockLineMap.entries.map { BlockLineEntry(it.key, it.value.first, it.value.second) }
            var startIdx = 0
            for (i in 1..entries.size) {
                if (i == entries.size || entries[i].line > entries[i - 1].line + 1) {
                    val group = entries.subList(startIdx, i)
                    val blockLeft = group.minOf { it.minLeft } - blockOutsetLeftPx
                    val blockRight = (group.maxOf { it.maxRight } + blockOutsetRightPx)
                        .coerceAtMost(canvasWidth + blockOutsetRightPx)
                    val top = getLineTop(group.first().line) + verticalInsetPx
                    val bottom = (getLineBottom(group.last().line) - verticalInsetPx).coerceAtLeast(top)
                    result.add(
                        CodeBoxRect(
                            left = blockLeft,
                            top = top,
                            right = blockRight,
                            bottom = bottom,
                            isBlock = true
                        )
                    )
                    startIdx = i
                }
            }
        }

        result.addAll(inlineBoxes)
        return result
    }
}

fun DrawScope.drawBlockDecorations(
    layoutResult: TextLayoutResult,
    spans: List<Span>,
    quoteBgColor: Color,
    quoteBarColor: Color,
    codeBgColor: Color,
    codeBorderColor: Color
) {
    val text = layoutResult.layoutInput.text.text
    if (text.isEmpty() || layoutResult.lineCount <= 0 || spans.isEmpty()) return

    val quoteOutsetLeft = 8.dp.toPx()
    val quoteOutsetRight = 6.dp.toPx()
    val quoteInlinePad = 3.5.dp.toPx()
    val barWidthPx = 2.5.dp.toPx()
    val gapFromPrevPx = 1.5.dp.toPx()
    val verticalInsetPx = 1.dp.toPx()

    val quoteBlocks = BlockDecorationLayoutCalculator.computeQuoteBlocks(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        canvasWidth = size.width,
        blockOutsetLeftPx = quoteOutsetLeft,
        blockOutsetRightPx = quoteOutsetRight,
        inlinePadPx = quoteInlinePad,
        barWidthPx = barWidthPx,
        gapFromPrevPx = gapFromPrevPx,
        verticalInsetPx = verticalInsetPx,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineStart = { layoutResult.getLineStart(it) },
        getLineEnd = { layoutResult.getLineEnd(it, visibleEnd = false) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) },
        getCharLeft = { layoutResult.getBoundingBox(it).left },
        getCharRight = { layoutResult.getBoundingBox(it).right }
    )

    val quoteRadius = 2.dp.toPx()
    for (qb in quoteBlocks) {
        val h = (qb.bottom - qb.top).coerceAtLeast(0f)
        val w = (qb.right - qb.left).coerceAtLeast(0f)
        if (h <= 0f || w <= 0f) continue
        drawRoundRect(
            color = quoteBgColor,
            topLeft = Offset(qb.left, qb.top),
            size = Size(w, h),
            cornerRadius = CornerRadius(quoteRadius, quoteRadius)
        )
        drawRoundRect(
            color = quoteBarColor,
            topLeft = Offset(qb.left, qb.top),
            size = Size(qb.barWidth.coerceAtMost(w), h),
            cornerRadius = CornerRadius(qb.barWidth / 2f, qb.barWidth / 2f)
        )
    }

    val codeOutset = 5.dp.toPx()
    val codeBoxes = BlockDecorationLayoutCalculator.computeCodeBoxes(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        canvasWidth = size.width,
        blockOutsetLeftPx = codeOutset,
        blockOutsetRightPx = codeOutset,
        inlinePadPx = 3.dp.toPx(),
        gapFromPrevPx = gapFromPrevPx,
        verticalInsetPx = verticalInsetPx,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineStart = { layoutResult.getLineStart(it) },
        getLineEnd = { layoutResult.getLineEnd(it, visibleEnd = false) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) },
        getCharLeft = { layoutResult.getBoundingBox(it).left },
        getCharRight = { layoutResult.getBoundingBox(it).right }
    )

    val cornerPx = 4.dp.toPx()
    val strokeWidthPx = 0.75.dp.toPx()
    for (cb in codeBoxes) {
        val w = (cb.right - cb.left).coerceAtLeast(0f)
        val h = (cb.bottom - cb.top).coerceAtLeast(0f)
        if (w <= 0f || h <= 0f) continue
        drawRoundRect(
            color = codeBgColor,
            topLeft = Offset(cb.left, cb.top),
            size = Size(w, h),
            cornerRadius = CornerRadius(cornerPx, cornerPx)
        )
        drawRoundRect(
            color = codeBorderColor,
            topLeft = Offset(cb.left, cb.top),
            size = Size(w, h),
            cornerRadius = CornerRadius(cornerPx, cornerPx),
            style = Stroke(width = strokeWidthPx)
        )
    }
}
