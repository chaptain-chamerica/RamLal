package com.likkho.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VaultMode { PRIMARY, DECOY }
enum class Route { HOME, WORKSPACE, SETTINGS, BACKUP }

/** Same shape as React `CanvasDraggableImage`. `source` = URI/path (Studio AI decides). Empty => UI placeholder. */
data class CanvasImage(
    val id: String,
    val source: String = "",
    val x: Float = 24f,
    val y: Float = 120f,
    val width: Float = 160f,
    val height: Float = 120f,
    val opacity: Float = 1f,
    val rotation: Float = 0f,
    val behindText: Boolean = false,
    val sizeBytes: Long = 0,
)

/** Same shape as React `CanvasAudioAttachment`. */
data class CanvasAudio(
    val id: String,
    val name: String,
    val format: String = "wav",
    val audioUri: String = "",
    val durationSec: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val x: Float = 24f,
    val y: Float = 120f,
    val width: Float = 270f,
    val height: Float = 56f,
    val rotation: Float = 0f,
    val playbackRate: Float = 1f,
    val behindText: Boolean = false,
)

data class CanvasVideo(
    val id: String,
    val name: String,
    val source: String = "",
    val durationSec: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val x: Float = 24f,
    val y: Float = 140f,
    val width: Float = 220f,
    val height: Float = 150f,
    val rotation: Float = 0f,
    val behindText: Boolean = false,
    val sizeBytes: Long = 0,
)

/** Header PFP: either picture (pfpSource) or Emoji/Text on colored background. null => App icon. */
data class Pfp(
    val source: String? = null,
    val text: String? = null,
    val bgColor: Long = 0xFF3366CC,
    val textColor: Long = 0xFFFFFFFF,
    val textScale: Float = 1f,
)

/** Same fields as React `DiaryLog`. contentHtml: Studio AI can store real rich text here. */
data class DiaryLog(
    val id: String,
    val heading: String,
    val contentHtml: String = "",
    val plainPreview: String = "",
    val pfp: Pfp? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val reminderAt: Long? = null,
    val pinned: Boolean = false,
    val diaryLockPin: String? = null,
    val canvasBgSource: String? = null,
    val canvasBgOpacity: Float = 0.35f,
    val canvasImages: List<CanvasImage> = emptyList(),
    val audioAttachments: List<CanvasAudio> = emptyList(),
    val videoAttachments: List<CanvasVideo> = emptyList(),
    val isContentLoaded: Boolean = true,
) {
    val dateStamp: String get() = fmtDate(createdAt)
    val timeStamp: String get() = fmtTime(createdAt)
    val updatedDateStamp: String get() = fmtDate(updatedAt)
    val updatedTimeStamp: String get() = fmtTime(updatedAt)

    fun toSummary(): DiaryLog = if (!isContentLoaded && contentHtml.isEmpty() && canvasImages.isEmpty() && audioAttachments.isEmpty() && videoAttachments.isEmpty()) {
        this
    } else {
        copy(
            contentHtml = "",
            canvasBgSource = null,
            canvasImages = emptyList(),
            audioAttachments = emptyList(),
            videoAttachments = emptyList(),
            isContentLoaded = false
        )
    }
}

data class MicSettings(val format: String = "wav", val sampleRate: Int = 44100, val bitRate: Int = 128000)

data class SavedPassword(val id: String, val name: String, val password: String, val createdAt: Long)

data class CustomFont(val id: String, val name: String, val lang: String, val filePath: String = "")

data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val releasePageUrl: String,
    val publishedAt: String?,
)

// ---------- Word analysis (6 categories, same fields as React `WordAnalysisRecord`) ----------
data class WordDefinition(val partOfSpeech: String, val definition: String, val example: String? = null)
data class WordAnalysisRecord(
    val word: String,
    val analyzedAt: Long = System.currentTimeMillis(),
    // 1 Phonetics
    val phoneticTranscription: String = "",
    val syllableMetrics: String = "",
    val perfectRhymes: List<String> = emptyList(),
    val nearSlantRhymes: List<String> = emptyList(),
    val meterAndRhythmScore: String = "",
    // 2 Lexical
    val definitions: List<WordDefinition> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val hypernyms: List<String> = emptyList(),
    val hyponyms: List<String> = emptyList(),
    val collocations: List<String> = emptyList(),
    // 3 Morphology
    val partOfSpeech: List<String> = emptyList(),
    val lemmatizationAndRoots: String = "",
    val affixBreakdown: String = "",
    val etymologyAndOrigin: String = "",
    val grammaticalDependency: String = "",
    // 4 Pragmatics
    val contextualSentimentPolarity: String = "",
    val emotionalValence: String = "",
    val formalityAndRegister: String = "",
    val connotationAndTone: String = "",
    // 5 Knowledge graph
    val namedEntityRecognition: String = "",
    val entityDisambiguation: List<String> = emptyList(),
    val externalKnowledgeLinks: List<Pair<String, String>> = emptyList(),
    // 6 Style / metrics
    val styleAndGrammarNotes: List<Pair<String, String>> = emptyList(),
)

data class WikiSuggestion(val title: String, val description: String)

// ---------- Export ----------
data class ExportFormat(val ext: String, val label: String, val desc: String)

val EXPORT_FORMATS = listOf(
    ExportFormat("txt", ".txt", "Plain Text Document"),
    ExportFormat("md", ".md", "Markdown Document"),
    ExportFormat("pdf", ".pdf", "PDF Printable Document (Full Canvas)"),
    ExportFormat("rtf", ".rtf", "Rich Text Format"),
    ExportFormat("html", ".html", "Web Page Archive"),
    ExportFormat("docx", ".docx", "Microsoft Word Document (Full Canvas)"),
    ExportFormat("json", ".json", "Structured JSON Data"),
    ExportFormat("csv", ".csv", "Comma-Separated Values"),
    ExportFormat("tsv", ".tsv", "Tab-Separated Values"),
    ExportFormat("xml", ".xml", "Extensible Markup Language"),
)

val AUDIO_FORMATS = listOf("wav", "flac", "m4a", "aac", "mp3", "ogg", "webm")
val SAMPLE_RATES = listOf(8000 to "8,000 Hz", 16000 to "16,000 Hz", 22050 to "22,050 Hz", 44100 to "44,100 Hz", 48000 to "48,000 Hz")
val BIT_RATES = listOf(64000 to "64 kbps", 128000 to "128 kbps", 192000 to "192 kbps", 256000 to "256 kbps", 320000 to "320 kbps")

// ---------- Rich-text toolbar actions (Studio AI implements the real formatting) ----------
enum class FormatAction {
    BOLD, ITALIC, UNDERLINE, STRIKE, H1, H2, H3, BULLET, NUMBERED, TODO, QUOTE, CODE, DIVIDER,
    ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT, JUSTIFY, INDENT, OUTDENT, SUPERSCRIPT, SUBSCRIPT, TIMESTAMP, UNDO, REDO,
}

val TEXT_COLORS = listOf(
    "#202122", "#54595d", "#3366cc", "#1d4ed8", "#0ea5e9", "#0d9488", "#14866d", "#15803d", "#65a30d", "#ca8a04",
    "#ac6600", "#ea580c", "#b32424", "#e11d48", "#db2777", "#9333ea", "#6b4ba1", "#4f46e5", "#78350f", "default",
)
val HIGHLIGHT_COLORS = listOf(
    "#fef08a", "#fde047", "#fed7aa", "#fecaca", "#fbcfe8", "#e9d5ff", "#ddd6fe", "#c7d2fe", "#bfdbfe", "#bae6fd",
    "#a5f3fc", "#99f6e4", "#bbf7d0", "#d9f99d", "#fef6e7", "#eaf3ff", "#fee7e6", "#d5fdf4", "#f3e5f5", "transparent",
)
val PFP_BG_PRESETS = listOf(
    Triple("Wikipedia Blue", 0xFF3366CCL, 0xFFFFFFFFL), Triple("Obsidian Slate", 0xFF202122L, 0xFFFFFFFFL),
    Triple("Crimson Ink", 0xFFB32424L, 0xFFFFFFFFL), Triple("Emerald Pine", 0xFF14866DL, 0xFFFFFFFFL),
    Triple("Royal Violet", 0xFF6B4BA1L, 0xFFFFFFFFL), Triple("Warm Amber", 0xFFAC6600L, 0xFFFFFFFFL),
    Triple("Deep Indigo", 0xFF3730A3L, 0xFFFFFFFFL), Triple("Rose Berry", 0xFFBE185DL, 0xFFFFFFFFL),
    Triple("Teal Ocean", 0xFF0F766EL, 0xFFFFFFFFL), Triple("Forest Moss", 0xFF166534L, 0xFFFFFFFFL),
    Triple("Terracotta", 0xFFC2410CL, 0xFFFFFFFFL), Triple("Parchment Cream", 0xFFFEF6E7L, 0xFF202122L),
    Triple("Soft Lavender", 0xFFEDE9FEL, 0xFF3730A3L), Triple("Mint Mist", 0xFFD1FAE5L, 0xFF065F46L),
    Triple("Sky Pastel", 0xFFDBEAFEL, 0xFF1E40AFL), Triple("Blush Petal", 0xFFFCE7F3L, 0xFF9D174DL),
)
val QUICK_EMOJIS = listOf(
    "📔", "✨", "🔒", "🌙", "☕", "🌿", "🪶", "🔥", "💡", "🎯", "🎧", "🎨",
    "🧠", "🚀", "🌸", "🌊", "🏔️", "🕯️", "📜", "💎", "🦋", "⚡", "🌈", "❤️",
)
val IMAGE_FILTERS = listOf(
    "Original", "Grayscale (Monochrome)", "Sepia", "Invert (Negative)", "Blur (Gaussian/Motion)", "Sharpen",
    "Edge Detection (Sobel)", "Thresholding (Binarization)", "High-Pass Filter", "Low-Pass Filter", "Vignette",
    "Warmth / Coolness", "Hue Rotate", "Vintage / Retro", "Noir", "HDR (High Dynamic Range)", "Color Balance",
    "Posterize", "Emboss", "Pixelate", "Distortion (Fish-eye/Warp)", "Noise Reduction (Denoise)",
)
val AUDIO_SPEEDS = listOf(1f, 1.25f, 1.5f, 2f)

// ---------- LaTeX palette (6 categories, first symbols; Studio AI can extend from latexMathEngine.ts) ----------
data class MathSymbol(val label: String, val display: String, val latex: String)
data class MathCategory(val id: String, val title: String, val symbols: List<MathSymbol>)

// ---------- Helpers ----------
private val IN = Locale("en", "IN")
fun fmtDate(ms: Long): String = SimpleDateFormat("dd MMM yyyy", IN).format(Date(ms))
fun fmtTime(ms: Long): String = SimpleDateFormat("hh:mm a", IN).format(Date(ms))
fun fmtDateTimeShort(ms: Long): String = SimpleDateFormat("dd MMM, hh:mm a", IN).format(Date(ms))

fun formatBytes(b: Long): String = when {
    b <= 0 -> "—"
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", b / 1024.0 / 1024.0)
}
