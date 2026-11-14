package com.likkho.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.likkho.app.editor.HeadingTypographySpec
import com.likkho.app.editor.RichTextEditorState
import com.likkho.app.editor.RichTextVisualTransformation
import com.likkho.app.editor.drawBlockDecorations
import com.likkho.app.editor.drawTextHighlights
import com.likkho.app.editor.model.SpanStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * WORKSPACE (WritingWorkspace.tsx). Opens in READING mode for existing diary (Edit button), EDIT mode for new.
 * Header: back | PFP | heading | [delete] | Reminder | Edit/Save.  Then reminder sub-bar, selected image/audio control bars,
 * canvas (bg image + draggable images/audio + text), and the formatting toolbar docked at bottom (edit mode only).
 * TODO(StudioAI): the text area is a plain BasicTextField; replace it with a real rich-text editor and wire hooks.applyXxx.
 */
@Composable
fun WorkspaceScreen(vm: AppViewModel) {
    val w = wiki(); val hooks = vm.hooks; val scope = rememberCoroutineScope()
    val initial = vm.editingLog
    var reading by remember { mutableStateOf(initial != null) }
    var heading by remember { mutableStateOf(initial?.heading.orEmpty()) }
    var pfp by remember { mutableStateOf(initial?.pfp) }
    var reminderAt by remember { mutableStateOf(initial?.reminderAt) }
    var lockPin by remember { mutableStateOf(initial?.diaryLockPin) }
    var bgSrc by remember { mutableStateOf(initial?.canvasBgSource) }
    var bgOpacity by remember { mutableStateOf(initial?.canvasBgOpacity ?: 0.35f) }
    var images by remember { mutableStateOf(initial?.canvasImages ?: emptyList()) }
    var audios by remember { mutableStateOf(initial?.audioAttachments ?: emptyList()) }
    val editorState = remember(initial?.id) { RichTextEditorState.fromHtml(initial?.contentHtml.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    var selImg by remember { mutableStateOf<String?>(null) }
    var selAud by remember { mutableStateOf<String?>(null) }
    var showReminder by remember { mutableStateOf(false) }
    var showPfp by remember { mutableStateOf(false) }
    var imgStudio by remember { mutableStateOf<String?>(null) }
    var audStudio by remember { mutableStateOf<CanvasAudio?>(null) }
    var bgStudio by remember { mutableStateOf(false) }
    var latexEdit by remember { mutableStateOf<String?>(null) }
    var spoilerAsk by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }

    fun save() {
        val now = System.currentTimeMillis()
        val base = initial ?: DiaryLog(id = "log_$now", heading = "", createdAt = now)
        val htmlContent = editorState.toHtml()
        val plain = editorState.text.take(140).replace("\n", " ")
        val log = base.copy(
            heading = heading.ifBlank { "Untitled" },
            contentHtml = htmlContent,
            plainPreview = plain,
            pfp = pfp,
            updatedAt = now,
            reminderAt = reminderAt,
            diaryLockPin = lockPin,
            canvasBgSource = bgSrc,
            canvasBgOpacity = bgOpacity,
            canvasImages = images,
            audioAttachments = audios
        )
        vm.upsertLog(log, false)
        hooks.scheduleReminder(log, reminderAt)
        saved = true
        scope.launch { delay(1500); saved = false }
    }
    BackHandler { if (selImg != null || selAud != null) { selImg = null; selAud = null } else vm.navigateTo(Route.HOME) }

    Column(Modifier.fillMaxSize().background(w.bg).imePadding()) {
        // ---------- header ----------
        Column(Modifier.fillMaxWidth().background(w.surface)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Outlined.ArrowBack, "Back to Homepage", { vm.navigateTo(Route.HOME) }, size = 40.dp)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clickable(enabled = !reading) { showPfp = true }) { PfpAvatar(pfp, 40.dp) }
                Spacer(Modifier.width(6.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (lockPin != null) { Icon(Icons.Outlined.Lock, "Diary Locked", tint = w.danger, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                    BasicTextField(heading, { heading = it }, readOnly = reading, singleLine = true, textStyle = serifStyle(18.sp, w.text), cursorBrush = SolidColor(w.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        decorationBox = { inner -> Box { if (heading.isEmpty()) Text("Diary Heading...", style = serifStyle(18.sp, w.muted.copy(alpha = 0.6f))); inner() } })
                }
                if (initial != null) IconBtn(Icons.Outlined.Delete, "Delete entry", { vm.requestDelete(initial.id) }, size = 40.dp, tint = w.danger, iconSize = 18.dp)
                Spacer(Modifier.width(4.dp))
                val active = reminderAt != null && reminderAt!! > System.currentTimeMillis()
                Row(Modifier.height(40.dp).background(if (active) w.accent.copy(alpha = 0.1f) else w.bg).wikiBorder(if (active) w.accent else w.border).clickable { showReminder = true }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (active) Icons.Outlined.NotificationsActive else Icons.Outlined.Notifications, null, tint = if (active) w.accent else w.text, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(4.dp))
                if (reading) WikiButton("Edit", { reading = false }, kind = BtnKind.PRIMARY, icon = Icons.Outlined.Edit, height = 40.dp)
                else WikiButton(if (saved) "Saved" else "Save", { save() }, kind = if (saved) BtnKind.SUCCESS else BtnKind.PRIMARY, icon = if (saved) Icons.Outlined.Check else Icons.Outlined.Save, height = 40.dp)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.border))
        }
        // ---------- reminder sub-bar ----------
        reminderAt?.takeIf { it > System.currentTimeMillis() }?.let {
            Row(Modifier.fillMaxWidth().background(w.accent.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🔔 ${fmtDateTimeShort(it)}", style = sansStyle(12.sp, w.accent, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text("Edit", style = sansStyle(12.sp, w.accent, FontWeight.Bold), modifier = Modifier.clickable { showReminder = true })
            }
        }
        // ---------- selected image bar ----------
        images.find { it.id == selImg }?.takeIf { !reading }?.let { im ->
            fun upd(f: (CanvasImage) -> CanvasImage) { images = images.map { if (it.id == im.id) f(it) else it } }
            ControlBar {
                Bar("Size -", Icons.Outlined.ZoomOut) { upd { it.copy(width = (it.width - 20).coerceAtLeast(50f), height = (it.height - 15).coerceAtLeast(40f)) } }
                Bar("Size +", Icons.Outlined.ZoomIn) { upd { it.copy(width = (it.width + 20).coerceAtMost(420f), height = it.height + 15) } }
                Bar("-15°", Icons.Outlined.RotateLeft) { upd { it.copy(rotation = (it.rotation - 15) % 360) } }
                Bar("+15°", Icons.Outlined.RotateRight) { upd { it.copy(rotation = (it.rotation + 15) % 360) } }
                Text("Size: ${formatBytes(im.sizeBytes)}", style = monoStyle(10.sp, w.muted))
                Bar("Compress / Edit", Icons.Outlined.Edit) { imgStudio = im.id }
                Bar(if (im.behindText) "Behind Text (BG)" else "In Front of Text (FG)", Icons.Outlined.Layers, on = im.behindText) { upd { it.copy(behindText = !it.behindText) } }
                Bar("", Icons.Outlined.Delete, danger = true) { images = images.filter { it.id != im.id }; selImg = null }
                Bar("Done", null) { selImg = null }
            }
        }
        // ---------- selected audio bar ----------
        audios.find { it.id == selAud }?.takeIf { !reading }?.let { a ->
            fun upd(f: (CanvasAudio) -> CanvasAudio) { audios = audios.map { if (it.id == a.id) f(it) else it } }
            Column {
                ControlBar {
                    Bar("W -", Icons.Outlined.SwapHoriz) { upd { it.copy(width = (it.width - 16).coerceAtLeast(170f)) } }
                    Bar("W +", Icons.Outlined.SwapHoriz) { upd { it.copy(width = (it.width + 16).coerceAtMost(440f)) } }
                    Bar("H -", Icons.Outlined.SwapVert) { upd { it.copy(height = (it.height - 8).coerceAtLeast(46f)) } }
                    Bar("H +", Icons.Outlined.SwapVert) { upd { it.copy(height = (it.height + 8).coerceAtMost(180f)) } }
                    Bar("", Icons.Outlined.ArrowBack) { upd { it.copy(x = (it.x - 16).coerceAtLeast(0f)) } }
                    Bar("", Icons.Outlined.ArrowForward) { upd { it.copy(x = it.x + 16) } }
                    Bar("", Icons.Outlined.ArrowUpward) { upd { it.copy(y = (it.y - 16).coerceAtLeast(0f)) } }
                    Bar("", Icons.Outlined.ArrowDownward) { upd { it.copy(y = it.y + 16) } }
                    Bar("Speed ${a.playbackRate}x", Icons.Outlined.Speed) { upd { it.copy(playbackRate = AUDIO_SPEEDS[(AUDIO_SPEEDS.indexOf(it.playbackRate) + 1) % AUDIO_SPEEDS.size]) } }
                    Bar(if (a.behindText) "Behind Text (BG)" else "In Front of Text (FG)", Icons.Outlined.Layers, on = a.behindText) { upd { it.copy(behindText = !it.behindText) } }
                    Bar("Edit", Icons.Outlined.GraphicEq) { audStudio = a }
                    Bar("Delete", Icons.Outlined.Delete, danger = true) { audios = audios.filter { it.id != a.id }; selAud = null }
                    Bar("Done", null) { selAud = null }
                }
                Row(Modifier.fillMaxWidth().background(w.surface).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rotate: ${a.rotation.toInt()}°", style = monoStyle(10.sp, w.muted)); Spacer(Modifier.width(8.dp))
                    WikiSlider(a.rotation, { v -> upd { it.copy(rotation = v) } }, 0f..360f, Modifier.weight(1f))
                }
                HairlineDivider()
            }
        }

        // ---------- canvas ----------
        Box(Modifier.weight(1f).fillMaxWidth().background(w.bg)) {
            if (bgSrc != null) Box(Modifier.fillMaxSize().background(w.accent.copy(alpha = bgOpacity * 0.3f))) // TODO(StudioAI): draw real bg image with bgOpacity
            images.filter { it.behindText }.forEach { DraggableImage(it, selImg == it.id, !reading, { selImg = it.id; selAud = null }) { x, y -> images = images.map { m -> if (m.id == it.id) m.copy(x = x, y = y) else m } } }
            audios.filter { it.behindText }.forEach { AudioCard(it, selAud == it.id, !reading) { selAud = it.id; selImg = null } }
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                val editorTextStyle = TextStyle(fontFamily = WikiFonts.prose, fontSize = 16.sp, color = w.text)
                val fontFamilyResolver = LocalFontFamilyResolver.current
                val density = LocalDensity.current
                val resolvedTypefaceState = remember(editorTextStyle, fontFamilyResolver) {
                    fontFamilyResolver.resolve(
                        fontFamily = editorTextStyle.fontFamily,
                        fontWeight = editorTextStyle.fontWeight ?: FontWeight.Normal,
                        fontStyle = editorTextStyle.fontStyle ?: FontStyle.Normal,
                        fontSynthesis = editorTextStyle.fontSynthesis ?: FontSynthesis.All,
                    )
                }
                val fontMetrics = remember(resolvedTypefaceState.value, editorTextStyle.fontSize, density) {
                    android.graphics.Paint().apply {
                        typeface = resolvedTypefaceState.value as? android.graphics.Typeface
                        textSize = with(density) { editorTextStyle.fontSize.toPx() }
                    }.fontMetrics
                }
                val resolvedHeadingTypeface = remember(fontFamilyResolver) {
                    fontFamilyResolver.resolve(
                        fontFamily = HeadingTypographySpec.fontFamily,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Normal,
                        fontSynthesis = FontSynthesis.All,
                    )
                }
                val headingFontMetrics = remember(resolvedHeadingTypeface.value, density) {
                    mapOf(
                        1 to android.graphics.Paint().apply {
                            typeface = resolvedHeadingTypeface.value as? android.graphics.Typeface
                            textSize = with(density) { HeadingTypographySpec.fontSize(1).toPx() }
                        }.fontMetrics,
                        2 to android.graphics.Paint().apply {
                            typeface = resolvedHeadingTypeface.value as? android.graphics.Typeface
                            textSize = with(density) { HeadingTypographySpec.fontSize(2).toPx() }
                        }.fontMetrics,
                        3 to android.graphics.Paint().apply {
                            typeface = resolvedHeadingTypeface.value as? android.graphics.Typeface
                            textSize = with(density) { HeadingTypographySpec.fontSize(3).toPx() }
                        }.fontMetrics,
                    )
                }
                val context = androidx.compose.ui.platform.LocalContext.current
                var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

                BasicTextField(
                    value = editorState.textFieldValue,
                    onValueChange = { editorState.onTextFieldValueChange(it) },
                    readOnly = reading,
                    textStyle = editorTextStyle,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    cursorBrush = SolidColor(w.accent),
                    visualTransformation = remember(editorState.spans, vm.customFonts.size, w.isDark) {
                        RichTextVisualTransformation(
                            spans = editorState.spans,
                            defaultTextColor = w.text,
                            quoteTextColor = w.text.copy(alpha = 0.85f),
                            codeTextColor = w.text
                        )
                    },
                    onTextLayout = { textLayoutResult = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier.drawBehind {
                                val layout = textLayoutResult ?: return@drawBehind
                                drawBlockDecorations(
                                    layoutResult = layout,
                                    spans = editorState.spans,
                                    quoteBgColor = w.accent.copy(alpha = if (w.isDark) 0.11f else 0.055f),
                                    quoteBarColor = w.accent,
                                    codeBgColor = w.surface,
                                    codeBorderColor = w.border.copy(alpha = 0.55f)
                                )
                                drawTextHighlights(
                                    layoutResult = layout,
                                    spans = editorState.spans,
                                    ascentPx = fontMetrics.ascent,
                                    descentPx = fontMetrics.descent,
                                    lineMetrics = { segStart, segEnd ->
                                        var bestLevel: Int? = null
                                        var activeFont: String? = null
                                        var hasQuote = false
                                        var hasCode = false
                                        for (sp in editorState.spans) {
                                            if (sp.start < segEnd && sp.end > segStart) {
                                                when (val st = sp.style) {
                                                    is SpanStyle.Heading -> {
                                                        if (bestLevel == null || st.level < bestLevel) bestLevel = st.level
                                                    }
                                                    is SpanStyle.FontId -> activeFont = st.fontId
                                                    is SpanStyle.Quote -> hasQuote = true
                                                    is SpanStyle.Code -> hasCode = true
                                                    else -> {}
                                                }
                                            }
                                        }
                                        val effectiveFontId = activeFont ?: when {
                                            hasCode -> com.likkho.app.editor.FontRegistry.BUILTIN_MONO
                                            hasQuote -> com.likkho.app.editor.FontRegistry.BUILTIN_SERIF
                                            else -> null
                                        }
                                        val effectiveSp = bestLevel?.let { HeadingTypographySpec.fontSize(it) }
                                            ?: if (hasCode && activeFont == null) 14.5.sp else editorTextStyle.fontSize
                                        val pxSize = with(density) { effectiveSp.toPx() }
                                        val resolvedTf = effectiveFontId?.let {
                                            com.likkho.app.editor.FontRegistry.resolveTypeface(context, it)
                                        }
                                        val fm = when {
                                            resolvedTf != null -> {
                                                android.graphics.Paint().apply {
                                                    typeface = resolvedTf
                                                    textSize = pxSize
                                                }.fontMetrics
                                            }
                                            bestLevel != null -> headingFontMetrics[bestLevel] ?: fontMetrics
                                            else -> fontMetrics
                                        }
                                        val hasDevanagari = (segStart until segEnd.coerceAtMost(editorState.text.length)).any { idx ->
                                            editorState.text[idx] in '\u0900'..'\u097F'
                                        }
                                        if (hasDevanagari) {
                                            val devFm = android.graphics.Paint().apply {
                                                typeface = android.graphics.Typeface.DEFAULT
                                                textSize = pxSize
                                            }.fontMetrics
                                            minOf(fm.ascent, devFm.ascent) to maxOf(fm.descent, devFm.descent)
                                        } else {
                                            fm.ascent to fm.descent
                                        }
                                    }
                                )
                            }
                        ) {
                            if (editorState.text.isEmpty()) Text("Start writing...", style = proseStyle(16.sp, w.muted.copy(alpha = 0.65f), italic = true))
                            inner()
                        }
                    }
                )
            }
            images.filter { !it.behindText }.forEach { DraggableImage(it, selImg == it.id, !reading, { selImg = it.id; selAud = null }) { x, y -> images = images.map { m -> if (m.id == it.id) m.copy(x = x, y = y) else m } } }
            audios.filter { !it.behindText }.forEach { AudioCard(it, selAud == it.id, !reading) { selAud = it.id; selImg = null } }
        }

        // ---------- toolbar ----------
        if (!reading) RichToolbar(
            vm = vm, lockPin = lockPin, bgSrc = bgSrc, bgOpacity = bgOpacity, recording = recording,
            onRecordToggle = {
                if (!recording) { hooks.startVoiceRecording(); recording = true }
                else { recording = false; hooks.stopVoiceRecording()?.let { audios = audios + it; vm.showStatus("Voice recording saved to diary.") } }
            },
            onAddImage = { scope.launch { hooks.pickImage()?.let { images = images + it; selImg = it.id } } },
            onAddAudio = { scope.launch { hooks.pickAudio()?.let { audios = audios + it } } },
            onLock = { lockPin = it }, onBgOpacity = { bgOpacity = it },
            onPickBg = { scope.launch { hooks.pickBackgroundImage()?.let { bgSrc = it } } }, onRemoveBg = { bgSrc = null }, onEditBg = { bgStudio = true },
            onInsertText = { inserted ->
                val current = editorState.textFieldValue
                val pos = current.selection.start
                val newText = current.text.substring(0, pos) + inserted + current.text.substring(pos)
                editorState.onTextFieldValueChange(
                    current.copy(
                        text = newText,
                        selection = androidx.compose.ui.text.TextRange(pos + inserted.length)
                    )
                )
            },
            onSpoiler = { hooks.applySpoiler(it) },
            editorState = editorState,
        )
    }

    if (showReminder) ReminderDialog(reminderAt, { showReminder = false }) { reminderAt = it }
    if (showPfp) PfpStudioDialog(pfp, hooks, { showPfp = false }) { pfp = it }
    imgStudio?.let { id -> ImageStudioDialog("Image Studio", images.find { it.id == id }?.sizeBytes ?: 0, { imgStudio = null }) { /* TODO(StudioAI): apply edits */ } }
    if (bgStudio) ImageStudioDialog("Background Image Studio", 0, { bgStudio = false }) { }
    audStudio?.let { AudioStudioDialog(it.name, { audStudio = null }) { } }
}

@Composable
private fun ControlBar(content: @Composable RowScope.() -> Unit) {
    val w = wiki()
    Column {
        Row(Modifier.fillMaxWidth().background(w.surface).horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        HairlineDivider()
    }
}

@Composable
private fun Bar(label: String, icon: ImageVector?, on: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val w = wiki()
    val c = if (danger) w.danger else if (on) Color.White else w.text
    Row(Modifier.height(30.dp).background(if (on) w.accent else w.bg).wikiBorder(if (on) w.accent else if (danger) w.danger else w.border).clickable(onClick = onClick).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, null, tint = c, modifier = Modifier.size(14.dp))
        if (label.isNotEmpty()) { if (icon != null) Spacer(Modifier.width(4.dp)); Text(label, style = sansStyle(11.sp, c, FontWeight.SemiBold), maxLines = 1) }
    }
}

@Composable
private fun DraggableImage(im: CanvasImage, selected: Boolean, editable: Boolean, onSelect: () -> Unit, onMove: (Float, Float) -> Unit) {
    val w = wiki()
    var x by remember(im.id) { mutableStateOf(im.x) }; var y by remember(im.id) { mutableStateOf(im.y) }
    Box(
        Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(im.width.dp, im.height.dp).rotate(im.rotation)
            .background(w.surface).wikiBorder(if (selected) w.accent else w.border, if (selected) 1.5.dp else 0.5.dp)
            .pointerInput(editable) { detectTapGestures(onTap = { if (editable) onSelect() }) }
            .pointerInput(editable) { if (editable) detectDragGestures(onDragStart = { onSelect() }, onDragEnd = { onMove(x, y) }) { c, d -> c.consume(); x += d.x; y += d.y } },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Image, null, tint = w.muted) /* TODO(StudioAI): real bitmap from im.source */ }
}

/** Opaque RED audio card #b32424. Play button before slider. Double-tap (edit mode) selects it for the control bar. */
@Composable
private fun AudioCard(a: CanvasAudio, selected: Boolean, editable: Boolean, onSelect: () -> Unit) {
    var playing by remember(a.id) { mutableStateOf(false) }
    var progress by remember(a.id) { mutableStateOf(0f) }
    Row(
        Modifier.offset { IntOffset(a.x.roundToInt(), a.y.roundToInt()) }.size(a.width.dp, a.height.dp).rotate(a.rotation)
            .background(Color(0xFFB32424)).wikiBorder(if (selected) wiki().accent else Color(0xFF7A1616), if (selected) 1.5.dp else 0.5.dp)
            .pointerInput(editable) { detectTapGestures(onDoubleTap = { if (editable) onSelect() }) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(Color(0xFF8E1B1B)).wikiBorder(Color.White.copy(alpha = 0.4f)).clickable { playing = !playing /* TODO(StudioAI): real playback */ }, contentAlignment = Alignment.Center) {
            Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = sansStyle(10.sp, Color.White, FontWeight.Medium), maxLines = 1)
            androidx.compose.material3.Slider(progress, { progress = it }, Modifier.height(20.dp), colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)))
        }
        Text("${a.durationSec}s", style = monoStyle(10.sp, Color.White))
    }
}
