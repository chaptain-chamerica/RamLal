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
 */
object BlockDecorationLayoutCalculator {

    fun computeQuoteBlocks(
        text: String,
        spans: List<Span>,
        lineCount: Int,
        canvasWidth: Float = 300f,
        blockOutsetLeftPx: Float = 0f,
        blockOutsetRightPx: Float = 0f,
        inlinePadPx: Float = 0f,
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

        val fullLines = sortedSetOf<Int>()
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

                if (segStart <= ls) {
                    fullLines.add(line)
                } else {
                    var minLeft = Float.POSITIVE_INFINITY
                    var maxRight = Float.NEGATIVE_INFINITY
                    for (idx in segStart until segEnd) {
                        val cLeft = getCharLeft(idx)
                        val cRight = getCharRight(idx)
                        minLeft = minOf(minLeft, minOf(cLeft, cRight))
                        maxRight = maxOf(maxRight, maxOf(cLeft, cRight))
                    }
                    if (minLeft.isFinite() && maxRight.isFinite() && maxRight > minLeft) {
                        partialRects.add(
                            QuoteBlockRect(
                                left = minLeft - inlinePadPx,
                                top = getLineTop(line),
                                right = maxRight + inlinePadPx,
                                bottom = getLineBottom(line),
                                isFullLine = false
                            )
                        )
                    }
                }
            }
        }

        val blocks = mutableListOf<QuoteBlockRect>()
        if (fullLines.isNotEmpty()) {
            var blockStartLine = fullLines.first()
            var prevLine = blockStartLine

            for (line in fullLines) {
                if (line > prevLine + 1) {
                    blocks.add(
                        QuoteBlockRect(
                            left = -blockOutsetLeftPx,
                            top = getLineTop(blockStartLine),
                            right = canvasWidth + blockOutsetRightPx,
                            bottom = getLineBottom(prevLine),
                            isFullLine = true
                        )
                    )
                    blockStartLine = line
                }
                prevLine = line
            }
            blocks.add(
                QuoteBlockRect(
                    left = -blockOutsetLeftPx,
                    top = getLineTop(blockStartLine),
                    right = canvasWidth + blockOutsetRightPx,
                    bottom = getLineBottom(prevLine),
                    isFullLine = true
                )
            )
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

        val blockLines = sortedSetOf<Int>()
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

                val coversWholeLine = segStart <= lineStart && segEnd >= visibleLineEnd
                if (coversWholeLine) {
                    blockLines.add(line)
                } else {
                    var minLeft = Float.POSITIVE_INFINITY
                    var maxRight = Float.NEGATIVE_INFINITY
                    for (idx in segStart until segEnd) {
                        val cLeft = getCharLeft(idx)
                        val cRight = getCharRight(idx)
                        minLeft = minOf(minLeft, minOf(cLeft, cRight))
                        maxRight = maxOf(maxRight, maxOf(cLeft, cRight))
                    }
                    if (minLeft.isFinite() && maxRight.isFinite() && maxRight > minLeft) {
                        inlineBoxes.add(
                            CodeBoxRect(
                                left = (minLeft - inlinePadPx).coerceAtLeast(-blockOutsetLeftPx),
                                top = getLineTop(line) + 1f,
                                right = (maxRight + inlinePadPx).coerceAtMost(canvasWidth + blockOutsetRightPx),
                                bottom = getLineBottom(line) - 1f,
                                isBlock = false
                            )
                        )
                    }
                }
            }
        }

        val result = mutableListOf<CodeBoxRect>()
        if (blockLines.isNotEmpty()) {
            var blockStart = blockLines.first()
            var prev = blockStart
            for (line in blockLines) {
                if (line > prev + 1) {
                    result.add(
                        CodeBoxRect(
                            left = -blockOutsetLeftPx,
                            top = getLineTop(blockStart),
                            right = canvasWidth + blockOutsetRightPx,
                            bottom = getLineBottom(prev),
                            isBlock = true
                        )
                    )
                    blockStart = line
                }
                prev = line
            }
            result.add(
                CodeBoxRect(
                    left = -blockOutsetLeftPx,
                    top = getLineTop(blockStart),
                    right = canvasWidth + blockOutsetRightPx,
                    bottom = getLineBottom(prev),
                    isBlock = true
                )
            )
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

    val quoteOutsetLeft = 9.dp.toPx()
    val quoteOutsetRight = 6.dp.toPx()
    val quoteInlinePad = 5.dp.toPx()

    val quoteBlocks = BlockDecorationLayoutCalculator.computeQuoteBlocks(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        canvasWidth = size.width,
        blockOutsetLeftPx = quoteOutsetLeft,
        blockOutsetRightPx = quoteOutsetRight,
        inlinePadPx = quoteInlinePad,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineStart = { layoutResult.getLineStart(it) },
        getLineEnd = { layoutResult.getLineEnd(it, visibleEnd = false) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) },
        getCharLeft = { layoutResult.getBoundingBox(it).left },
        getCharRight = { layoutResult.getBoundingBox(it).right }
    )

    val barWidth = 3.dp.toPx()
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
            topLeft = Offset(qb.left, qb.top + 1.dp.toPx()),
            size = Size(barWidth, (h - 2.dp.toPx()).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        )
    }

    val codeOutset = 6.dp.toPx()
    val codeBoxes = BlockDecorationLayoutCalculator.computeCodeBoxes(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        canvasWidth = size.width,
        blockOutsetLeftPx = codeOutset,
        blockOutsetRightPx = codeOutset,
        inlinePadPx = 3.dp.toPx(),
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
