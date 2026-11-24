package com.likkho.app.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

data class DividerLine(
    val y: Float
)

data class BulletMarkerGeom(
    val centerX: Float,
    val centerY: Float,
    val radius: Float
)

data class TodoCheckboxGeom(
    val spanStart: Int,
    val checked: Boolean,
    val boxLeft: Float,
    val boxTop: Float,
    val boxSize: Float,
    val hitLeft: Float,
    val hitTop: Float,
    val hitRight: Float,
    val hitBottom: Float
)

/**
 * Pure layout geometry calculator for Quote block bars/tints, Code blocks / inline code boxes,
 * paragraph dividers, and H1/H2/H3 heading underline dividers.
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

    fun computeDividerLines(
        text: String,
        spans: List<Span>,
        lineCount: Int,
        activeHeadingLevel: Int? = null,
        cursorOffset: Int = 0,
        getLineForOffset: (Int) -> Int,
        getLineTop: (Int) -> Float,
        getLineBottom: (Int) -> Float
    ): List<DividerLine> {
        if (lineCount <= 0) return emptyList()
        val ys = mutableListOf<Float>()

        // 1. Explicit paragraph divider spans (<hr>)
        for (sp in spans) {
            if (sp.style is SpanStyle.Divider && text.isNotEmpty()) {
                val off = sp.start.coerceIn(0, text.length - 1)
                val line = getLineForOffset(off).coerceIn(0, lineCount - 1)
                val midY = (getLineTop(line) + getLineBottom(line)) / 2f
                ys.add(midY)
            }
        }

        // 2. Dividers directly underneath H1/H2/H3 headings
        val headingEndLines = sortedSetOf<Int>()
        if (text.isNotEmpty()) {
            for (sp in spans) {
                if (sp.style is SpanStyle.Heading) {
                    val s = sp.start.coerceIn(0, text.length)
                    var e = sp.end.coerceIn(0, text.length)
                    while (e > s && text[e - 1] == '\n') e--
                    if (s < e) {
                        val lastLine = getLineForOffset((e - 1).coerceAtLeast(s)).coerceIn(0, lineCount - 1)
                        headingEndLines.add(lastLine)
                    }
                }
            }
        }

        // 3. Immediate divider under current cursor line when H1/H2/H3 tool is enabled
        if (activeHeadingLevel != null) {
            val safeCursor = cursorOffset.coerceIn(0, text.length)
            val cursorLine = getLineForOffset(safeCursor).coerceIn(0, lineCount - 1)
            headingEndLines.add(cursorLine)
        }

        for (line in headingEndLines) {
            val bottomY = (getLineBottom(line) - 1f).coerceAtLeast(getLineTop(line))
            ys.add(bottomY)
        }

        if (ys.isEmpty()) return emptyList()
        val deduped = mutableListOf<DividerLine>()
        for (y in ys.sorted()) {
            if (deduped.isEmpty() || kotlin.math.abs(y - deduped.last().y) > 2f) {
                deduped.add(DividerLine(y))
            }
        }
        return deduped
    }

    fun computeBulletMarkers(
        text: String,
        spans: List<Span>,
        lineCount: Int,
        densityScale: Float = 1f,
        getLineForOffset: (Int) -> Int,
        getLineTop: (Int) -> Float,
        getLineBottom: (Int) -> Float,
        getCharLeft: (Int) -> Float,
        getCharRight: (Int) -> Float
    ): List<BulletMarkerGeom> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val out = mutableListOf<BulletMarkerGeom>()
        for (sp in spans) {
            if (sp.style !is SpanStyle.Bullet) continue
            if (sp.start < 0 || sp.start >= text.length) continue
            val s = sp.start
            val line = getLineForOffset(s).coerceIn(0, lineCount - 1)
            val lineTop = getLineTop(line)
            val lineBottom = getLineBottom(line)
            val lineH = (lineBottom - lineTop).coerceAtLeast(12f * densityScale)
            val glyphLeft = getCharLeft(s)
            val glyphRight = getCharRight(s)
            val glyphW = (glyphRight - glyphLeft).coerceAtLeast(8f * densityScale)
            val radius = (lineH * 0.135f).coerceIn(2.2f * densityScale, 5f * densityScale)
            val cx = glyphLeft + (glyphW * 0.48f).coerceAtLeast(radius + 1f * densityScale)
            val cy = (lineTop + lineBottom) / 2f
            out.add(BulletMarkerGeom(centerX = cx, centerY = cy, radius = radius))
        }
        return out
    }

    fun computeTodoCheckboxes(
        text: String,
        spans: List<Span>,
        lineCount: Int,
        densityScale: Float = 1f,
        getLineForOffset: (Int) -> Int,
        getLineTop: (Int) -> Float,
        getLineBottom: (Int) -> Float,
        getCharLeft: (Int) -> Float,
        getCharRight: (Int) -> Float
    ): List<TodoCheckboxGeom> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val out = mutableListOf<TodoCheckboxGeom>()
        for (sp in spans) {
            val todoStyle = sp.style as? SpanStyle.Todo ?: continue
            if (sp.start < 0 || sp.start >= text.length) continue
            val s = sp.start
            val e = sp.end.coerceIn(s + 1, text.length)
            val line = getLineForOffset(s).coerceIn(0, lineCount - 1)
            val lineTop = getLineTop(line)
            val lineBottom = getLineBottom(line)
            val lineH = (lineBottom - lineTop).coerceAtLeast(16f * densityScale)
            val glyphLeft = getCharLeft(s)
            val glyphRight = getCharRight(s)
            val prefixRight = getCharRight((e - 1).coerceIn(0, text.length - 1))
            val availW = (glyphRight - glyphLeft).coerceAtLeast(14f * densityScale)
            val boxSize = (lineH * 0.56f)
                .coerceIn(14f * densityScale, 24f * densityScale)
                .coerceAtMost(availW * 0.92f)
            val boxLeft = glyphLeft + ((availW - boxSize) * 0.32f).coerceAtLeast(0f)
            val centerY = (lineTop + lineBottom) / 2f
            val boxTop = centerY - boxSize / 2f

            val hitHalfH = maxOf(lineH / 2f, 18f * densityScale)
            val hitLeft = (glyphLeft - 4f * densityScale).coerceAtLeast(0f)
            val hitRight = maxOf(boxLeft + boxSize + 4f * densityScale, prefixRight - 1f * densityScale)
            val hitTop = (centerY - hitHalfH).coerceAtLeast(0f)
            val hitBottom = centerY + hitHalfH

            out.add(
                TodoCheckboxGeom(
                    spanStart = s,
                    checked = todoStyle.checked,
                    boxLeft = boxLeft,
                    boxTop = boxTop,
                    boxSize = boxSize,
                    hitLeft = hitLeft,
                    hitTop = hitTop,
                    hitRight = hitRight,
                    hitBottom = hitBottom
                )
            )
        }
        return out
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

    fun computeStaticDecorations(
        layoutResult: TextLayoutResult,
        spans: List<Span>,
        densityScale: Float
    ): CachedBlockDecorations {
        val text = layoutResult.layoutInput.text.text
        val lineCount = layoutResult.lineCount
        if (lineCount <= 0) {
            return CachedBlockDecorations(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        val staticDividers = computeDividerLines(
            text = text,
            spans = spans,
            lineCount = lineCount,
            activeHeadingLevel = null,
            cursorOffset = 0,
            getLineForOffset = { layoutResult.getLineForOffset(it) },
            getLineTop = { layoutResult.getLineTop(it) },
            getLineBottom = { layoutResult.getLineBottom(it) }
        )
        if (text.isEmpty() || spans.isEmpty()) {
            return CachedBlockDecorations(staticDividers, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val bullets = computeBulletMarkers(
            text = text,
            spans = spans,
            lineCount = lineCount,
            densityScale = densityScale,
            getLineForOffset = { layoutResult.getLineForOffset(it) },
            getLineTop = { layoutResult.getLineTop(it) },
            getLineBottom = { layoutResult.getLineBottom(it) },
            getCharLeft = { layoutResult.getBoundingBox(it).left },
            getCharRight = { layoutResult.getBoundingBox(it).right }
        )
        val todos = computeTodoCheckboxes(
            text = text,
            spans = spans,
            lineCount = lineCount,
            densityScale = densityScale,
            getLineForOffset = { layoutResult.getLineForOffset(it) },
            getLineTop = { layoutResult.getLineTop(it) },
            getLineBottom = { layoutResult.getLineBottom(it) },
            getCharLeft = { layoutResult.getBoundingBox(it).left },
            getCharRight = { layoutResult.getBoundingBox(it).right }
        )
        val quoteBlocks = computeQuoteBlocks(
            text = text,
            spans = spans,
            lineCount = lineCount,
            canvasWidth = layoutResult.size.width.toFloat(),
            blockOutsetLeftPx = 8f * densityScale,
            blockOutsetRightPx = 6f * densityScale,
            inlinePadPx = 3.5f * densityScale,
            barWidthPx = 2.5f * densityScale,
            gapFromPrevPx = 1.5f * densityScale,
            verticalInsetPx = 1f * densityScale,
            getLineForOffset = { layoutResult.getLineForOffset(it) },
            getLineStart = { layoutResult.getLineStart(it) },
            getLineEnd = { layoutResult.getLineEnd(it, visibleEnd = false) },
            getLineTop = { layoutResult.getLineTop(it) },
            getLineBottom = { layoutResult.getLineBottom(it) },
            getCharLeft = { layoutResult.getBoundingBox(it).left },
            getCharRight = { layoutResult.getBoundingBox(it).right }
        )
        val codeBoxes = computeCodeBoxes(
            text = text,
            spans = spans,
            lineCount = lineCount,
            canvasWidth = layoutResult.size.width.toFloat(),
            blockOutsetLeftPx = 5f * densityScale,
            blockOutsetRightPx = 5f * densityScale,
            inlinePadPx = 3f * densityScale,
            gapFromPrevPx = 1.5f * densityScale,
            verticalInsetPx = 1f * densityScale,
            getLineForOffset = { layoutResult.getLineForOffset(it) },
            getLineStart = { layoutResult.getLineStart(it) },
            getLineEnd = { layoutResult.getLineEnd(it, visibleEnd = false) },
            getLineTop = { layoutResult.getLineTop(it) },
            getLineBottom = { layoutResult.getLineBottom(it) },
            getCharLeft = { layoutResult.getBoundingBox(it).left },
            getCharRight = { layoutResult.getBoundingBox(it).right }
        )
        return CachedBlockDecorations(
            staticDividers = staticDividers,
            bullets = bullets,
            todos = todos,
            quoteBlocks = quoteBlocks,
            codeBoxes = codeBoxes
        )
    }

    fun computeActiveHeadingDivider(
        layoutResult: TextLayoutResult,
        cursorLine: Int,
        staticDividers: List<DividerLine>
    ): DividerLine? {
        if (layoutResult.lineCount <= 0 || cursorLine !in 0 until layoutResult.lineCount) return null
        val bottomY = (layoutResult.getLineBottom(cursorLine) - 1f).coerceAtLeast(layoutResult.getLineTop(cursorLine))
        for (i in staticDividers.indices) {
            if (kotlin.math.abs(bottomY - staticDividers[i].y) <= 2f) return null
        }
        return DividerLine(bottomY)
    }
}

data class CachedBlockDecorations(
    val staticDividers: List<DividerLine>,
    val bullets: List<BulletMarkerGeom>,
    val todos: List<TodoCheckboxGeom>,
    val quoteBlocks: List<QuoteBlockRect>,
    val codeBoxes: List<CodeBoxRect>
)

fun DrawScope.drawCachedBlockDecorations(
    cached: CachedBlockDecorations,
    activeHeadingDivider: DividerLine?,
    reusableCheckPath: Path,
    quoteBgColor: Color,
    quoteBarColor: Color,
    codeBgColor: Color,
    codeBorderColor: Color,
    dividerColor: Color = codeBorderColor,
    bulletColor: Color = quoteBarColor,
    todoUncheckedBorderColor: Color = codeBorderColor,
    todoUncheckedBgColor: Color = codeBgColor,
    todoCheckedFillColor: Color = quoteBarColor
) {
    val hairlineStrokePx = 0.75.dp.toPx()
    val dividers = cached.staticDividers
    for (i in dividers.indices) {
        val y = dividers[i].y
        drawLine(
            color = dividerColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = hairlineStrokePx
        )
    }
    if (activeHeadingDivider != null) {
        drawLine(
            color = dividerColor,
            start = Offset(0f, activeHeadingDivider.y),
            end = Offset(size.width, activeHeadingDivider.y),
            strokeWidth = hairlineStrokePx
        )
    }

    val bullets = cached.bullets
    for (i in bullets.indices) {
        val bm = bullets[i]
        drawCircle(
            color = bulletColor,
            radius = bm.radius,
            center = Offset(bm.centerX, bm.centerY)
        )
    }

    val todos = cached.todos
    if (todos.isNotEmpty()) {
        val todoBorderPx = 1.6.dp.toPx()
        val minCheckStrokePx = 1.8.dp.toPx()
        for (i in todos.indices) {
            val td = todos[i]
            val r = td.boxSize * 0.22f
            val topLeft = Offset(td.boxLeft, td.boxTop)
            val boxSz = Size(td.boxSize, td.boxSize)
            if (td.checked) {
                drawRoundRect(
                    color = todoCheckedFillColor,
                    topLeft = topLeft,
                    size = boxSz,
                    cornerRadius = CornerRadius(r, r)
                )
                reusableCheckPath.reset()
                reusableCheckPath.moveTo(td.boxLeft + td.boxSize * 0.23f, td.boxTop + td.boxSize * 0.52f)
                reusableCheckPath.lineTo(td.boxLeft + td.boxSize * 0.43f, td.boxTop + td.boxSize * 0.72f)
                reusableCheckPath.lineTo(td.boxLeft + td.boxSize * 0.77f, td.boxTop + td.boxSize * 0.30f)
                drawPath(
                    path = reusableCheckPath,
                    color = Color.White,
                    style = Stroke(
                        width = (td.boxSize * 0.13f).coerceAtLeast(minCheckStrokePx),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            } else {
                drawRoundRect(
                    color = todoUncheckedBgColor,
                    topLeft = topLeft,
                    size = boxSz,
                    cornerRadius = CornerRadius(r, r)
                )
                drawRoundRect(
                    color = todoUncheckedBorderColor,
                    topLeft = topLeft,
                    size = boxSz,
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = todoBorderPx)
                )
            }
        }
    }

    val quoteBlocks = cached.quoteBlocks
    if (quoteBlocks.isNotEmpty()) {
        val quoteRadius = 2.dp.toPx()
        for (i in quoteBlocks.indices) {
            val qb = quoteBlocks[i]
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
    }

    val codeBoxes = cached.codeBoxes
    if (codeBoxes.isNotEmpty()) {
        val cornerPx = 4.dp.toPx()
        val strokeWidthPx = 0.75.dp.toPx()
        for (i in codeBoxes.indices) {
            val cb = codeBoxes[i]
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
}

fun DrawScope.drawBlockDecorations(
    layoutResult: TextLayoutResult,
    spans: List<Span>,
    quoteBgColor: Color,
    quoteBarColor: Color,
    codeBgColor: Color,
    codeBorderColor: Color,
    dividerColor: Color = codeBorderColor,
    activeHeadingLevel: Int? = null,
    cursorOffset: Int = 0,
    bulletColor: Color = quoteBarColor,
    todoUncheckedBorderColor: Color = codeBorderColor,
    todoUncheckedBgColor: Color = codeBgColor,
    todoCheckedFillColor: Color = quoteBarColor
) {
    val text = layoutResult.layoutInput.text.text
    if (layoutResult.lineCount <= 0) return

    val dividers = BlockDecorationLayoutCalculator.computeDividerLines(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        activeHeadingLevel = activeHeadingLevel,
        cursorOffset = cursorOffset,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) }
    )
    val hairlineStrokePx = 0.75.dp.toPx()
    for (div in dividers) {
        drawLine(
            color = dividerColor,
            start = Offset(0f, div.y),
            end = Offset(size.width, div.y),
            strokeWidth = hairlineStrokePx
        )
    }

    if (text.isEmpty() || spans.isEmpty()) return

    val bullets = BlockDecorationLayoutCalculator.computeBulletMarkers(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        densityScale = density,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) },
        getCharLeft = { layoutResult.getBoundingBox(it).left },
        getCharRight = { layoutResult.getBoundingBox(it).right }
    )
    for (bm in bullets) {
        drawCircle(
            color = bulletColor,
            radius = bm.radius,
            center = Offset(bm.centerX, bm.centerY)
        )
    }

    val todos = BlockDecorationLayoutCalculator.computeTodoCheckboxes(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        densityScale = density,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) },
        getCharLeft = { layoutResult.getBoundingBox(it).left },
        getCharRight = { layoutResult.getBoundingBox(it).right }
    )
    val todoBorderPx = 1.6.dp.toPx()
    for (td in todos) {
        val r = td.boxSize * 0.22f
        val topLeft = Offset(td.boxLeft, td.boxTop)
        val boxSz = Size(td.boxSize, td.boxSize)
        if (td.checked) {
            drawRoundRect(
                color = todoCheckedFillColor,
                topLeft = topLeft,
                size = boxSz,
                cornerRadius = CornerRadius(r, r)
            )
            val checkPath = Path().apply {
                moveTo(td.boxLeft + td.boxSize * 0.23f, td.boxTop + td.boxSize * 0.52f)
                lineTo(td.boxLeft + td.boxSize * 0.43f, td.boxTop + td.boxSize * 0.72f)
                lineTo(td.boxLeft + td.boxSize * 0.77f, td.boxTop + td.boxSize * 0.30f)
            }
            drawPath(
                path = checkPath,
                color = Color.White,
                style = Stroke(
                    width = (td.boxSize * 0.13f).coerceAtLeast(1.8.dp.toPx()),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        } else {
            drawRoundRect(
                color = todoUncheckedBgColor,
                topLeft = topLeft,
                size = boxSz,
                cornerRadius = CornerRadius(r, r)
            )
            drawRoundRect(
                color = todoUncheckedBorderColor,
                topLeft = topLeft,
                size = boxSz,
                cornerRadius = CornerRadius(r, r),
                style = Stroke(width = todoBorderPx)
            )
        }
    }

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
