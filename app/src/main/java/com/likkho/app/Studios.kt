package com.likkho.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun WikiSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    val w = wiki()
    Slider(value, onChange, modifier, valueRange = range, colors = SliderDefaults.colors(thumbColor = w.accent, activeTrackColor = w.accent, inactiveTrackColor = w.hairline))
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val w = wiki()
    Box(modifier.height(34.dp).background(if (selected) w.accent else w.surface).wikiBorder(if (selected) w.accent else w.border).clickable(onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
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

/** Image Studio (MediaImageStudioModal): tabs Compress | Filters (22+) | Adjust & Opacity | Crop & Transform. */
@Composable
fun ImageStudioDialog(
    title: String,
    sizeBytes: Long,
    onClose: () -> Unit,
    previewBitmap: ImageBitmap? = null,
    loadSourceBitmap: (suspend () -> Bitmap?)? = null,
    initialOpacity: Float = 1f,
    onApplyOpacity: ((Float) -> Unit)? = null,
    onApplyEdited: (suspend (EncodedImageResult, ImageBitmap?) -> Unit)? = null,
    onApply: () -> Unit = {},
) {
    val w = wiki()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var params by remember(initialOpacity) {
        mutableStateOf(ImageEditParams(opacity = initialOpacity.coerceIn(0.05f, 1f)))
    }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderedPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var estimatedBytes by remember { mutableStateOf<Long?>(null) }
    var outputDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var isApplying by remember { mutableStateOf(false) }

    LaunchedEffect(loadSourceBitmap) {
        val loaded = loadSourceBitmap?.invoke()
        if (loaded != null && !loaded.isRecycled) {
            val previewCopy = withContext(Dispatchers.Default) {
                val maxDim = 540
                if (loaded.width <= maxDim && loaded.height <= maxDim) {
                    loaded.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    val scale = min(maxDim.toFloat() / loaded.width, maxDim.toFloat() / loaded.height)
                    val nw = (loaded.width * scale).roundToInt().coerceAtLeast(1)
                    val nh = (loaded.height * scale).roundToInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(loaded, nw, nh, true)
                }
            }
            sourceBitmap = loaded
            previewBaseBitmap = previewCopy
            outputDims = loaded.width to loaded.height
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sourceBitmap?.let { if (!it.isRecycled) it.recycle() }
            previewBaseBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    LaunchedEffect(params, previewBaseBitmap) {
        val base = previewBaseBitmap ?: return@LaunchedEffect
        if (base.isRecycled) return@LaunchedEffect
        delay(55L)
        val processed = withContext(Dispatchers.Default) {
            if (base.isRecycled) null
            else runCatching { ImageEditorEngine.processBitmapForPreview(base, params) }.getOrNull()
        }
        if (processed != null) {
            renderedPreview = processed.asImageBitmap()
        }
    }

    LaunchedEffect(params, sourceBitmap) {
        val src = sourceBitmap ?: return@LaunchedEffect
        if (src.isRecycled) return@LaunchedEffect
        if (!params.hasPixelOrEncodingChanges()) {
            estimatedBytes = null
            outputDims = src.width to src.height
            return@LaunchedEffect
        }
        delay(160L)
        val snapshot = withContext(Dispatchers.Default) {
            if (src.isRecycled) null
            else runCatching {
                val processed = ImageEditorEngine.processBitmapForExport(src, params)
                try {
                    val encoded = ImageEditorEngine.encodeBitmap(processed, params)
                    Triple(encoded.bytes.size.toLong(), encoded.width, encoded.height)
                } finally {
                    if (!processed.isRecycled) processed.recycle()
                }
            }.getOrNull()
        }
        if (snapshot != null) {
            estimatedBytes = snapshot.first
            outputDims = snapshot.second to snapshot.third
        }
    }

    val isLosslessPng = params.format.startsWith("PNG")
    val hasChanges = params.hasPixelOrEncodingChanges() || kotlin.math.abs(params.opacity - initialOpacity) > 0.005f

    WikiDialog(
        onDismiss = { if (!isApplying) onClose() },
        dismissOnOutside = !isApplying,
        showClose = !isApplying,
        title = title,
        titleIcon = Icons.Outlined.Image,
        maxWidth = 440.dp,
        subtitle = "Original: ${formatBytes(sizeBytes)}"
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(166.dp)
                    .background(w.surface)
                    .wikiBorder(w.border)
                    .pointerInput(params.zoom, params.cropAspect) {
                        if (params.zoom > 1.01f || params.cropAspect != "Free") {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val sensitivity = 2f / (min(size.width, size.height).coerceAtLeast(100)).toFloat()
                                params = params.copy(
                                    panX = (params.panX - dragAmount.x * sensitivity).coerceIn(-1f, 1f),
                                    panY = (params.panY - dragAmount.y * sensitivity).coerceIn(-1f, 1f)
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val activePreview = renderedPreview ?: previewBitmap
                if (activePreview != null) {
                    Image(
                        bitmap = activePreview,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        alpha = params.opacity.coerceIn(0.05f, 1f),
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                } else {
                    Text("No image preview available", style = sansStyle(11.sp, w.muted, FontWeight.Normal))
                }
                if (params.zoom > 1.01f) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Drag to pan · ${params.zoom.let { (it * 100).roundToInt() }}%", style = monoStyle(9.sp, Color.White))
                    }
                }
            }

            // Live dimensions & compressed byte estimate strip
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(w.surface)
                    .wikiBorder(w.hairline)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dimText = outputDims?.let { "${it.first}×${it.second}px" } ?: "—"
                Text(dimText, style = monoStyle(10.sp, w.muted), modifier = Modifier.weight(1f))
                val est = estimatedBytes
                if (est != null) {
                    Text("Est: ${formatBytes(est)}", style = monoStyle(10.sp, w.text))
                    val savedPct = ImageEditorEngine.computeSavingsPercent(sizeBytes, est)
                    if (savedPct != null) {
                        Spacer(Modifier.width(6.dp))
                        val badgeColor = if (savedPct > 0) w.success else w.muted
                        val sign = if (savedPct > 0) "-$savedPct%" else "+${-savedPct}%"
                        Text(sign, style = monoStyle(10.sp, badgeColor))
                    }
                } else {
                    Text("Orig: ${formatBytes(sizeBytes)}", style = monoStyle(10.sp, w.muted))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Compress", "Filters (22+)", "Adjust", "Crop").forEachIndexed { i, t ->
                    Chip(t, tab == i, Modifier.weight(1f)) { tab = i }
                }
            }

            when (tab) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(w.surface)
                            .wikiBorder(w.border)
                            .clickable { params = params.copy(compress = !params.compress) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Compress & Optimize", style = sansStyle(12.sp, w.text, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        WikiSwitch(params.compress)
                    }
                    FieldLabel("Quick Presets")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompressionPreset.values().forEach { preset ->
                            val isSelected = params.compress && when (preset) {
                                CompressionPreset.BALANCED -> params.format == "WebP" && params.quality == 82 && params.maxDimension == 1600
                                CompressionPreset.COMPACT -> params.format == "WebP" && params.quality == 65 && params.maxDimension == 1280
                                CompressionPreset.TINY -> params.format == "JPEG" && params.quality == 50 && params.maxDimension == 800
                                CompressionPreset.LOSSLESS -> params.format.startsWith("PNG") && params.maxDimension == null
                            }
                            Chip(preset.label, isSelected, Modifier.weight(1f)) {
                                params = params.withCompressionPreset(preset)
                            }
                        }
                    }
                    FieldLabel("Format")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("WebP", "JPEG", "PNG (Lossless)").forEach { fmt ->
                            Chip(fmt, params.format == fmt, Modifier.weight(1f)) {
                                params = params.copy(compress = true, format = fmt)
                            }
                        }
                    }
                    if (!isLosslessPng) {
                        LabeledSlider("Quality", params.quality.toFloat(), 1f..100f, "%") {
                            params = params.copy(compress = true, quality = it.roundToInt().coerceIn(1, 100))
                        }
                    }
                    FieldLabel("Max Resolution")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ImageEditorEngine.MAX_DIMENSIONS.forEach { (label, dim) ->
                            Chip(label, params.maxDimension == dim, Modifier.weight(1f)) {
                                params = params.copy(compress = true, maxDimension = dim)
                            }
                        }
                    }
                    FieldLabel("Bit Depth")
                    listOf("24-bit RGB", "16-bit Hi", "8-bit 256c", "1-bit Mono").chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { d ->
                                Chip(d, params.bitDepth == d, Modifier.weight(1f)) {
                                    params = params.copy(compress = true, bitDepth = d)
                                }
                            }
                        }
                    }
                    FieldLabel("Color Space")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("sRGB", "Display P3", "Grayscale").forEach { sp ->
                            Chip(sp, params.colorSpace == sp, Modifier.weight(1f)) {
                                params = params.copy(compress = true, colorSpace = sp)
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { params = params.copy(stripExif = !params.stripExif) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Strip EXIF Metadata", style = sansStyle(12.sp, w.text, FontWeight.Medium), modifier = Modifier.weight(1f))
                        WikiSwitch(params.stripExif)
                    }
                }
                1 -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    IMAGE_FILTERS.chunked(2).forEachIndexed { r, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEachIndexed { c, f ->
                                val idx = r * 2 + c
                                Chip(f, params.filterIndex == idx, Modifier.weight(1f)) {
                                    params = params.copy(filterIndex = idx)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                2 -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledSlider("Brightness", params.brightness, 0f..200f, "%") { params = params.copy(brightness = it) }
                    LabeledSlider("Contrast", params.contrast, 0f..200f, "%") { params = params.copy(contrast = it) }
                    LabeledSlider("Saturation (Vibrance)", params.saturation, 0f..200f, "%") { params = params.copy(saturation = it) }
                    LabeledSlider("Warmth / Coolness", params.warmth, -100f..100f) { params = params.copy(warmth = it) }
                    LabeledSlider("Hue Rotate", params.hue, 0f..360f, "°") { params = params.copy(hue = it) }
                    LabeledSlider("Opacity", params.opacity * 100f, 5f..100f, "%") {
                        params = params.copy(opacity = (it / 100f).coerceIn(0.05f, 1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        WikiButton(
                            "Reset Tone",
                            {
                                params = params.copy(
                                    brightness = 100f,
                                    contrast = 100f,
                                    saturation = 100f,
                                    warmth = 0f,
                                    hue = 0f,
                                    opacity = initialOpacity.coerceIn(0.05f, 1f)
                                )
                            },
                            height = 32.dp
                        )
                    }
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldLabel("Rotate & Flip")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WikiButton("Rotate -90°", { params = params.copy(rotationDegrees = (params.rotationDegrees + 270) % 360) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Outlined.RotateLeft, height = 34.dp)
                        WikiButton("Rotate +90°", { params = params.copy(rotationDegrees = (params.rotationDegrees + 90) % 360) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Outlined.RotateRight, height = 34.dp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Flip H", params.flipH, Modifier.weight(1f)) { params = params.copy(flipH = !params.flipH) }
                        Chip("Flip V", params.flipV, Modifier.weight(1f)) { params = params.copy(flipV = !params.flipV) }
                    }
                    FieldLabel("Crop Aspect Ratio")
                    ImageEditorEngine.CROP_ASPECTS.chunked(3).forEach { rowAspects ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowAspects.forEach { aspect ->
                                Chip(aspect, params.cropAspect == aspect, Modifier.weight(1f)) {
                                    params = params.copy(cropAspect = aspect, panX = 0f, panY = 0f)
                                }
                            }
                        }
                    }
                    LabeledSlider("Zoom", params.zoom * 100f, 100f..400f, "%") {
                        val nextZoom = (it / 100f).coerceIn(1f, 4f)
                        params = params.copy(zoom = nextZoom)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        WikiButton(
                            "Reset Crop",
                            {
                                params = params.copy(
                                    rotationDegrees = 0,
                                    flipH = false,
                                    flipV = false,
                                    cropAspect = "Free",
                                    zoom = 1f,
                                    panX = 0f,
                                    panY = 0f
                                )
                            },
                            height = 32.dp
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasChanges && !isApplying) {
                    WikiButton(
                        "Reset",
                        { params = ImageEditParams(opacity = initialOpacity.coerceIn(0.05f, 1f)) },
                        height = 36.dp
                    )
                }
                Spacer(Modifier.weight(1f))
                WikiButton("Cancel", onClose, enabled = !isApplying, height = 36.dp)
                WikiButton(
                    text = if (isApplying) "Applying..." else "Apply",
                    onClick = {
                        if (isApplying) return@WikiButton
                        val src = sourceBitmap
                        val needsReencode = params.hasPixelOrEncodingChanges() && src != null && !src.isRecycled && onApplyEdited != null
                        if (!needsReencode) {
                            onApplyOpacity?.invoke(params.opacity.coerceIn(0.05f, 1f))
                            onApply()
                            onClose()
                        } else {
                            isApplying = true
                            scope.launch {
                                try {
                                    val resultPair = withContext(Dispatchers.Default) {
                                        val processed = ImageEditorEngine.processBitmapForExport(src!!, params)
                                        try {
                                            val encoded = ImageEditorEngine.encodeBitmap(processed, params)
                                            val previewImg = processed.asImageBitmap()
                                            encoded to previewImg
                                        } finally {
                                            // Keep processed alive as ImageBitmap wrapper until cache uses or replaces it
                                        }
                                    }
                                    onApplyEdited?.invoke(resultPair.first, resultPair.second)
                                    onApply()
                                    onClose()
                                } finally {
                                    isApplying = false
                                }
                            }
                        }
                    },
                    enabled = !isApplying,
                    kind = BtnKind.PRIMARY,
                    icon = Icons.Outlined.Check,
                    height = 36.dp
                )
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
