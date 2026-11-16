package com.likkho.app.editor.model

import com.likkho.app.storage.model.ContentBlockRecord
import com.likkho.app.storage.model.SpanStyleRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Stable block ID generator.
 */
fun generateBlockId(): String = UUID.randomUUID().toString()

/**
 * Text alignment options for blocks.
 */
@Serializable
enum class TextAlignment(val value: String) {
    @SerialName("left") LEFT("left"),
    @SerialName("center") CENTER("center"),
    @SerialName("right") RIGHT("right"),
    @SerialName("justify") JUSTIFY("justify");

    companion object {
        fun fromString(str: String): TextAlignment = when (str.lowercase().trim()) {
            "center" -> CENTER
            "right", "end" -> RIGHT
            "justify" -> JUSTIFY
            else -> LEFT
        }
    }
}

/**
 * Rich-text span styling styles.
 */
@Serializable
sealed interface SpanStyle {
    val styleKey: String
    val valueString: String?

    @Serializable
    @SerialName("bold")
    data object Bold : SpanStyle {
        override val styleKey: String = "bold"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("italic")
    data object Italic : SpanStyle {
        override val styleKey: String = "italic"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("underline")
    data object Underline : SpanStyle {
        override val styleKey: String = "underline"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("strike")
    data object Strike : SpanStyle {
        override val styleKey: String = "strike"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("superscript")
    data object Superscript : SpanStyle {
        override val styleKey: String = "superscript"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("subscript")
    data object Subscript : SpanStyle {
        override val styleKey: String = "subscript"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("color")
    data class Color(val hex: String) : SpanStyle {
        override val styleKey: String = "color"
        override val valueString: String get() = hex
    }

    @Serializable
    @SerialName("highlight")
    data class Highlight(val hex: String) : SpanStyle {
        override val styleKey: String = "highlight"
        override val valueString: String get() = hex
    }

    @Serializable
    @SerialName("sizePx")
    data class SizePx(val sizePx: Float) : SpanStyle {
        override val styleKey: String = "sizePx"
        override val valueString: String get() = sizePx.toString()
    }

    @Serializable
    @SerialName("fontId")
    data class FontId(val fontId: String) : SpanStyle {
        override val styleKey: String = "fontId"
        override val valueString: String get() = fontId
    }

    @Serializable
    @SerialName("link")
    data class Link(val url: String) : SpanStyle {
        override val styleKey: String = "link"
        override val valueString: String get() = url
    }

    @Serializable
    @SerialName("heading")
    data class Heading(val level: Int) : SpanStyle {
        init {
            require(level in 1..3) { "Heading level must be 1, 2, or 3" }
        }
        override val styleKey: String = "heading"
        override val valueString: String get() = level.toString()
    }

    @Serializable
    @SerialName("quote")
    data object Quote : SpanStyle {
        override val styleKey: String = "quote"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("code")
    data object Code : SpanStyle {
        override val styleKey: String = "code"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("divider")
    data object Divider : SpanStyle {
        override val styleKey: String = "divider"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("bullet")
    data object Bullet : SpanStyle {
        override val styleKey: String = "bullet"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("numbered")
    data object Numbered : SpanStyle {
        override val styleKey: String = "numbered"
        override val valueString: String? = null
    }

    @Serializable
    @SerialName("todo")
    data class Todo(val checked: Boolean = false) : SpanStyle {
        override val styleKey: String = "todo"
        override val valueString: String get() = checked.toString()
    }

    @Serializable
    @SerialName("align")
    data class Align(val alignment: TextAlignment) : SpanStyle {
        override val styleKey: String = "align"
        override val valueString: String get() = alignment.value
    }

    @Serializable
    @SerialName("indent")
    data class Indent(val level: Int) : SpanStyle {
        init {
            require(level in 1..6) { "Indent level must be 1..6" }
        }
        override val styleKey: String = "indent"
        override val valueString: String get() = level.toString()
    }

    companion object {
        fun fromRecord(record: SpanStyleRecord): SpanStyle = when (record.style.lowercase()) {
            "bold" -> Bold
            "italic" -> Italic
            "underline" -> Underline
            "strike" -> Strike
            "superscript" -> Superscript
            "subscript" -> Subscript
            "color" -> Color(record.value ?: "#000000")
            "highlight" -> Highlight(record.value ?: "#FFFF00")
            "sizepx" -> SizePx(record.value?.toFloatOrNull() ?: 16f)
            "fontid" -> FontId(record.value ?: "default")
            "link" -> Link(record.value ?: "")
            "heading" -> Heading(record.value?.toIntOrNull()?.coerceIn(1, 3) ?: 1)
            "quote" -> Quote
            "code" -> Code
            "divider" -> Divider
            "bullet" -> Bullet
            "numbered" -> Numbered
            "todo" -> Todo(record.value.equals("true", ignoreCase = true))
            "align" -> Align(TextAlignment.fromString(record.value ?: "left"))
            "indent" -> Indent((record.value?.toIntOrNull() ?: 1).coerceIn(1, 6))
            else -> Bold
        }
    }
}

/**
 * Character offset span using UTF-16 offsets (native to Kotlin String and Android Compose).
 */
@Serializable
data class Span(
    val start: Int,
    val end: Int,
    val style: SpanStyle
) {
    fun toRecord(): SpanStyleRecord = SpanStyleRecord(
        start = start,
        end = end,
        style = style.styleKey,
        value = style.valueString
    )

    companion object {
        fun fromRecord(record: SpanStyleRecord): Span = Span(
            start = record.start,
            end = record.end,
            style = SpanStyle.fromRecord(record)
        )
    }
}

/**
 * Types of blocks supported by the Likkho rich text document model.
 */
@Serializable
sealed interface BlockType {
    val typeName: String

    @Serializable
    @SerialName("paragraph")
    data object Paragraph : BlockType {
        override val typeName: String = "paragraph"
    }

    @Serializable
    @SerialName("heading")
    data class Heading(val level: Int = 1) : BlockType {
        init {
            require(level in 1..3) { "Heading level must be 1, 2, or 3" }
        }
        override val typeName: String get() = "heading$level"
    }

    @Serializable
    @SerialName("bullet")
    data object Bullet : BlockType {
        override val typeName: String = "bullet"
    }

    @Serializable
    @SerialName("numbered")
    data object Numbered : BlockType {
        override val typeName: String = "numbered"
    }

    @Serializable
    @SerialName("todo")
    data class Todo(val checked: Boolean = false) : BlockType {
        override val typeName: String = "todo"
    }

    @Serializable
    @SerialName("quote")
    data object Quote : BlockType {
        override val typeName: String = "quote"
    }

    @Serializable
    @SerialName("code")
    data object Code : BlockType {
        override val typeName: String = "code"
    }

    @Serializable
    @SerialName("divider")
    data object Divider : BlockType {
        override val typeName: String = "divider"
    }

    @Serializable
    @SerialName("latex")
    data class Latex(val formula: String = "") : BlockType {
        override val typeName: String = "latex"
    }

    @Serializable
    @SerialName("spoiler")
    data class Spoiler(val passcodeHash: String? = null, val text: String = "") : BlockType {
        override val typeName: String = "spoiler"
    }
}

/**
 * An individual content block within an immutable Document.
 */
@Serializable
data class Block(
    val id: String = generateBlockId(),
    val type: BlockType = BlockType.Paragraph,
    val text: String = "",
    val spans: List<Span> = emptyList(),
    val alignment: TextAlignment = TextAlignment.LEFT,
    val indent: Int = 0,
    val checked: Boolean = false,
    val formula: String = "",
    val passcodeHash: String? = null
) {
    val clampedIndent: Int get() = indent.coerceIn(0, 6)

    fun toContentBlockRecord(): ContentBlockRecord {
        val typeString = when (type) {
            is BlockType.Paragraph -> "paragraph"
            is BlockType.Heading -> "heading${type.level}"
            is BlockType.Bullet -> "bullet"
            is BlockType.Numbered -> "numbered"
            is BlockType.Todo -> "todo"
            is BlockType.Quote -> "quote"
            is BlockType.Code -> "code"
            is BlockType.Divider -> "divider"
            is BlockType.Latex -> "latex"
            is BlockType.Spoiler -> "spoiler"
        }
        val contentText = when (type) {
            is BlockType.Latex -> if (formula.isNotEmpty()) formula else text
            is BlockType.Spoiler -> if (type.text.isNotEmpty()) type.text else text
            else -> text
        }
        val isChecked = if (type is BlockType.Todo) type.checked || checked else checked
        val hash = passcodeHash ?: (type as? BlockType.Spoiler)?.passcodeHash

        return ContentBlockRecord(
            type = typeString,
            text = contentText,
            spans = spans.map { it.toRecord() },
            alignment = alignment.value,
            indent = clampedIndent,
            checked = isChecked,
            id = id,
            passcodeHash = hash
        )
    }

    companion object {
        fun fromContentBlockRecord(record: ContentBlockRecord): Block {
            val typeStr = record.type.lowercase().trim()
            val type = when {
                typeStr == "paragraph" -> BlockType.Paragraph
                typeStr == "heading1" -> BlockType.Heading(1)
                typeStr == "heading2" -> BlockType.Heading(2)
                typeStr == "heading3" -> BlockType.Heading(3)
                typeStr.startsWith("heading") -> {
                    val lvl = typeStr.removePrefix("heading").toIntOrNull()?.coerceIn(1, 3) ?: 1
                    BlockType.Heading(lvl)
                }
                typeStr == "bullet" -> BlockType.Bullet
                typeStr == "numbered" -> BlockType.Numbered
                typeStr == "todo" -> BlockType.Todo(record.checked)
                typeStr == "quote" -> BlockType.Quote
                typeStr == "code" -> BlockType.Code
                typeStr == "divider" -> BlockType.Divider
                typeStr == "latex" -> BlockType.Latex(record.text)
                typeStr == "spoiler" -> BlockType.Spoiler(record.passcodeHash, record.text)
                else -> BlockType.Paragraph
            }

            return Block(
                id = record.id ?: generateBlockId(),
                type = type,
                text = record.text,
                spans = record.spans.map { Span.fromRecord(it) },
                alignment = TextAlignment.fromString(record.alignment),
                indent = record.indent.coerceIn(0, 6),
                checked = record.checked,
                formula = if (type is BlockType.Latex) record.text else "",
                passcodeHash = record.passcodeHash
            )
        }
    }
}

/**
 * Normalizes a list of spans against a given text length:
 * - Clamps all offsets within [0, textLength]
 * - Drops empty spans (start >= end)
 * - Merges overlapping or adjacent spans having identical styles
 * - Returns clean, sorted spans
 */
fun normalizeSpans(spans: List<Span>, textLength: Int): List<Span> {
    if (spans.isEmpty() || textLength <= 0) return emptyList()

    // 1. Clamp and filter empty
    val validSpans = spans.mapNotNull { span ->
        val s = span.start.coerceIn(0, textLength)
        val e = span.end.coerceIn(0, textLength)
        if (s < e) span.copy(start = s, end = e) else null
    }

    if (validSpans.isEmpty()) return emptyList()

    // 2. Group by exact style and merge overlapping/adjacent spans
    val grouped = validSpans.groupBy { it.style }
    val mergedResult = mutableListOf<Span>()

    for ((_, styleSpans) in grouped) {
        val sorted = styleSpans.sortedWith(compareBy({ it.start }, { it.end }))
        if (sorted.isEmpty()) continue

        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.start <= current.end) {
                // Overlapping or adjacent: merge
                current = current.copy(end = maxOf(current.end, next.end))
            } else {
                mergedResult.add(current)
                current = next
            }
        }
        mergedResult.add(current)
    }

    // 3. Sort final spans by start, then end
    return mergedResult.sortedWith(compareBy({ it.start }, { it.end }))
}

/**
 * An immutable rich text document comprising a list of blocks.
 */
@Serializable
data class Document(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val blocks: List<Block> = listOf(Block())
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

        fun fromJson(jsonString: String): Document =
            json.decodeFromString(serializer(), jsonString)

        /**
         * Converts plain text into a Document of paragraph blocks.
         */
        fun fromPlainText(text: String): Document {
            if (text.isEmpty()) {
                return Document(blocks = listOf(Block(type = BlockType.Paragraph, text = "")))
            }
            val lines = text.split(Regex("\r?\n"))
            val blocks = lines.map { line ->
                Block(type = BlockType.Paragraph, text = line)
            }
            return Document(blocks = blocks.ifEmpty { listOf(Block()) })
        }

        /**
         * Creates a Document from storage layer ContentBlockRecords.
         */
        fun fromContentBlockRecords(records: List<ContentBlockRecord>): Document {
            if (records.isEmpty()) {
                return Document(blocks = listOf(Block()))
            }
            return Document(blocks = records.map { Block.fromContentBlockRecord(it) })
        }
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    fun toContentBlockRecords(): List<ContentBlockRecord> = blocks.map { it.toContentBlockRecord() }

    /**
     * Generates a single-line plain preview of the document contents up to [maxLength].
     */
    fun plainPreview(maxLength: Int = 120): String {
        val sb = StringBuilder()
        for (b in blocks) {
            if (b.type is BlockType.Divider) continue
            val t = when (b.type) {
                is BlockType.Latex -> b.formula.ifEmpty { b.text }
                is BlockType.Spoiler -> b.text
                else -> b.text
            }
            if (t.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(t.trim())
            }
            if (sb.length >= maxLength) break
        }
        val full = sb.toString()
        return if (full.length > maxLength) full.take(maxLength).trimEnd() + "…" else full
    }

    /**
     * Finds a block by ID.
     */
    fun findBlock(blockId: String): Block? = blocks.find { it.id == blockId }

    /**
     * Replaces a block by ID using a transform function.
     */
    fun updateBlock(blockId: String, transform: (Block) -> Block): Document {
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index == -1) return this
        val newBlocks = blocks.toMutableList()
        newBlocks[index] = transform(newBlocks[index])
        return copy(blocks = newBlocks)
    }

    /**
     * Applies a style across [start, end) UTF-16 offset range in the specified block.
     * Safely clamps invalid ranges. Automatically merges overlapping or adjacent spans.
     */
    fun applyStyle(blockId: String, start: Int, end: Int, style: SpanStyle): Document {
        val block = findBlock(blockId) ?: return this
        val textLen = block.text.length
        val s = minOf(start, end).coerceIn(0, textLen)
        val e = maxOf(start, end).coerceIn(0, textLen)
        if (s >= e) return this

        val newSpan = Span(s, e, style)
        val updatedSpans = normalizeSpans(block.spans + newSpan, textLen)
        return updateBlock(blockId) { it.copy(spans = updatedSpans) }
    }

    /**
     * Removes a style across [start, end) UTF-16 offset range in the specified block.
     * Safely clamps invalid ranges. Handles partial overlaps and span splits.
     */
    fun removeStyle(
        blockId: String,
        start: Int,
        end: Int,
        predicate: (SpanStyle) -> Boolean
    ): Document {
        val block = findBlock(blockId) ?: return this
        val textLen = block.text.length
        val s = minOf(start, end).coerceIn(0, textLen)
        val e = maxOf(start, end).coerceIn(0, textLen)
        if (s >= e) return this

        val resultSpans = mutableListOf<Span>()
        for (sp in block.spans) {
            if (!predicate(sp.style)) {
                resultSpans.add(sp)
                continue
            }
            // Matching style: check overlap with [s, e)
            if (sp.end <= s || sp.start >= e) {
                // Completely outside
                resultSpans.add(sp)
            } else if (sp.start >= s && sp.end <= e) {
                // Completely inside removal range: drop
            } else if (sp.start < s && sp.end > e) {
                // Removal range is strictly inside the span: split into two spans
                resultSpans.add(sp.copy(start = sp.start, end = s))
                resultSpans.add(sp.copy(start = e, end = sp.end))
            } else if (sp.start < s && sp.end > s) {
                // Overlaps start of removal range: shrink right edge
                resultSpans.add(sp.copy(start = sp.start, end = s))
            } else if (sp.start < e && sp.end > e) {
                // Overlaps end of removal range: shrink left edge
                resultSpans.add(sp.copy(start = e, end = sp.end))
            }
        }

        val updatedSpans = normalizeSpans(resultSpans, textLen)
        return updateBlock(blockId) { it.copy(spans = updatedSpans) }
    }

    fun removeStyle(blockId: String, start: Int, end: Int, style: SpanStyle): Document =
        removeStyle(blockId, start, end) { it == style }

    /**
     * Toggles a style across [start, end).
     * If the entire range already has the style, it is removed; otherwise it is applied.
     */
    fun toggleStyle(blockId: String, start: Int, end: Int, style: SpanStyle): Document {
        val block = findBlock(blockId) ?: return this
        val textLen = block.text.length
        val s = minOf(start, end).coerceIn(0, textLen)
        val e = maxOf(start, end).coerceIn(0, textLen)
        if (s >= e) return this

        // Check if the range [s, e) is fully covered by this style
        val styleSpans = normalizeSpans(block.spans.filter { it.style == style }, textLen)
        val isFullyCovered = styleSpans.any { it.start <= s && it.end >= e }

        return if (isFullyCovered) {
            removeStyle(blockId, s, e, style)
        } else {
            applyStyle(blockId, s, e, style)
        }
    }

    /**
     * Inserts text at the given UTF-16 offset.
     * Safely clamps offset. Shifts subsequent spans and expands spans containing the insertion point.
     */
    fun insertText(blockId: String, offset: Int, textToInsert: String): Document {
        val block = findBlock(blockId) ?: return this
        if (textToInsert.isEmpty()) return this

        val pos = offset.coerceIn(0, block.text.length)
        val delta = textToInsert.length
        val newText = block.text.substring(0, pos) + textToInsert + block.text.substring(pos)

        val shiftedSpans = block.spans.mapNotNull { sp ->
            when {
                sp.end <= pos -> sp
                sp.start >= pos -> sp.copy(start = sp.start + delta, end = sp.end + delta)
                else -> sp.copy(end = sp.end + delta)
            }
        }

        val updatedSpans = normalizeSpans(shiftedSpans, newText.length)
        return updateBlock(blockId) { it.copy(text = newText, spans = updatedSpans) }
    }

    /**
     * Deletes a range of text [start, end).
     * Safely clamps range. Shifts subsequent spans and shrinks overlapping spans.
     */
    fun deleteRange(blockId: String, start: Int, end: Int): Document {
        val block = findBlock(blockId) ?: return this
        val textLen = block.text.length
        val s = minOf(start, end).coerceIn(0, textLen)
        val e = maxOf(start, end).coerceIn(0, textLen)
        if (s >= e) return this

        val deletedLen = e - s
        val newText = block.text.substring(0, s) + block.text.substring(e)

        val shiftedSpans = block.spans.mapNotNull { sp ->
            when {
                // Strictly before: untouched
                sp.end <= s -> sp
                // Strictly after: shift left
                sp.start >= e -> sp.copy(start = sp.start - deletedLen, end = sp.end - deletedLen)
                // Completely inside deleted range: drop
                sp.start >= s && sp.end <= e -> null
                // Encompasses entire deleted range: shrink
                sp.start < s && sp.end > e -> sp.copy(end = sp.end - deletedLen)
                // Starts before, ends inside: shrink to s
                sp.start < s && sp.end > s -> sp.copy(end = s)
                // Starts inside, ends after: starts at s, end shifted
                sp.start < e && sp.end > e -> sp.copy(start = s, end = sp.end - deletedLen)
                else -> null
            }
        }

        val updatedSpans = normalizeSpans(shiftedSpans, newText.length)
        return updateBlock(blockId) { it.copy(text = newText, spans = updatedSpans) }
    }

    /**
     * Splits a block at the given offset into two blocks.
     * Original block keeps content before offset; new block receives content after offset.
     * Spans crossing the split boundary are partitioned between the two blocks.
     */
    fun splitBlock(
        blockId: String,
        offset: Int,
        newBlockId: String = generateBlockId()
    ): Document {
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index == -1) return this
        val b = blocks[index]
        val pos = offset.coerceIn(0, b.text.length)

        val text1 = b.text.substring(0, pos)
        val text2 = b.text.substring(pos)

        val spans1 = mutableListOf<Span>()
        val spans2 = mutableListOf<Span>()

        for (sp in b.spans) {
            when {
                sp.end <= pos -> spans1.add(sp)
                sp.start >= pos -> spans2.add(sp.copy(start = sp.start - pos, end = sp.end - pos))
                else -> {
                    // Crossing split point: partition
                    spans1.add(sp.copy(end = pos))
                    spans2.add(sp.copy(start = 0, end = sp.end - pos))
                }
            }
        }

        val nextType = when (b.type) {
            is BlockType.Heading -> BlockType.Paragraph
            is BlockType.Todo -> BlockType.Todo(checked = false)
            else -> b.type
        }

        val block1 = b.copy(text = text1, spans = normalizeSpans(spans1, text1.length))
        val block2 = Block(
            id = newBlockId,
            type = nextType,
            text = text2,
            spans = normalizeSpans(spans2, text2.length),
            alignment = b.alignment,
            indent = b.indent,
            checked = false,
            formula = if (nextType is BlockType.Latex) text2 else "",
            passcodeHash = b.passcodeHash
        )

        val newBlocks = blocks.toMutableList()
        newBlocks[index] = block1
        newBlocks.add(index + 1, block2)
        return copy(blocks = newBlocks)
    }

    /**
     * Merges [secondBlockId] into [firstBlockId].
     * The second block is deleted and its text/spans are appended to the first block.
     */
    fun mergeBlocks(firstBlockId: String, secondBlockId: String): Document {
        if (firstBlockId == secondBlockId) return this
        val idx1 = blocks.indexOfFirst { it.id == firstBlockId }
        val idx2 = blocks.indexOfFirst { it.id == secondBlockId }
        if (idx1 == -1 || idx2 == -1) return this

        val b1 = blocks[idx1]
        val b2 = blocks[idx2]

        val offset = b1.text.length
        val mergedText = b1.text + b2.text
        val shiftedSpans2 = b2.spans.map { it.copy(start = it.start + offset, end = it.end + offset) }
        val mergedSpans = normalizeSpans(b1.spans + shiftedSpans2, mergedText.length)

        val mergedBlock = b1.copy(text = mergedText, spans = mergedSpans)

        val newBlocks = blocks.toMutableList()
        newBlocks[idx1] = mergedBlock
        newBlocks.removeAt(idx2)

        return copy(blocks = newBlocks.ifEmpty { listOf(Block()) })
    }

    /**
     * Changes the block type while preserving text, spans, and alignments.
     */
    fun changeBlockType(blockId: String, newType: BlockType): Document {
        return updateBlock(blockId) { b ->
            when (newType) {
                is BlockType.Divider -> b.copy(
                    type = BlockType.Divider,
                    text = "",
                    spans = emptyList()
                )
                is BlockType.Todo -> b.copy(
                    type = newType,
                    checked = newType.checked
                )
                is BlockType.Latex -> b.copy(
                    type = newType,
                    formula = newType.formula.ifEmpty { b.text }
                )
                is BlockType.Spoiler -> b.copy(
                    type = newType,
                    passcodeHash = newType.passcodeHash ?: b.passcodeHash
                )
                else -> b.copy(type = newType)
            }
        }
    }
}
