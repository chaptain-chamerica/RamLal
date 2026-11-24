package com.likkho.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun WikiSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    val w = wiki()
    Slider(value, onChange, modifier, valueRange = range, colors = SliderDefaults.colors(thumbColor = w.accent, activeTrackColor = w.accent, inactiveTrackColor = w.hairline))
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val w = wiki()
    Box(modifier.height(34.dp).background(if (selected) w.accent else w.surface).wikiBorder(if (selected) w.accent else w.border).clickable(onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = sansStyle(11.sp, if (selected) Color.White else w.text, FontWeight.SemiBold), maxLines = 1)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, suffix: String = "", onChange: (Float) -> Unit) {
    val w = wiki()
    Column {
        Row { Text(label, style = sansStyle(11.sp, w.text, FontWeight.Medium), modifier = Modifier.weight(1f)); Text("${value.toInt()}$suffix", style = monoStyle(11.sp, w.muted)) }
        WikiSlider(value, onChange, range)
    }
}

/** Image Studio (MediaImageStudioModal): tabs Compress | Filters (22) | Adjust & Opacity | Crop. Footer Cancel / Apply. */
@Composable
fun ImageStudioDialog(
    title: String,
    sizeBytes: Long,
    onClose: () -> Unit,
    previewBitmap: ImageBitmap? = null,
    initialOpacity: Float = 1f,
    onApplyOpacity: ((Float) -> Unit)? = null,
    onApply: () -> Unit,
) {
    val w = wiki()
    var tab by remember { mutableStateOf(0) }
    var compress by remember { mutableStateOf(false) }
    var fmt by remember { mutableStateOf("WebP") }
    var quality by remember { mutableStateOf(80f) }
    var depth by remember { mutableStateOf("24-bit RGB") }
    var space by remember { mutableStateOf("sRGB") }
    var exif by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }
    var bright by remember { mutableStateOf(100f) }; var contrast by remember { mutableStateOf(100f) }; var sat by remember { mutableStateOf(100f) }
    var warm by remember { mutableStateOf(0f) }; var hue by remember { mutableStateOf(0f) }; var opacity by remember(initialOpacity) { mutableStateOf((initialOpacity * 100f).coerceIn(5f, 100f)) }
    var aspect by remember { mutableStateOf("Free") }; var zoom by remember { mutableStateOf(100f) }
    WikiDialog(onDismiss = onClose, title = title, titleIcon = Icons.Outlined.Image, maxWidth = 440.dp, subtitle = "Size: ${formatBytes(sizeBytes)}") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().height(150.dp).background(w.surface).wikiBorder(w.border), contentAlignment = Alignment.Center) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        alpha = (opacity / 100f).coerceIn(0.05f, 1f),
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                } else {
                    Text("No image preview available", style = sansStyle(11.sp, w.muted, FontWeight.Normal))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Compress", "Filters (22+)", "Adjust", "Crop").forEachIndexed { i, t -> Chip(t, tab == i, Modifier.weight(1f)) { tab = i } }
            }
            when (tab) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth().background(w.surface).wikiBorder(w.border).clickable { compress = !compress }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Compress Image (Optional)", style = sansStyle(12.sp, w.text, FontWeight.SemiBold), modifier = Modifier.weight(1f)); WikiSwitch(compress)
                    }
                    if (compress) {
                        FieldLabel("Compression Format")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("WebP", "JPEG", "PNG (Lossless)").forEach { Chip(it, fmt == it, Modifier.weight(1f)) { fmt = it } } }
                        LabeledSlider("Quality", quality, 1f..100f, "%") { quality = it }
                        FieldLabel("Bit Depth")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("24-bit RGB", "16-bit Hi", "8-bit 256c", "1-bit Mono").forEach { Chip(it, depth == it, Modifier.weight(1f)) { depth = it } } }
                        FieldLabel("Color Space")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("sRGB", "Display P3", "Grayscale").forEach { Chip(it, space == it, Modifier.weight(1f)) { space = it } } }
                        Row(Modifier.fillMaxWidth().clickable { exif = !exif }, verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep EXIF Metadata", style = sansStyle(12.sp, w.text, FontWeight.Medium), modifier = Modifier.weight(1f)); WikiSwitch(exif)
                        }
                    }
                }
                1 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    IMAGE_FILTERS.chunked(2).forEachIndexed { r, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEachIndexed { c, f -> Chip(f, filter == r * 2 + c, Modifier.weight(1f)) { filter = r * 2 + c } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                2 -> Column {
                    LabeledSlider("Brightness", bright, 0f..200f, "%") { bright = it }
                    LabeledSlider("Contrast", contrast, 0f..200f, "%") { contrast = it }
                    LabeledSlider("Saturation (Vibrance)", sat, 0f..200f, "%") { sat = it }
                    LabeledSlider("Warmth / Coolness (Temperature)", warm, -100f..100f) { warm = it }
                    LabeledSlider("Hue Rotate", hue, 0f..360f, "°") { hue = it }
                    LabeledSlider("Opacity", opacity, 5f..100f, "%") { opacity = it }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("Crop Aspect Ratio")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Free", "1:1", "4:3", "16:9").forEach { Chip(it, aspect == it, Modifier.weight(1f)) { aspect = it } } }
                    LabeledSlider("Zoom", zoom, 100f..400f, "%") { zoom = it }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                WikiButton("Cancel", onClose, height = 36.dp)
                WikiButton("Apply", { onApplyOpacity?.invoke((opacity / 100f).coerceIn(0.05f, 1f)); onApply(); onClose() }, kind = BtnKind.PRIMARY, icon = Icons.Outlined.Check, height = 36.dp)
            }
        }
    }
}

/** Audio Studio (MediaAudioStudioModal): codec, bitrate, channels, sampling rate. */
@Composable
fun AudioStudioDialog(name: String, onClose: () -> Unit, onApply: () -> Unit) {
    val w = wiki()
    var codec by remember { mutableStateOf("wav") }; var bitrate by remember { mutableStateOf(128000) }
    var stereo by remember { mutableStateOf(true) }; var rate by remember { mutableStateOf(44100) }
    val codecs = listOf("wav" to "WAV (PCM) · Uncompressed Lossless", "mp3" to "MP3 · MPEG-1 Audio Layer III", "aac" to "AAC · Advanced Audio Coding", "m4a" to "M4A · MPEG-4 Audio", "flac" to "FLAC · Free Lossless", "ogg" to "OGG (Opus)", "webm" to "WebM Audio")
    WikiDialog(onDismiss = onClose, title = "Audio Studio", titleIcon = Icons.Outlined.GraphicEq, subtitle = name) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().background(w.surface).wikiBorder(w.border).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PlayArrow, null, tint = w.accent); Spacer(Modifier.width(8.dp)); Text("Preview (TODO Studio AI)", style = sansStyle(12.sp, w.muted, FontWeight.Normal))
            }
            FieldLabel("1. Audio Codec (Format)")
            codecs.forEach { (id, label) -> Chip(label, codec == id, Modifier.fillMaxWidth()) { codec = id } }
            FieldLabel("2. Bitrate"); WikiSelect(BIT_RATES, bitrate) { bitrate = it }
            FieldLabel("3. Channels")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip("Mono", !stereo, Modifier.weight(1f)) { stereo = false }; Chip("Stereo", stereo, Modifier.weight(1f)) { stereo = true } }
            FieldLabel("4. Sampling Rate"); WikiSelect(SAMPLE_RATES, rate) { rate = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                WikiButton("Cancel", onClose, height = 36.dp); WikiButton("Apply", { onApply(); onClose() }, kind = BtnKind.PRIMARY, height = 36.dp)
            }
        }
    }
}

/** PFP Studio (ImageCropperModal): tab "Emoji / Text & BG" | "Photo / Image". Footer: Remove | Save PFP. */
@Composable
fun PfpStudioDialog(
    current: Pfp?,
    hooks: FeatureHooks,
    onClose: () -> Unit,
    onImportPhotoUri: (suspend (Uri) -> String?)? = null,
    onSave: (Pfp?) -> Unit,
) {
    val w = wiki(); val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(if (current?.source != null) 1 else 0) }
    var text by remember { mutableStateOf(current?.text.orEmpty()) }
    var bg by remember { mutableStateOf(current?.bgColor ?: 0xFF3366CCL) }
    var fg by remember { mutableStateOf(current?.textColor ?: 0xFFFFFFFFL) }
    var scale by remember { mutableStateOf(current?.textScale ?: 1f) }
    var photo by remember { mutableStateOf(current?.source) }
    var zoom by remember { mutableStateOf(100f) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                importPickedPfpPhoto(uri, onImportPhotoUri)?.let { photo = it }
            }
        }
    }
    WikiDialog(onDismiss = onClose, title = "Diary PFP", titleIcon = Icons.Outlined.AddPhotoAlternate) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Chip("Emoji / Text & BG", tab == 0, Modifier.weight(1f)) { tab = 0 }; Chip("Photo / Image", tab == 1, Modifier.weight(1f)) { tab = 1 } }
            Box(Modifier.align(Alignment.CenterHorizontally)) { PfpAvatar(if (tab == 0) Pfp(null, text.ifBlank { "LK" }, bg, fg, scale) else Pfp(photo ?: "", null, bg, fg), 120.dp) }
            if (tab == 0) {
                WikiTextField(text, { text = it.take(4) }, Modifier.fillMaxWidth(), "Type emoji or text (e.g. 📔, LK, अ)...")
                QUICK_EMOJIS.chunked(8).forEach { r -> Row(Modifier.fillMaxWidth()) { r.forEach { e -> Box(Modifier.weight(1f).height(36.dp).clickable { text = e }, contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) } } } }
                LabeledSlider("Size", scale * 100, 50f..150f, "%") { scale = it / 100 }
                FieldLabel("Background Color")
                PFP_BG_PRESETS.chunked(8).forEach { r -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { r.forEach { (n, b, t) -> Box(Modifier.weight(1f).height(30.dp).background(Color(b)).wikiBorder(if (bg == b) w.accent else w.border, if (bg == b) 2.dp else 1.dp).clickable { bg = b; fg = t }) } } }
            } else {
                LabeledSlider("Zoom", zoom, 100f..400f, "%") { zoom = it }
                WikiButton(
                    if (photo == null) "Browse Image" else "Choose Another Photo",
                    {
                        if (onImportPhotoUri != null) {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    },
                    Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.Image
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WikiButton("Remove", { onSave(null); onClose() }, kind = BtnKind.OUTLINE, icon = Icons.Outlined.Delete, height = 36.dp)
                Spacer(Modifier.weight(1f))
                WikiButton("Save PFP", { onSave(if (tab == 0) Pfp(null, text.ifBlank { "LK" }, bg, fg, scale) else Pfp(photo, null, bg, fg)); onClose() }, kind = BtnKind.PRIMARY, height = 36.dp)
            }
        }
    }
}

internal suspend fun <T> importPickedPfpPhoto(
    uri: T?,
    onImportPhotoUri: (suspend (T) -> String?)?
): String? = if (uri != null) onImportPhotoUri?.invoke(uri) else null

/** Word analysis result: 6 categories (WordAnalysisModal). */
@Composable
fun WordAnalysisDialog(r: WordAnalysisRecord, onClose: () -> Unit) {
    val w = wiki()
    @Composable fun H(t: String) = Text(t, style = serifStyle(15.sp, w.accent), modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    @Composable fun L(k: String, v: String) { if (v.isNotBlank()) Row { Text("$k: ", style = sansStyle(12.sp, w.muted, FontWeight.SemiBold)); Text(v, style = sansStyle(12.sp, w.text, FontWeight.Normal)) } }
    WikiDialog(onDismiss = onClose, title = r.word, titleIcon = Icons.Outlined.AutoStories, maxWidth = 448.dp) {
        Column(Modifier.padding(16.dp)) {
            H("1. Phonetics, Sound & Prosody"); L("Transcription", r.phoneticTranscription); L("Syllables", r.syllableMetrics); L("Perfect rhymes", r.perfectRhymes.joinToString()); L("Near/slant rhymes", r.nearSlantRhymes.joinToString()); L("Meter", r.meterAndRhythmScore)
            H("2. Lexical & Semantics"); Text("Definitions", style = sansStyle(12.sp, w.text, FontWeight.Bold))
            r.definitions.forEach { Text("(${it.partOfSpeech}) ${it.definition}", style = sansStyle(12.sp, w.text, FontWeight.Normal)) }
            L("Synonyms", r.synonyms.joinToString()); L("Antonyms", r.antonyms.joinToString()); L("Hypernyms", r.hypernyms.joinToString()); L("Hyponyms", r.hyponyms.joinToString()); L("Collocations", r.collocations.joinToString())
            H("3. Morphology, Syntax & Etymology"); L("Part of speech", r.partOfSpeech.joinToString()); L("Roots", r.lemmatizationAndRoots); L("Affixes", r.affixBreakdown); L("Etymology", r.etymologyAndOrigin); L("Dependency", r.grammaticalDependency)
            H("4. Pragmatics, Vibe & Sentiment"); L("Sentiment", r.contextualSentimentPolarity); L("Valence", r.emotionalValence); L("Register", r.formalityAndRegister); L("Tone", r.connotationAndTone)
            H("5. Knowledge Graph & Entities"); L("NER", r.namedEntityRecognition); L("Disambiguation", r.entityDisambiguation.joinToString())
            r.externalKnowledgeLinks.forEach { Text(it.first, style = sansStyle(12.sp, w.link, FontWeight.Medium)) }
            H("6. Style, Grammar & Quantitative Metrics"); r.styleAndGrammarNotes.forEach { L(it.first, it.second) }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) { WikiButton("Close", onClose, height = 36.dp) }
        }
    }
}
