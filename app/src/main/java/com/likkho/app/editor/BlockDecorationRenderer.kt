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
        getLineBottom: (Int) -> Float,
        visibleLineRange: IntRange? = null
    ): List<DividerLine> {
        if (lineCount <= 0) return emptyList()
        val visFirst = visibleLineRange?.first?.coerceIn(0, lineCount - 1) ?: 0
        val visLast = visibleLineRange?.last?.coerceIn(0, lineCount - 1) ?: (lineCount - 1)
        if (visFirst > visLast) return emptyList()

        val ys = mutableListOf<Float>()

        // 1. Explicit paragraph divider spans (<hr>)
        for (sp in spans) {
            if (sp.style is SpanStyle.Divider && text.isNotEmpty()) {
                val off = sp.start.coerceIn(0, text.length - 1)
                val line = getLineForOffset(off).coerceIn(0, lineCount - 1)
                if (line in visFirst..visLast) {
                    val midY = (getLineTop(line) + getLineBottom(line)) / 2f
                    ys.add(midY)
                }
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
                        if (lastLine in visFirst..visLast) {
                            headingEndLines.add(lastLine)
                        }
                    }
                }
            }
        }

        // 3. Immediate divider under current cursor line when H1/H2/H3 tool is enabled
        if (activeHeadingLevel != null) {
            val safeCursor = cursorOffset.coerceIn(0, text.length)
            val cursorLine = getLineForOffset(safeCursor).coerceIn(0, lineCount - 1)
            if (cursorLine in visFirst..visLast) {
                headingEndLines.add(cursorLine)
            }
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
        getCharRight: (Int) -> Float,
        visibleLineRange: IntRange? = null
    ): List<BulletMarkerGeom> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val visFirst = visibleLineRange?.first?.coerceIn(0, lineCount - 1) ?: 0
        val visLast = visibleLineRange?.last?.coerceIn(0, lineCount - 1) ?: (lineCount - 1)
        if (visFirst > visLast) return emptyList()

        val out = mutableListOf<BulletMarkerGeom>()
        for (sp in spans) {
            if (sp.style !is SpanStyle.Bullet) continue
            if (sp.start < 0 || sp.start >= text.length) continue
            val s = sp.start
            val line = getLineForOffset(s).coerceIn(0, lineCount - 1)
            if (line !in visFirst..visLast) continue
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
        getCharRight: (Int) -> Float,
        visibleLineRange: IntRange? = null
    ): List<TodoCheckboxGeom> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val visFirst = visibleLineRange?.first?.coerceIn(0, lineCount - 1) ?: 0
        val visLast = visibleLineRange?.last?.coerceIn(0, lineCount - 1) ?: (lineCount - 1)
        if (visFirst > visLast) return emptyList()

        val out = mutableListOf<TodoCheckboxGeom>()
        for (sp in spans) {
            val todoStyle = sp.style as? SpanStyle.Todo ?: continue
            if (sp.start < 0 || sp.start >= text.length) continue
            val s = sp.start
            val e = sp.end.coerceIn(s + 1, text.length)
            val line = getLineForOffset(s).coerceIn(0, lineCount - 1)
            if (line !in visFirst..visLast) continue
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
        getCharRight: (Int) -> Float = { 0f },
        visibleLineRange: IntRange? = null
    ): List<QuoteBlockRect> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val visFirst = visibleLineRange?.first?.coerceIn(0, lineCount - 1) ?: 0
        val visLast = visibleLineRange?.last?.coerceIn(0, lineCount - 1) ?: (lineCount - 1)
        if (visFirst > visLast) return emptyList()

        data class FullLineEntry(val line: Int, val minLeft: Float, val maxRight: Float)
        val fullLineMap = sortedMapOf<Int, Pair<Float, Float>>()
        val partialRects = mutableListOf<QuoteBlockRect>()

        for (sp in spans) {
            if (sp.style !is SpanStyle.Quote) continue
            val s = sp.start.coerceIn(0, text.length)
            val e = sp.end.coerceIn(0, text.length)
            if (s >= e) continue
            val firstLine = getLineForOffset(s).coerceIn(0, lineCount - 1)
            if (firstLine > visLast) continue
            val lastLine = getLineForOffset((e - 1).coerceAtLeast(s)).coerceIn(firstLine, lineCount - 1)
            if (lastLine < visFirst) continue

            val rangeStart = maxOf(firstLine, visFirst)
            val rangeEnd = minOf(lastLine, visLast)
            for (line in rangeStart..rangeEnd) {
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
        getCharRight: (Int) -> Float,
        visibleLineRange: IntRange? = null
    ): List<CodeBoxRect> {
        if (text.isEmpty() || lineCount <= 0 || spans.isEmpty()) return emptyList()
        val visFirst = visibleLineRange?.first?.coerceIn(0, lineCount - 1) ?: 0
        val visLast = visibleLineRange?.last?.coerceIn(0, lineCount - 1) ?: (lineCount - 1)
        if (visFirst > visLast) return emptyList()

        data class BlockLineEntry(val line: Int, val minLeft: Float, val maxRight: Float)
        val blockLineMap = sortedMapOf<Int, Pair<Float, Float>>()
        val inlineBoxes = mutableListOf<CodeBoxRect>()

        for (sp in spans) {
            if (sp.style !is SpanStyle.Code) continue
            val s = sp.start.coerceIn(0, text.length)
            val e = sp.end.coerceIn(0, text.length)
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

private const val TALL_BLOCK_HEIGHT_THRESHOLD_PX = 400f

data class CachedBlockDecorations(
    val dividers: List<DividerLine>,
    val bullets: List<BulletMarkerGeom>,
    val todos: List<TodoCheckboxGeom>,
    val quoteBlocks: List<QuoteBlockRect>,
    val codeBoxes: List<CodeBoxRect>,
    val checkPaths: List<Path>
) {
    internal val sortedDividers: List<DividerLine> =
        if (dividers.size > 1) dividers.sortedBy { it.y } else dividers

    internal val sortedBullets: List<BulletMarkerGeom> =
        if (bullets.size > 1) bullets.sortedBy { it.centerY } else bullets

    internal val sortedTodos: List<TodoCheckboxGeom>
    internal val sortedCheckPaths: List<Path>

    internal val shortQuoteBlocks: List<QuoteBlockRect>
    internal val tallQuoteBlocks: List<QuoteBlockRect>

    internal val shortCodeBoxes: List<CodeBoxRect>
    internal val tallCodeBoxes: List<CodeBoxRect>

    init {
        if (todos.size > 1 && todos.size == checkPaths.size) {
            val paired = todos.indices.sortedBy { todos[it].boxTop }
            sortedTodos = paired.map { todos[it] }
            sortedCheckPaths = paired.map { checkPaths[it] }
        } else {
            sortedTodos = todos
            sortedCheckPaths = checkPaths
        }

        if (quoteBlocks.isEmpty()) {
            shortQuoteBlocks = emptyList()
            tallQuoteBlocks = emptyList()
        } else {
            val shorts = ArrayList<QuoteBlockRect>(quoteBlocks.size)
            val talls = ArrayList<QuoteBlockRect>()
            for (qb in quoteBlocks) {
                if ((qb.bottom - qb.top) > TALL_BLOCK_HEIGHT_THRESHOLD_PX) talls.add(qb) else shorts.add(qb)
            }
            if (shorts.size > 1) shorts.sortBy { it.top }
            shortQuoteBlocks = shorts
            tallQuoteBlocks = talls
        }

        if (codeBoxes.isEmpty()) {
            shortCodeBoxes = emptyList()
            tallCodeBoxes = emptyList()
        } else {
            val shorts = ArrayList<CodeBoxRect>(codeBoxes.size)
            val talls = ArrayList<CodeBoxRect>()
            for (cb in codeBoxes) {
                if ((cb.bottom - cb.top) > TALL_BLOCK_HEIGHT_THRESHOLD_PX) talls.add(cb) else shorts.add(cb)
            }
            if (shorts.size > 1) shorts.sortBy { it.top }
            shortCodeBoxes = shorts
            tallCodeBoxes = talls
        }
    }
}

fun computeBlockDecorationCache(
    layoutResult: TextLayoutResult,
    spans: List<Span>,
    canvasWidth: Float,
    densityScale: Float,
    activeHeadingLevel: Int? = null,
    cursorOffset: Int = 0,
    visibleLineRange: IntRange? = null
): CachedBlockDecorations {
    val text = layoutResult.layoutInput.text.text
    if (layoutResult.lineCount <= 0) {
        return CachedBlockDecorations(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }

    val dividers = BlockDecorationLayoutCalculator.computeDividerLines(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        activeHeadingLevel = activeHeadingLevel,
        cursorOffset = cursorOffset,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) },
        visibleLineRange = visibleLineRange
    )

    if (text.isEmpty() || spans.isEmpty()) {
        return CachedBlockDecorations(dividers, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }

    val bullets = BlockDecorationLayoutCalculator.computeBulletMarkers(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        densityScale = densityScale,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) },
        getCharLeft = { layoutResult.getBoundingBox(it).left },
        getCharRight = { layoutResult.getBoundingBox(it).right },
        visibleLineRange = visibleLineRange
    )

    val todos = BlockDecorationLayoutCalculator.computeTodoCheckboxes(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        densityScale = densityScale,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) },
        getCharLeft = { layoutResult.getBoundingBox(it).left },
        getCharRight = { layoutResult.getBoundingBox(it).right },
        visibleLineRange = visibleLineRange
    )

    val checkPaths = todos.map { td ->
        if (td.checked) {
            Path().apply {
                moveTo(td.boxLeft + td.boxSize * 0.23f, td.boxTop + td.boxSize * 0.52f)
                lineTo(td.boxLeft + td.boxSize * 0.43f, td.boxTop + td.boxSize * 0.72f)
                lineTo(td.boxLeft + td.boxSize * 0.77f, td.boxTop + td.boxSize * 0.30f)
            }
        } else {
            Path()
        }
    }

    val quoteOutsetLeft = 8f * densityScale
    val quoteOutsetRight = 6f * densityScale
    val quoteInlinePad = 3.5f * densityScale
    val barWidthPx = 2.5f * densityScale
    val gapFromPrevPx = 1.5f * densityScale
    val verticalInsetPx = 1f * densityScale

    val quoteBlocks = BlockDecorationLayoutCalculator.computeQuoteBlocks(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        canvasWidth = canvasWidth,
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
        getCharRight = { layoutResult.getBoundingBox(it).right },
        visibleLineRange = visibleLineRange
    )

    val codeOutset = 5f * densityScale
    val codeBoxes = BlockDecorationLayoutCalculator.computeCodeBoxes(
        text = text,
        spans = spans,
        lineCount = layoutResult.lineCount,
        canvasWidth = canvasWidth,
        blockOutsetLeftPx = codeOutset,
        blockOutsetRightPx = codeOutset,
        inlinePadPx = 3f * densityScale,
        gapFromPrevPx = gapFromPrevPx,
        verticalInsetPx = verticalInsetPx,
        getLineForOffset = { layoutResult.getLineForOffset(it) },
        getLineStart = { layoutResult.getLineStart(it) },
        getLineEnd = { layoutResult.getLineEnd(it, visibleEnd = false) },
        getLineTop = { layoutResult.getLineTop(it) },
        getLineBottom = { layoutResult.getLineBottom(it) },
        getCharLeft = { layoutResult.getBoundingBox(it).left },
        getCharRight = { layoutResult.getBoundingBox(it).right },
        visibleLineRange = visibleLineRange
    )

    return CachedBlockDecorations(
        dividers = dividers,
        bullets = bullets,
        todos = todos,
        quoteBlocks = quoteBlocks,
        codeBoxes = codeBoxes,
        checkPaths = checkPaths
    )
}

internal fun collectVisibleQuoteBlocks(
    cache: CachedBlockDecorations,
    visibleTopPx: Float,
    visibleBottomPx: Float
): List<QuoteBlockRect> {
    if (cache.quoteBlocks.isEmpty()) return emptyList()
    val useCull = visibleTopPx.isFinite() || visibleBottomPx.isFinite()
    if (!useCull) return cache.quoteBlocks
    val out = mutableListOf<QuoteBlockRect>()
    for (qb in cache.tallQuoteBlocks) {
        if (qb.bottom >= visibleTopPx && qb.top <= visibleBottomPx) out.add(qb)
    }
    val startIdx = if (visibleTopPx.isFinite()) {
        lowerBoundByTop(cache.shortQuoteBlocks, visibleTopPx - TALL_BLOCK_HEIGHT_THRESHOLD_PX) { it.top }
    } else 0
    for (i in startIdx until cache.shortQuoteBlocks.size) {
        val qb = cache.shortQuoteBlocks[i]
        if (qb.top > visibleBottomPx) break
        if (qb.bottom >= visibleTopPx) out.add(qb)
    }
    return out
}

internal fun collectVisibleCodeBoxes(
    cache: CachedBlockDecorations,
    visibleTopPx: Float,
    visibleBottomPx: Float
): List<CodeBoxRect> {
    if (cache.codeBoxes.isEmpty()) return emptyList()
    val useCull = visibleTopPx.isFinite() || visibleBottomPx.isFinite()
    if (!useCull) return cache.codeBoxes
    val out = mutableListOf<CodeBoxRect>()
    for (cb in cache.tallCodeBoxes) {
        if (cb.bottom >= visibleTopPx && cb.top <= visibleBottomPx) out.add(cb)
    }
    val startIdx = if (visibleTopPx.isFinite()) {
        lowerBoundByTop(cache.shortCodeBoxes, visibleTopPx - TALL_BLOCK_HEIGHT_THRESHOLD_PX) { it.top }
    } else 0
    for (i in startIdx until cache.shortCodeBoxes.size) {
        val cb = cache.shortCodeBoxes[i]
        if (cb.top > visibleBottomPx) break
        if (cb.bottom >= visibleTopPx) out.add(cb)
    }
    return out
}

fun DrawScope.drawCachedBlockDecorations(
    cache: CachedBlockDecorations,
    quoteBgColor: Color,
    quoteBarColor: Color,
    codeBgColor: Color,
    codeBorderColor: Color,
    dividerColor: Color = codeBorderColor,
    bulletColor: Color = quoteBarColor,
    todoUncheckedBorderColor: Color = codeBorderColor,
    todoUncheckedBgColor: Color = codeBgColor,
    todoCheckedFillColor: Color = quoteBarColor,
    visibleTopPx: Float = Float.NEGATIVE_INFINITY,
    visibleBottomPx: Float = Float.POSITIVE_INFINITY
) {
    val useCull = visibleTopPx.isFinite() || visibleBottomPx.isFinite()
    val hairlineStrokePx = 0.75.dp.toPx()

    val divs = cache.sortedDividers
    if (divs.isNotEmpty()) {
        val divStart = if (useCull && visibleTopPx.isFinite()) {
            lowerBoundByTop(divs, visibleTopPx - 4f) { it.y }
        } else 0
        for (i in divStart until divs.size) {
            val div = divs[i]
            if (useCull && div.y > visibleBottomPx + 4f) break
            drawLine(
                color = dividerColor,
                start = Offset(0f, div.y),
                end = Offset(size.width, div.y),
                strokeWidth = hairlineStrokePx
            )
        }
    }

    val bullets = cache.sortedBullets
    if (bullets.isNotEmpty()) {
        val bStart = if (useCull && visibleTopPx.isFinite()) {
            lowerBoundByTop(bullets, visibleTopPx - 24f) { it.centerY }
        } else 0
        for (i in bStart until bullets.size) {
            val bm = bullets[i]
            if (useCull && bm.centerY - bm.radius > visibleBottomPx) break
            if (useCull && bm.centerY + bm.radius < visibleTopPx) continue
            drawCircle(
                color = bulletColor,
                radius = bm.radius,
                center = Offset(bm.centerX, bm.centerY)
            )
        }
    }

    val todos = cache.sortedTodos
    if (todos.isNotEmpty()) {
        val todoBorderPx = 1.6.dp.toPx()
        val checkMinStrokePx = 1.8.dp.toPx()
        val tStart = if (useCull && visibleTopPx.isFinite()) {
            lowerBoundByTop(todos, visibleTopPx - 64f) { it.boxTop }
        } else 0
        for (i in tStart until todos.size) {
            val td = todos[i]
            if (useCull && td.boxTop > visibleBottomPx) break
            if (useCull && td.boxTop + td.boxSize < visibleTopPx) continue
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
                drawPath(
                    path = cache.sortedCheckPaths[i],
                    color = Color.White,
                    style = Stroke(
                        width = (td.boxSize * 0.13f).coerceAtLeast(checkMinStrokePx),
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

    val quoteRadius = 2.dp.toPx()
    fun drawQuote(qb: QuoteBlockRect) {
        val h = (qb.bottom - qb.top).coerceAtLeast(0f)
        val w = (qb.right - qb.left).coerceAtLeast(0f)
        if (h <= 0f || w <= 0f) return
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
    if (cache.tallQuoteBlocks.isNotEmpty()) {
        for (qb in cache.tallQuoteBlocks) {
            if (useCull && (qb.bottom < visibleTopPx || qb.top > visibleBottomPx)) continue
            drawQuote(qb)
        }
    }
    if (cache.shortQuoteBlocks.isNotEmpty()) {
        val qStart = if (useCull && visibleTopPx.isFinite()) {
            lowerBoundByTop(cache.shortQuoteBlocks, visibleTopPx - TALL_BLOCK_HEIGHT_THRESHOLD_PX) { it.top }
        } else 0
        for (i in qStart until cache.shortQuoteBlocks.size) {
            val qb = cache.shortQuoteBlocks[i]
            if (useCull && qb.top > visibleBottomPx) break
            if (useCull && qb.bottom < visibleTopPx) continue
            drawQuote(qb)
        }
    }

    val cornerPx = 4.dp.toPx()
    fun drawCode(cb: CodeBoxRect) {
        val w = (cb.right - cb.left).coerceAtLeast(0f)
        val h = (cb.bottom - cb.top).coerceAtLeast(0f)
        if (w <= 0f || h <= 0f) return
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
            style = Stroke(width = hairlineStrokePx)
        )
    }
    if (cache.tallCodeBoxes.isNotEmpty()) {
        for (cb in cache.tallCodeBoxes) {
            if (useCull && (cb.bottom < visibleTopPx || cb.top > visibleBottomPx)) continue
            drawCode(cb)
        }
    }
    if (cache.shortCodeBoxes.isNotEmpty()) {
        val cStart = if (useCull && visibleTopPx.isFinite()) {
            lowerBoundByTop(cache.shortCodeBoxes, visibleTopPx - TALL_BLOCK_HEIGHT_THRESHOLD_PX) { it.top }
        } else 0
        for (i in cStart until cache.shortCodeBoxes.size) {
            val cb = cache.shortCodeBoxes[i]
            if (useCull && cb.top > visibleBottomPx) break
            if (useCull && cb.bottom < visibleTopPx) continue
            drawCode(cb)
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
    val cache = computeBlockDecorationCache(
        layoutResult = layoutResult,
        spans = spans,
        canvasWidth = size.width,
        densityScale = density,
        activeHeadingLevel = activeHeadingLevel,
        cursorOffset = cursorOffset
    )
    drawCachedBlockDecorations(
        cache = cache,
        quoteBgColor = quoteBgColor,
        quoteBarColor = quoteBarColor,
        codeBgColor = codeBgColor,
        codeBorderColor = codeBorderColor,
        dividerColor = dividerColor,
        bulletColor = bulletColor,
        todoUncheckedBorderColor = todoUncheckedBorderColor,
        todoUncheckedBgColor = todoUncheckedBgColor,
        todoCheckedFillColor = todoCheckedFillColor
    )
}
