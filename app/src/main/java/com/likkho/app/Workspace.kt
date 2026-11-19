package com.likkho.app

import android.graphics.SurfaceTexture
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Build
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.likkho.app.editor.BlockDecorationLayoutCalculator
import com.likkho.app.editor.HeadingTypographySpec
import com.likkho.app.editor.RichTextEditorState
import com.likkho.app.editor.RichTextVisualTransformation
import com.likkho.app.editor.drawBlockDecorations
import com.likkho.app.editor.drawTextHighlights
import com.likkho.app.editor.drawTextSpoilers
import com.likkho.app.editor.model.SpanStyle
import com.likkho.app.editor.toComposeTextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var videos by remember { mutableStateOf(initial?.videoAttachments ?: emptyList()) }
    val editorState = remember(initial?.id) { RichTextEditorState.fromHtml(initial?.contentHtml.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    var selImg by remember { mutableStateOf<String?>(null) }
    var selAud by remember { mutableStateOf<String?>(null) }
    var selVid by remember { mutableStateOf<String?>(null) }
    var showReminder by remember { mutableStateOf(false) }
    var showPfp by remember { mutableStateOf(false) }
    var imgStudio by remember { mutableStateOf<String?>(null) }
    var audStudio by remember { mutableStateOf<CanvasAudio?>(null) }
    var bgStudio by remember { mutableStateOf(false) }
    var spoilerUnlockPrompt by remember { mutableStateOf<SpanStyle.Spoiler?>(null) }
    var recording by remember { mutableStateOf(false) }

    val initialMediaIds = remember(initial?.id) {
        collectCanvasMediaIds(pfp, bgSrc, images, audios, videos)
    }
    var lastSavedMediaIds by remember(initial?.id) { mutableStateOf(initialMediaIds) }
    val sessionImportedMediaIds = remember(initial?.id) { mutableStateListOf<String>() }

    DisposableEffect(editorState) {
        editorState.onLockedSpoilerBlocked = { blocked ->
            vm.showStatus("Enter spoiler passcode to edit, delete, or copy.", BannerKind.ERROR)
            spoilerUnlockPrompt = blocked
        }
        onDispose {
            editorState.onLockedSpoilerBlocked = null
        }
    }

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val outerDensity = LocalDensity.current

    fun currentSpawnCoords(): Pair<Float, Float> {
        val sx = with(outerDensity) { hScroll.value.toDp().value } + 24f
        val sy = with(outerDensity) { vScroll.value.toDp().value } + 120f
        return sx.coerceAtLeast(0f) to sy.coerceAtLeast(0f)
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val (sx, sy) = currentSpawnCoords()
                val imported = vm.importImageUri(uri, sx, sy)
                if (imported != null) {
                    sessionImportedMediaIds.add(imported.id)
                    images = images + imported
                    selImg = imported.id
                    selAud = null
                    selVid = null
                } else {
                    vm.showStatus("Could not import image.", BannerKind.ERROR)
                }
            }
        }
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val (sx, sy) = currentSpawnCoords()
                val imported = vm.importVideoUri(uri, sx, sy)
                if (imported != null) {
                    sessionImportedMediaIds.add(imported.id)
                    videos = videos + imported
                    selVid = imported.id
                    selImg = null
                    selAud = null
                } else {
                    vm.showStatus("Could not import video.", BannerKind.ERROR)
                }
            }
        }
    }
    val audioPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val (sx, sy) = currentSpawnCoords()
                val imported = vm.importAudioUri(uri, sx, sy)
                if (imported != null) {
                    sessionImportedMediaIds.add(imported.audioUri ?: imported.id)
                    audios = audios + imported
                    selAud = imported.id
                    selImg = null
                    selVid = null
                } else {
                    vm.showStatus("Could not import audio.", BannerKind.ERROR)
                }
            }
        }
    }
    val bgPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val mediaId = vm.importMediaUri(uri)
                if (mediaId != null) {
                    sessionImportedMediaIds.add(mediaId)
                    bgSrc = mediaId
                } else {
                    vm.showStatus("Could not import background image.", BannerKind.ERROR)
                }
            }
        }
    }

    fun save(showFeedback: Boolean = true) {
        val now = System.currentTimeMillis()
        val base = initial ?: DiaryLog(id = "log_$now", heading = "", createdAt = now)
        val htmlContent = editorState.toHtml()
        val plain = editorState.maskedPlainPreview(140)
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
            audioAttachments = audios,
            videoAttachments = videos
        )
        val currentMediaIds = collectCanvasMediaIds(pfp, bgSrc, images, audios, videos)
        val obsoleteMediaIds = (lastSavedMediaIds + sessionImportedMediaIds) - currentMediaIds
        vm.upsertLog(log, false, obsoleteMediaIds) { persistResult ->
            persistResult.onSuccess {
                lastSavedMediaIds = currentMediaIds
                sessionImportedMediaIds.clear()
                hooks.scheduleReminder(log, reminderAt) { remRes ->
                    remRes.onFailure { err ->
                        scope.launch {
                            val st = storageFailureBanner("Reminder update failed", err)
                            vm.showStatus(st.message, st.kind, 4000)
                        }
                    }
                }
                if (showFeedback) {
                    saved = true
                    scope.launch { delay(1500); saved = false }
                }
            }.onFailure {
                saved = false
            }
        }
    }

    fun exitWorkspace() {
        val discardedMediaIds = sessionImportedMediaIds.toSet() - lastSavedMediaIds
        if (discardedMediaIds.isNotEmpty()) {
            vm.cleanupUnusedSessionMedia(discardedMediaIds)
        }
        sessionImportedMediaIds.clear()
        vm.navigateTo(Route.HOME)
    }

    BackHandler { if (selImg != null || selAud != null || selVid != null) { selImg = null; selAud = null; selVid = null } else exitWorkspace() }

    Column(
        Modifier
            .fillMaxSize()
            .background(w.bg)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
    ) {
        // ---------- header ----------
        Column(Modifier.fillMaxWidth().background(w.surface)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    "Back to Homepage",
                    { exitWorkspace() },
                    size = 48.dp,
                    bordered = false,
                    bg = Color.Transparent
                )
                Spacer(Modifier.width(4.dp))
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
                Text("Size: ${formatBytes(im.sizeBytes)}", style = monoStyle(10.sp, w.muted))
                Text("Pinch anywhere to resize/rotate", style = sansStyle(10.sp, w.accent, FontWeight.Medium))
                Bar("Compress / Edit", Icons.Outlined.Edit) { imgStudio = im.id }
                Bar(if (im.behindText) "Behind Text (BG)" else "In Front of Text (FG)", Icons.Outlined.Layers, on = im.behindText) { upd { it.copy(behindText = !it.behindText) } }
                Bar("", Icons.Outlined.Delete, danger = true) { images = images.filter { it.id != im.id }; selImg = null }
                Bar("Done", null) { selImg = null }
            }
        }
        // ---------- selected video bar ----------
        videos.find { it.id == selVid }?.takeIf { !reading }?.let { v ->
            fun upd(f: (CanvasVideo) -> CanvasVideo) { videos = videos.map { if (it.id == v.id) f(it) else it } }
            ControlBar {
                Text("Size: ${formatBytes(v.sizeBytes)}", style = monoStyle(10.sp, w.muted))
                Text("Pinch anywhere to resize/rotate", style = sansStyle(10.sp, w.accent, FontWeight.Medium))
                Bar(if (v.behindText) "Behind Text (BG)" else "In Front of Text (FG)", Icons.Outlined.Layers, on = v.behindText) { upd { it.copy(behindText = !it.behindText) } }
                Bar("Delete", Icons.Outlined.Delete, danger = true) { videos = videos.filter { it.id != v.id }; selVid = null }
                Bar("Done", null) { selVid = null }
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

        // ---------- canvas (infinite right/bottom media drag, screen-width writing) ----------
        val bgBitmap by produceState<ImageBitmap?>(null, bgSrc) {
            value = bgSrc?.takeIf { it.isNotBlank() }?.let { vm.loadDecryptedImageBitmap(it) }
        }
        var writingBlockHeightPx by remember { mutableStateOf(0) }
        var activeDragBoundsDp by remember { mutableStateOf<Pair<Float, Float>?>(null) }
        var viewportRootOrigin by remember { mutableStateOf(Offset.Zero) }
        val applyCanvasPinch by rememberUpdatedState { zoom: Float, rot: Float ->
            val currentSelImg = selImg
            val currentSelVid = selVid
            if (currentSelImg != null) {
                images = images.map { im ->
                    if (im.id == currentSelImg) {
                        val minScale = maxOf(50f / im.width, 40f / im.height)
                        val maxScale = minOf(1200f / im.width, 1200f / im.height)
                        val scale = zoom.coerceIn(minScale, maxScale)
                        im.copy(
                            width = im.width * scale,
                            height = im.height * scale,
                            rotation = (im.rotation + rot) % 360f
                        )
                    } else im
                }
            } else if (currentSelVid != null) {
                videos = videos.map { v ->
                    if (v.id == currentSelVid) {
                        val minScale = maxOf(100f / v.width, 72f / v.height)
                        val maxScale = minOf(1200f / v.width, 1200f / v.height)
                        val scale = zoom.coerceIn(minScale, maxScale)
                        v.copy(
                            width = v.width * scale,
                            height = v.height * scale,
                            rotation = (v.rotation + rot) % 360f
                        )
                    } else v
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(w.bg)
                .onGloballyPositioned { coords -> viewportRootOrigin = coords.positionInRoot() }
                .pointerInput(selImg, selVid, reading) {
                    if (!reading && (selImg != null || selVid != null)) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var zooming = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    zooming = true
                                    val zoom = event.calculateZoom()
                                    val rot = event.calculateRotation()
                                    if (zoom != 1f || rot != 0f) {
                                        applyCanvasPinch(zoom, rot)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (zooming) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
        ) {
            val viewportW = maxWidth
            val viewportH = maxHeight
            val density = LocalDensity.current
            val viewportWPx = with(density) { viewportW.toPx() }
            val viewportHPx = with(density) { viewportH.toPx() }
            val writingHeightDp = with(density) { writingBlockHeightPx.toDp() }

            val mediaMaxRightDp = maxOf(
                images.maxOfOrNull { it.x + it.width } ?: 0f,
                audios.maxOfOrNull { it.x + it.width } ?: 0f,
                videos.maxOfOrNull { it.x + it.width } ?: 0f,
                activeDragBoundsDp?.first ?: 0f
            )
            val mediaMaxBottomDp = maxOf(
                images.maxOfOrNull { it.y + it.height } ?: 0f,
                audios.maxOfOrNull { it.y + it.height } ?: 0f,
                videos.maxOfOrNull { it.y + it.height } ?: 0f,
                activeDragBoundsDp?.second ?: 0f
            )
            val contentNeedW = if (mediaMaxRightDp > viewportW.value - 32f || activeDragBoundsDp != null || hScroll.value > 0) {
                maxOf(viewportW, (mediaMaxRightDp + 240f).dp)
            } else {
                viewportW
            }
            val canvasWidth = maxOf(
                contentNeedW,
                with(density) { (hScroll.value + viewportWPx).toDp() }
            )
            val contentNeedH = maxOf(
                viewportH,
                writingHeightDp + 120.dp,
                (mediaMaxBottomDp + 240f).dp
            )
            val canvasHeight = maxOf(
                contentNeedH,
                with(density) { (vScroll.value + viewportHPx).toDp() }
            )

            if (bgSrc != null) {
                val bgBmp = bgBitmap
                if (bgBmp != null) {
                    Image(
                        bitmap = bgBmp,
                        contentDescription = "Canvas background",
                        contentScale = ContentScale.Crop,
                        alpha = bgOpacity,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(w.accent.copy(alpha = bgOpacity * 0.3f)))
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
                    .verticalScroll(vScroll)
            ) {
                Box(Modifier.size(canvasWidth, canvasHeight)) {
                    // Layer 1: Behind-text media
                    images.filter { it.behindText }.forEach { im ->
                        key(im.id) {
                            DraggableImage(
                                im = im,
                                selected = selImg == im.id,
                                editable = !reading,
                                hScroll = hScroll,
                                vScroll = vScroll,
                                viewportWPx = viewportWPx,
                                viewportHPx = viewportHPx,
                                viewportRootOrigin = viewportRootOrigin,
                                loadImageBitmap = { id -> vm.loadDecryptedImageBitmap(id) },
                                onSelect = { selImg = im.id; selAud = null; selVid = null },
                                onDragProgress = { maxR, maxB -> activeDragBoundsDp = maxR to maxB },
                                onMove = { x, y ->
                                    activeDragBoundsDp = null
                                    images = images.map { m -> if (m.id == im.id) m.copy(x = x, y = y) else m }
                                }
                            )
                        }
                    }
                    videos.filter { it.behindText }.forEach { v ->
                        key(v.id) {
                            VideoCard(
                                v = v,
                                selected = selVid == v.id,
                                editable = !reading,
                                hScroll = hScroll,
                                vScroll = vScroll,
                                viewportWPx = viewportWPx,
                                viewportHPx = viewportHPx,
                                viewportRootOrigin = viewportRootOrigin,
                                loadThumbnail = { id -> vm.loadVideoThumbnailBitmap(id) },
                                openMediaDataSource = { id -> vm.openDecryptedMediaDataSource(id) },
                                onStatus = { vm.showStatus(it, BannerKind.ERROR) },
                                onSelect = { selVid = v.id; selImg = null; selAud = null },
                                onDragProgress = { maxR, maxB -> activeDragBoundsDp = maxR to maxB },
                                onMove = { x, y ->
                                    activeDragBoundsDp = null
                                    videos = videos.map { vid -> if (vid.id == v.id) vid.copy(x = x, y = y) else vid }
                                }
                            )
                        }
                    }
                    audios.filter { it.behindText }.forEach { a ->
                        key(a.id) {
                            AudioCard(
                                a = a,
                                selected = selAud == a.id,
                                editable = !reading,
                                hScroll = hScroll,
                                vScroll = vScroll,
                                viewportWPx = viewportWPx,
                                viewportHPx = viewportHPx,
                                viewportRootOrigin = viewportRootOrigin,
                                loadAudioBytes = { id -> vm.loadDecryptedMediaBytes(id) },
                                onStatus = { vm.showStatus(it, BannerKind.ERROR) },
                                onSelect = { selAud = a.id; selImg = null; selVid = null },
                                onDragProgress = { maxR, maxB -> activeDragBoundsDp = maxR to maxB },
                                onMove = { x, y ->
                                    activeDragBoundsDp = null
                                    audios = audios.map { aud -> if (aud.id == a.id) aud.copy(x = x, y = y) else aud }
                                }
                            )
                        }
                    }

                    // Layer 2: Writing paragraphs locked to phone screen width (viewportW)
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .width(viewportW)
                            .onSizeChanged { writingBlockHeightPx = it.height }
                            .padding(16.dp)
                    ) {
                val emptyPendingIndent = if (editorState.text.isEmpty() && editorState.activePendingIndent > 0) {
                    val indSp = (editorState.activePendingIndent * 24).sp
                    TextIndent(firstLine = indSp, restLine = indSp)
                } else {
                    TextIndent.None
                }
                val editorTextStyle = TextStyle(
                    fontFamily = WikiFonts.prose,
                    fontSize = 16.sp,
                    color = w.text,
                    textAlign = editorState.canvasAlignment.toComposeTextAlign(),
                    textIndent = emptyPendingIndent
                )
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
                val systemClipboard = LocalClipboardManager.current
                val spoilerClipboardManager = remember(systemClipboard, editorState) {
                    object : ClipboardManager by systemClipboard {
                        override fun setText(annotatedString: AnnotatedString) {
                            when (val copyResult = editorState.prepareClipboardCopy()) {
                                is RichTextEditorState.SpoilerCopyResult.AllowPlain -> {
                                    systemClipboard.setText(annotatedString)
                                }
                                is RichTextEditorState.SpoilerCopyResult.Masked -> {
                                    systemClipboard.setText(AnnotatedString(copyResult.text))
                                    vm.showStatus("Copied with hidden spoiler text masked.", BannerKind.INFO)
                                }
                                is RichTextEditorState.SpoilerCopyResult.Blocked -> {
                                    vm.showStatus("Enter spoiler passcode to copy hidden text.", BannerKind.ERROR)
                                    spoilerUnlockPrompt = copyResult.blockingSpoiler
                                }
                            }
                        }
                    }
                }
                val segmentLineMetrics: (Int, Int) -> Pair<Float, Float> = { segStart, segEnd ->
                    var bestLevel: Int? = null
                    var activeFont: String? = null
                    var customSizePx: Float? = null
                    var hasQuote = false
                    var hasCode = false
                    var isScript = false
                    for (sp in editorState.spans) {
                        if (sp.start < segEnd && sp.end > segStart) {
                            when (val st = sp.style) {
                                is SpanStyle.Heading -> {
                                    if (bestLevel == null || st.level < bestLevel) bestLevel = st.level
                                }
                                is SpanStyle.FontId -> activeFont = st.fontId
                                is SpanStyle.SizePx -> customSizePx = st.sizePx
                                is SpanStyle.Quote -> hasQuote = true
                                is SpanStyle.Code -> hasCode = true
                                is SpanStyle.Superscript, is SpanStyle.Subscript -> isScript = true
                                else -> {}
                            }
                        }
                    }
                    val effectiveFontId = activeFont ?: when {
                        hasCode -> com.likkho.app.editor.FontRegistry.BUILTIN_MONO
                        hasQuote -> com.likkho.app.editor.FontRegistry.BUILTIN_SERIF
                        else -> null
                    }
                    val baseSp = customSizePx?.coerceIn(10f, 48f)?.sp
                        ?: bestLevel?.let { HeadingTypographySpec.fontSize(it) }
                        ?: if (hasCode && activeFont == null) 14.5.sp else editorTextStyle.fontSize
                    val effectiveSp = if (isScript) (baseSp.value * 0.72f).coerceAtLeast(8f).sp else baseSp
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
                        bestLevel != null && !isScript -> headingFontMetrics[bestLevel] ?: fontMetrics
                        isScript -> {
                            android.graphics.Paint().apply {
                                typeface = resolvedTypefaceState.value as? android.graphics.Typeface
                                textSize = pxSize
                            }.fontMetrics
                        }
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
                var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                var isEditorFocused by remember { mutableStateOf(false) }
                var cursorVisible by remember { mutableStateOf(true) }

                LaunchedEffect(
                    isEditorFocused,
                    reading,
                    editorState.textFieldValue,
                    editorState.activeFontSizePx,
                    editorState.activeHeadingLevel,
                    editorState.canvasAlignment,
                    editorState.currentIndentLevel,
                    editorState.isSuperscriptActive,
                    editorState.isSubscriptActive,
                    editorState.unlockedSpoilerIds
                ) {
                    if (isEditorFocused && !reading) {
                        cursorVisible = true
                        while (true) {
                            delay(530L)
                            cursorVisible = !cursorVisible
                        }
                    } else {
                        cursorVisible = false
                    }
                }

                CompositionLocalProvider(LocalClipboardManager provides spoilerClipboardManager) {
                BasicTextField(
                    value = editorState.textFieldValue,
                    onValueChange = { editorState.onTextFieldValueChange(it) },
                    readOnly = reading,
                    textStyle = editorTextStyle,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    cursorBrush = SolidColor(if (textLayoutResult == null) w.accent else Color.Transparent),
                    visualTransformation = remember(
                        editorState.spans,
                        editorState.canvasAlignment,
                        editorState.unlockedSpoilerIds,
                        vm.customFonts.size,
                        w.isDark
                    ) {
                        RichTextVisualTransformation(
                            spans = editorState.spans,
                            defaultTextColor = w.text,
                            quoteTextColor = w.text.copy(alpha = 0.85f),
                            codeTextColor = w.text,
                            numberedColor = w.accent,
                            todoCheckedTextColor = w.muted,
                            linkColor = w.link,
                            defaultAlignment = editorState.canvasAlignment,
                            unlockedSpoilerIds = editorState.unlockedSpoilerIds
                        )
                    },
                    onTextLayout = { textLayoutResult = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = (viewportH - 32.dp).coerceAtLeast(360.dp))
                        .onFocusChanged { isEditorFocused = it.isFocused }
                        .pointerInput(editorState, hooks) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                val downPos = down.position
                                val downTime = System.currentTimeMillis()
                                val touchSlop = viewConfiguration.touchSlop
                                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (System.currentTimeMillis() - downTime > longPressTimeout) break
                                    val dx = change.position.x - downPos.x
                                    val dy = change.position.y - downPos.y
                                    if (kotlin.math.hypot(dx, dy) > touchSlop) break
                                    if (change.changedToUpIgnoreConsumed()) {
                                        val layout = textLayoutResult
                                        if (layout != null &&
                                            layout.layoutInput.text.text == editorState.text &&
                                            editorState.text.isNotEmpty()
                                        ) {
                                            val rawOffset = layout.getOffsetForPosition(change.position)
                                            val textLen = layout.layoutInput.text.text.length
                                            val candidateOffsets = buildList {
                                                if (rawOffset in 0 until textLen) add(rawOffset)
                                                if (rawOffset - 1 in 0 until textLen) add(rawOffset - 1)
                                            }
                                            val tappedSpoiler = candidateOffsets.firstNotNullOfOrNull { idx ->
                                                val box = layout.getBoundingBox(idx)
                                                if (change.position.x in (box.left - 8f)..(box.right + 8f) &&
                                                    change.position.y in (box.top - 8f)..(box.bottom + 8f)
                                                ) {
                                                    editorState.lockedSpoilerAtOffset(idx)
                                                } else null
                                            }
                                            if (tappedSpoiler != null) {
                                                change.consume()
                                                spoilerUnlockPrompt = tappedSpoiler
                                                break
                                            }
                                            val hitOffset = candidateOffsets.firstOrNull { idx ->
                                                val box = layout.getBoundingBox(idx)
                                                change.position.x in (box.left - 8f)..(box.right + 8f) &&
                                                    change.position.y in (box.top - 8f)..(box.bottom + 8f) &&
                                                    editorState.linkAtOffset(idx) != null
                                            }
                                            val tappedUrl = hitOffset?.let { editorState.linkAtOffset(it) }
                                            if (!tappedUrl.isNullOrBlank()) {
                                                change.consume()
                                                hooks.openUrl(tappedUrl)
                                            }
                                        }
                                        break
                                    }
                                }
                            }
                        },
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .drawBehind {
                                    val layout = textLayoutResult ?: return@drawBehind
                                    if (layout.layoutInput.text.text != editorState.text) return@drawBehind
                                    drawBlockDecorations(
                                        layoutResult = layout,
                                        spans = editorState.spans,
                                        quoteBgColor = w.accent.copy(alpha = if (w.isDark) 0.11f else 0.055f),
                                        quoteBarColor = w.accent,
                                        codeBgColor = w.surface,
                                        codeBorderColor = w.border.copy(alpha = 0.55f),
                                        dividerColor = w.border,
                                        activeHeadingLevel = editorState.activeHeadingLevel,
                                        cursorOffset = editorState.selection.start,
                                        bulletColor = w.text.copy(alpha = 0.85f),
                                        todoUncheckedBorderColor = w.text.copy(alpha = 0.58f),
                                        todoUncheckedBgColor = w.surface,
                                        todoCheckedFillColor = w.accent
                                    )
                                    drawTextHighlights(
                                        layoutResult = layout,
                                        spans = editorState.spans,
                                        ascentPx = fontMetrics.ascent,
                                        descentPx = fontMetrics.descent,
                                        lineMetrics = segmentLineMetrics
                                    )
                                }
                                .drawWithContent {
                                    drawContent()
                                    val layout = textLayoutResult ?: return@drawWithContent
                                    if (layout.layoutInput.text.text == editorState.text) {
                                        drawTextSpoilers(
                                            layoutResult = layout,
                                            spans = editorState.spans,
                                            unlockedSpoilerIds = editorState.unlockedSpoilerIds,
                                            ascentPx = fontMetrics.ascent,
                                            descentPx = fontMetrics.descent,
                                            lineMetrics = segmentLineMetrics
                                        )
                                    }
                                    if (!isEditorFocused || reading || !cursorVisible) return@drawWithContent
                                    if (layout.lineCount <= 0) return@drawWithContent
                                    val sel = editorState.textFieldValue.selection
                                    if (!sel.collapsed) return@drawWithContent

                                    val layoutTextLen = layout.layoutInput.text.text.length
                                    val cursorOffset = sel.start.coerceIn(0, layoutTextLen)
                                    val cursorRect = layout.getCursorRect(cursorOffset)
                                    val line = layout.getLineForOffset(cursorOffset).coerceIn(0, (layout.lineCount - 1).coerceAtLeast(0))
                                    val baseline = layout.getLineBaseline(line)
                                    val baseCursorSp = editorState.activeFontSizePx?.sp
                                        ?: editorState.activeHeadingLevel?.let { HeadingTypographySpec.fontSize(it) }
                                        ?: editorTextStyle.fontSize
                                    val targetCursorSp = if (editorState.isSuperscriptActive || editorState.isSubscriptActive) {
                                        (baseCursorSp.value * 0.72f).coerceAtLeast(8f).sp
                                    } else {
                                        baseCursorSp
                                    }
                                    val cursorSizePx = with(density) { targetCursorSp.toPx() }
                                    val activeTypeface = editorState.activeFontId?.let {
                                        com.likkho.app.editor.FontRegistry.resolveTypeface(context, it)
                                    } ?: (resolvedTypefaceState.value as? android.graphics.Typeface)
                                    val cursorFm = android.graphics.Paint().apply {
                                        typeface = activeTypeface
                                        textSize = cursorSizePx
                                    }.fontMetrics
                                    val cursorH = (cursorFm.descent - cursorFm.ascent).coerceAtLeast(cursorSizePx)
                                    val rawTop = baseline + cursorFm.ascent
                                    val top = if (line == 0 && rawTop < 0f) 0f else rawTop
                                    val cursorW = 2.dp.toPx()
                                    val x = cursorRect.left.coerceIn(0f, (size.width - cursorW).coerceAtLeast(0f))
                                    drawRect(
                                        color = w.accent,
                                        topLeft = androidx.compose.ui.geometry.Offset(x, top),
                                        size = androidx.compose.ui.geometry.Size(cursorW, cursorH)
                                    )
                                }
                        ) {
                            if (editorState.text.isEmpty()) {
                                Text(
                                    text = "Start writing...",
                                    modifier = Modifier.fillMaxWidth(),
                                    style = proseStyle(16.sp, w.muted.copy(alpha = 0.65f), italic = true).copy(
                                        textAlign = editorState.canvasAlignment.toComposeTextAlign(),
                                        textIndent = emptyPendingIndent
                                    )
                                )
                            }
                            inner()
                            val layout = textLayoutResult
                            if (layout != null &&
                                layout.lineCount > 0 &&
                                layout.layoutInput.text.text == editorState.text &&
                                editorState.spans.any { it.style is SpanStyle.Todo }
                            ) {
                                val layoutText = layout.layoutInput.text.text
                                val todoGeoms = remember(layout, editorState.spans, layoutText, density) {
                                    BlockDecorationLayoutCalculator.computeTodoCheckboxes(
                                        text = layoutText,
                                        spans = editorState.spans,
                                        lineCount = layout.lineCount,
                                        densityScale = density.density,
                                        getLineForOffset = { layout.getLineForOffset(it) },
                                        getLineTop = { layout.getLineTop(it) },
                                        getLineBottom = { layout.getLineBottom(it) },
                                        getCharLeft = { layout.getBoundingBox(it).left },
                                        getCharRight = { layout.getBoundingBox(it).right }
                                    )
                                }
                                todoGeoms.forEachIndexed { idx, geom ->
                                    key(geom.spanStart) {
                                        val hitW = with(density) { (geom.hitRight - geom.hitLeft).coerceAtLeast(24f).toDp() }
                                        val hitH = with(density) { (geom.hitBottom - geom.hitTop).coerceAtLeast(24f).toDp() }
                                        Box(
                                            modifier = Modifier
                                                .offset { IntOffset(geom.hitLeft.roundToInt(), geom.hitTop.roundToInt()) }
                                                .size(hitW, hitH)
                                                .semantics {
                                                    contentDescription = if (geom.checked) "Checked item" else "Unchecked item"
                                                }
                                                .testTag("canvas_checkbox_$idx")
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) {
                                                    if (editorState.toggleTodoAt(geom.spanStart) && reading) {
                                                        save(showFeedback = false)
                                                    }
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
                }
                }
                    // Layer 3: Foreground media
                    images.filter { !it.behindText }.forEach { im ->
                        key(im.id) {
                            DraggableImage(
                                im = im,
                                selected = selImg == im.id,
                                editable = !reading,
                                hScroll = hScroll,
                                vScroll = vScroll,
                                viewportWPx = viewportWPx,
                                viewportHPx = viewportHPx,
                                viewportRootOrigin = viewportRootOrigin,
                                loadImageBitmap = { id -> vm.loadDecryptedImageBitmap(id) },
                                onSelect = { selImg = im.id; selAud = null; selVid = null },
                                onDragProgress = { maxR, maxB -> activeDragBoundsDp = maxR to maxB },
                                onMove = { x, y ->
                                    activeDragBoundsDp = null
                                    images = images.map { m -> if (m.id == im.id) m.copy(x = x, y = y) else m }
                                }
                            )
                        }
                    }
                    videos.filter { !it.behindText }.forEach { v ->
                        key(v.id) {
                            VideoCard(
                                v = v,
                                selected = selVid == v.id,
                                editable = !reading,
                                hScroll = hScroll,
                                vScroll = vScroll,
                                viewportWPx = viewportWPx,
                                viewportHPx = viewportHPx,
                                viewportRootOrigin = viewportRootOrigin,
                                loadThumbnail = { id -> vm.loadVideoThumbnailBitmap(id) },
                                openMediaDataSource = { id -> vm.openDecryptedMediaDataSource(id) },
                                onStatus = { vm.showStatus(it, BannerKind.ERROR) },
                                onSelect = { selVid = v.id; selImg = null; selAud = null },
                                onDragProgress = { maxR, maxB -> activeDragBoundsDp = maxR to maxB },
                                onMove = { x, y ->
                                    activeDragBoundsDp = null
                                    videos = videos.map { vid -> if (vid.id == v.id) vid.copy(x = x, y = y) else vid }
                                }
                            )
                        }
                    }
                    audios.filter { !it.behindText }.forEach { a ->
                        key(a.id) {
                            AudioCard(
                                a = a,
                                selected = selAud == a.id,
                                editable = !reading,
                                hScroll = hScroll,
                                vScroll = vScroll,
                                viewportWPx = viewportWPx,
                                viewportHPx = viewportHPx,
                                viewportRootOrigin = viewportRootOrigin,
                                loadAudioBytes = { id -> vm.loadDecryptedMediaBytes(id) },
                                onStatus = { vm.showStatus(it, BannerKind.ERROR) },
                                onSelect = { selAud = a.id; selImg = null; selVid = null },
                                onDragProgress = { maxR, maxB -> activeDragBoundsDp = maxR to maxB },
                                onMove = { x, y ->
                                    activeDragBoundsDp = null
                                    audios = audios.map { aud -> if (aud.id == a.id) aud.copy(x = x, y = y) else aud }
                                }
                            )
                        }
                    }
                }
            }
        }

        // ---------- toolbar ----------
        if (!reading) RichToolbar(
            vm = vm, lockPin = lockPin, bgSrc = bgSrc, bgOpacity = bgOpacity, recording = recording,
            onRecordToggle = {
                if (!recording) { hooks.startVoiceRecording(); recording = true }
                else {
                    recording = false
                    hooks.stopVoiceRecording()?.let {
                        sessionImportedMediaIds.add(it.audioUri ?: it.id)
                        audios = audios + it
                        vm.showStatus("Voice recording saved to diary.")
                    }
                }
            },
            onAddImage = { imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onAddVideo = { videoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            onAddAudio = { audioPickerLauncher.launch(arrayOf("audio/*")) },
            onLock = { lockPin = it }, onBgOpacity = { bgOpacity = it },
            onPickBg = { bgPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemoveBg = { bgSrc = null }, onEditBg = { bgStudio = true },
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
        if (!reading) {
            val density = LocalDensity.current
            val isImeOpen = WindowInsets.ime.getBottom(density) > WindowInsets.navigationBars.getBottom(density)
            if (!isImeOpen) {
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showReminder) ReminderDialog(reminderAt, { showReminder = false }) { reminderAt = it }
    if (showPfp) PfpStudioDialog(
        current = pfp,
        hooks = hooks,
        onClose = { showPfp = false },
        onImportPhotoUri = { uri ->
            importPfpForSession(
                uri = uri,
                importPhoto = { vm.importMediaUri(it) },
                sessionImportedMediaIds = sessionImportedMediaIds,
                onFailure = { vm.showStatus("Could not import PFP image.", BannerKind.ERROR) }
            )
        },
        onSave = { pfp = it }
    )
    imgStudio?.let { id ->
        images.find { it.id == id }?.let { targetImg ->
            val previewBmp by produceState<ImageBitmap?>(null, targetImg.source) {
                value = targetImg.source.takeIf { it.isNotBlank() }?.let { vm.loadDecryptedImageBitmap(it) }
            }
            ImageStudioDialog(
                title = "Image Studio",
                sizeBytes = targetImg.sizeBytes,
                onClose = { imgStudio = null },
                previewBitmap = previewBmp,
                initialOpacity = targetImg.opacity,
                onApplyOpacity = { newOpacity ->
                    images = images.map { m -> if (m.id == targetImg.id) m.copy(opacity = newOpacity) else m }
                }
            ) {}
        } ?: run { imgStudio = null }
    }
    if (bgStudio) {
        val bgPreviewBmp by produceState<ImageBitmap?>(null, bgSrc) {
            value = bgSrc?.takeIf { it.isNotBlank() }?.let { vm.loadDecryptedImageBitmap(it) }
        }
        ImageStudioDialog(
            title = "Background Image Studio",
            sizeBytes = 0,
            onClose = { bgStudio = false },
            previewBitmap = bgPreviewBmp,
            initialOpacity = bgOpacity,
            onApplyOpacity = { newOpacity -> bgOpacity = newOpacity }
        ) {}
    }
    audStudio?.let { AudioStudioDialog(it.name, { audStudio = null }) { } }
    spoilerUnlockPrompt?.let { sp ->
        var passcodeInput by remember(sp.id) { mutableStateOf("") }
        var passcodeError by remember(sp.id) { mutableStateOf<String?>(null) }
        fun submitSpoilerUnlock() {
            if (editorState.unlockSpoiler(sp.id, passcodeInput)) {
                spoilerUnlockPrompt = null
                vm.showStatus("Spoiler unlocked. You can now read, copy, or remove it.")
            } else {
                passcodeError = "Incorrect spoiler passcode."
            }
        }
        WikiDialog(
            onDismiss = { spoilerUnlockPrompt = null },
            title = "Unlock Spoiler Text",
            titleIcon = Icons.Outlined.VisibilityOff,
            titleIconTint = w.accent,
            maxWidth = 320.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the spoiler passcode to reveal, copy, edit, or delete this hidden text.",
                    style = sansStyle(12.sp, w.muted, FontWeight.Normal)
                )
                passcodeError?.let { err ->
                    Text(err, style = sansStyle(12.sp, w.danger, FontWeight.Medium))
                }
                Column {
                    FieldLabel("Spoiler Passcode")
                    WikiTextField(
                        value = passcodeInput,
                        onValueChange = { passcodeInput = it; passcodeError = null },
                        modifier = Modifier.fillMaxWidth().testTag("spoiler_unlock_input"),
                        placeholder = "Enter passcode...",
                        password = true,
                        onDone = { submitSpoilerUnlock() }
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WikiButton("Cancel", { spoilerUnlockPrompt = null }, height = 36.dp)
                    Spacer(Modifier.width(8.dp))
                    WikiButton("Unlock", { submitSpoilerUnlock() }, kind = BtnKind.PRIMARY, height = 36.dp)
                }
            }
        }
    }
}

internal fun collectCanvasMediaIds(
    pfp: Pfp?,
    bgSrc: String?,
    images: List<CanvasImage>,
    audios: List<CanvasAudio>,
    videos: List<CanvasVideo>
): Set<String> {
    val ids = mutableSetOf<String>()
    pfp?.source?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
    bgSrc?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
    images.forEach { im ->
        (im.source.takeIf { it.isNotBlank() } ?: im.id.takeIf { it.isNotBlank() })?.let { ids.add(it) }
    }
    audios.forEach { a ->
        (a.audioUri?.takeIf { it.isNotBlank() } ?: a.id.takeIf { it.isNotBlank() })?.let { ids.add(it) }
    }
    videos.forEach { v ->
        (v.source.takeIf { it.isNotBlank() } ?: v.id.takeIf { it.isNotBlank() })?.let { ids.add(it) }
    }
    return ids
}

internal suspend fun <T> importPfpForSession(
    uri: T,
    importPhoto: suspend (T) -> String?,
    sessionImportedMediaIds: MutableCollection<String>,
    onFailure: () -> Unit = {}
): String? {
    val imported = importPhoto(uri)
    if (imported != null) {
        sessionImportedMediaIds.add(imported)
    } else {
        onFailure()
    }
    return imported
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
private fun DraggableImage(
    im: CanvasImage,
    selected: Boolean,
    editable: Boolean,
    hScroll: ScrollState,
    vScroll: ScrollState,
    viewportWPx: Float,
    viewportHPx: Float,
    viewportRootOrigin: Offset,
    loadImageBitmap: suspend (String) -> ImageBitmap?,
    onSelect: () -> Unit,
    onDragProgress: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val w = wiki()
    val density = LocalDensity.current
    var liveX by remember(im.id) { mutableStateOf(im.x) }
    var liveY by remember(im.id) { mutableStateOf(im.y) }
    var isDragging by remember(im.id) { mutableStateOf(false) }
    var fingerRootPos by remember(im.id) { mutableStateOf(Offset.Zero) }
    var itemCoords by remember(im.id) { mutableStateOf<LayoutCoordinates?>(null) }

    val currentW by rememberUpdatedState(im.width)
    val currentH by rememberUpdatedState(im.height)
    val currentViewportOrigin by rememberUpdatedState(viewportRootOrigin)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDragProgress by rememberUpdatedState(onDragProgress)
    val currentOnMove by rememberUpdatedState(onMove)

    LaunchedEffect(im.x, im.y) {
        if (!isDragging) {
            liveX = im.x
            liveY = im.y
        }
    }

    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        val edgeZonePx = with(density) { 44.dp.toPx() }
        val maxStepPx = with(density) { 12.dp.toPx() }
        while (isDragging) {
            val relX = fingerRootPos.x - currentViewportOrigin.x
            val relY = fingerRootPos.y - currentViewportOrigin.y
            var panPxX = 0f
            var panPxY = 0f
            if (relX > viewportWPx - edgeZonePx) {
                val ratio = ((relX - (viewportWPx - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxX = maxStepPx * (0.35f + ratio * 0.65f)
            } else if (relX < edgeZonePx && hScroll.value > 0) {
                val ratio = ((edgeZonePx - relX) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxX = -maxStepPx * (0.35f + ratio * 0.65f)
            }
            if (relY > viewportHPx - edgeZonePx) {
                val ratio = ((relY - (viewportHPx - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxY = maxStepPx * (0.35f + ratio * 0.65f)
            } else if (relY < edgeZonePx && vScroll.value > 0) {
                val ratio = ((edgeZonePx - relY) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxY = -maxStepPx * (0.35f + ratio * 0.65f)
            }
            if (panPxX != 0f || panPxY != 0f) {
                val nextX = (liveX + panPxX / density.density).coerceAtLeast(0f)
                val nextY = (liveY + panPxY / density.density).coerceAtLeast(0f)
                liveX = nextX
                liveY = nextY
                currentOnDragProgress(nextX + currentW, nextY + currentH)
                if (panPxX != 0f) hScroll.scrollBy(panPxX)
                if (panPxY != 0f) vScroll.scrollBy(panPxY)
            }
            delay(16L)
        }
    }

    val bitmap by produceState<ImageBitmap?>(null, im.source) {
        value = im.source.takeIf { it.isNotBlank() }?.let { loadImageBitmap(it) }
    }

    Box(
        Modifier
            .offset { IntOffset(with(this) { liveX.dp.roundToPx() }, with(this) { liveY.dp.roundToPx() }) }
            .size(im.width.dp, im.height.dp)
            .rotate(im.rotation)
            .onGloballyPositioned { itemCoords = it }
            .background(if (bitmap != null) Color.Transparent else w.surface)
            .wikiBorder(if (selected) w.accent else w.border, if (selected) 1.5.dp else 0.5.dp)
            .pointerInput(im.id, editable) {
                detectTapGestures(onTap = { if (editable) currentOnSelect() })
            }
            .pointerInput(im.id, editable) {
                if (editable) {
                    var prevFingerRoot = Offset.Zero
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val coords = itemCoords
                            if (coords != null && coords.isAttached) {
                                prevFingerRoot = coords.localToRoot(startOffset)
                                fingerRootPos = prevFingerRoot
                            }
                            isDragging = true
                            currentOnSelect()
                            currentOnDragProgress(liveX + currentW, liveY + currentH)
                        },
                        onDragEnd = {
                            isDragging = false
                            currentOnMove(liveX, liveY)
                        },
                        onDragCancel = {
                            isDragging = false
                            currentOnMove(liveX, liveY)
                        },
                    ) { change, _ ->
                        if (change.isConsumed) return@detectDragGestures
                        change.consume()
                        val coords = itemCoords
                        if (coords != null && coords.isAttached) {
                            val curRoot = coords.localToRoot(change.position)
                            val deltaRoot = curRoot - prevFingerRoot
                            prevFingerRoot = curRoot
                            fingerRootPos = curRoot
                            liveX = (liveX + deltaRoot.x / density.density).coerceAtLeast(0f)
                            liveY = (liveY + deltaRoot.y / density.density).coerceAtLeast(0f)
                            currentOnDragProgress(liveX + currentW, liveY + currentH)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = "Canvas image",
                contentScale = ContentScale.FillBounds,
                alpha = im.opacity,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Outlined.Image, contentDescription = "Image placeholder", tint = w.muted, modifier = Modifier.alpha(im.opacity))
        }
    }
}

private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= data.size) return -1
        val pos = position.toInt()
        val remaining = data.size - pos
        val count = minOf(size, remaining)
        System.arraycopy(data, pos, buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() {}
}

/** Opaque RED audio card #b32424. Play button before slider. Draggable infinitely right/bottom. */
@Composable
private fun AudioCard(
    a: CanvasAudio,
    selected: Boolean,
    editable: Boolean,
    hScroll: ScrollState,
    vScroll: ScrollState,
    viewportWPx: Float,
    viewportHPx: Float,
    viewportRootOrigin: Offset,
    loadAudioBytes: suspend (String) -> ByteArray?,
    onStatus: (String) -> Unit,
    onSelect: () -> Unit,
    onDragProgress: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var liveX by remember(a.id) { mutableStateOf(a.x) }
    var liveY by remember(a.id) { mutableStateOf(a.y) }
    var isDragging by remember(a.id) { mutableStateOf(false) }
    var fingerRootPos by remember(a.id) { mutableStateOf(Offset.Zero) }
    var itemCoords by remember(a.id) { mutableStateOf<LayoutCoordinates?>(null) }

    val currentW by rememberUpdatedState(a.width)
    val currentH by rememberUpdatedState(a.height)
    val currentViewportOrigin by rememberUpdatedState(viewportRootOrigin)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDragProgress by rememberUpdatedState(onDragProgress)
    val currentOnMove by rememberUpdatedState(onMove)

    LaunchedEffect(a.x, a.y) {
        if (!isDragging) {
            liveX = a.x
            liveY = a.y
        }
    }

    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        val edgeZonePx = with(density) { 44.dp.toPx() }
        val maxStepPx = with(density) { 12.dp.toPx() }
        while (isDragging) {
            val relX = fingerRootPos.x - currentViewportOrigin.x
            val relY = fingerRootPos.y - currentViewportOrigin.y
            var panPxX = 0f
            var panPxY = 0f
            if (relX > viewportWPx - edgeZonePx) {
                val ratio = ((relX - (viewportWPx - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxX = maxStepPx * (0.35f + ratio * 0.65f)
            } else if (relX < edgeZonePx && hScroll.value > 0) {
                val ratio = ((edgeZonePx - relX) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxX = -maxStepPx * (0.35f + ratio * 0.65f)
            }
            if (relY > viewportHPx - edgeZonePx) {
                val ratio = ((relY - (viewportHPx - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxY = maxStepPx * (0.35f + ratio * 0.65f)
            } else if (relY < edgeZonePx && vScroll.value > 0) {
                val ratio = ((edgeZonePx - relY) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxY = -maxStepPx * (0.35f + ratio * 0.65f)
            }
            if (panPxX != 0f || panPxY != 0f) {
                val nextX = (liveX + panPxX / density.density).coerceAtLeast(0f)
                val nextY = (liveY + panPxY / density.density).coerceAtLeast(0f)
                liveX = nextX
                liveY = nextY
                currentOnDragProgress(nextX + currentW, nextY + currentH)
                if (panPxX != 0f) hScroll.scrollBy(panPxX)
                if (panPxY != 0f) vScroll.scrollBy(panPxY)
            }
            delay(16L)
        }
    }

    var playing by remember(a.id) { mutableStateOf(false) }
    var loadingAudio by remember(a.id) { mutableStateOf(false) }
    var progress by remember(a.id) { mutableStateOf(0f) }
    var durationMs by remember(a.id) { mutableStateOf((a.durationSec * 1000).coerceAtLeast(1000)) }
    var mediaPlayer by remember(a.id) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(a.id) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
        }
    }

    LaunchedEffect(playing, a.playbackRate, mediaPlayer) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        if (playing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                mp.playbackParams = mp.playbackParams.setSpeed(a.playbackRate)
            }
        }
        while (playing) {
            val cur = runCatching { mp.currentPosition }.getOrDefault(0)
            val total = durationMs.coerceAtLeast(1)
            progress = (cur.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            delay(120L)
        }
    }

    fun togglePlay() {
        val existing = mediaPlayer
        if (existing != null) {
            if (playing) {
                runCatching { existing.pause() }
                playing = false
            } else {
                runCatching {
                    existing.start()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        existing.playbackParams = existing.playbackParams.setSpeed(a.playbackRate)
                    }
                    playing = true
                }.onFailure {
                    playing = false
                    onStatus("Unable to play audio.")
                }
            }
            return
        }
        if (loadingAudio) return
        loadingAudio = true
        scope.launch {
            val bytes = a.audioUri.takeIf { it.isNotBlank() }?.let { loadAudioBytes(it) }
            if (bytes == null || bytes.isEmpty()) {
                loadingAudio = false
                onStatus("Audio data unavailable.")
                return@launch
            }
            val preparedPlayer = withContext(Dispatchers.IO) {
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(ByteArrayMediaDataSource(bytes))
                        prepare()
                    }
                }.getOrNull()
            }
            loadingAudio = false
            if (preparedPlayer == null) {
                onStatus("Unsupported audio format.")
                return@launch
            }
            preparedPlayer.setOnCompletionListener {
                playing = false
                progress = 0f
                runCatching { it.seekTo(0) }
            }
            durationMs = preparedPlayer.duration.takeIf { it > 0 } ?: (a.durationSec * 1000).coerceAtLeast(1000)
            mediaPlayer = preparedPlayer
            runCatching {
                preparedPlayer.start()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    preparedPlayer.playbackParams = preparedPlayer.playbackParams.setSpeed(a.playbackRate)
                }
                playing = true
            }.onFailure {
                playing = false
                onStatus("Unable to play audio.")
            }
        }
    }

    Row(
        Modifier
            .offset { IntOffset(with(this) { liveX.dp.roundToPx() }, with(this) { liveY.dp.roundToPx() }) }
            .size(a.width.dp, a.height.dp)
            .rotate(a.rotation)
            .onGloballyPositioned { itemCoords = it }
            .background(Color(0xFFB32424))
            .wikiBorder(if (selected) wiki().accent else Color(0xFF7A1616), if (selected) 1.5.dp else 0.5.dp)
            .pointerInput(a.id, editable) {
                detectTapGestures(
                    onTap = { if (editable) currentOnSelect() },
                    onDoubleTap = { if (editable) currentOnSelect() }
                )
            }
            .pointerInput(a.id, editable) {
                if (editable) {
                    var prevFingerRoot = Offset.Zero
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val coords = itemCoords
                            if (coords != null && coords.isAttached) {
                                prevFingerRoot = coords.localToRoot(startOffset)
                                fingerRootPos = prevFingerRoot
                            }
                            isDragging = true
                            currentOnSelect()
                            currentOnDragProgress(liveX + currentW, liveY + currentH)
                        },
                        onDragEnd = {
                            isDragging = false
                            currentOnMove(liveX, liveY)
                        },
                        onDragCancel = {
                            isDragging = false
                            currentOnMove(liveX, liveY)
                        },
                    ) { change, _ ->
                        if (change.isConsumed) return@detectDragGestures
                        change.consume()
                        val coords = itemCoords
                        if (coords != null && coords.isAttached) {
                            val curRoot = coords.localToRoot(change.position)
                            val deltaRoot = curRoot - prevFingerRoot
                            prevFingerRoot = curRoot
                            fingerRootPos = curRoot
                            liveX = (liveX + deltaRoot.x / density.density).coerceAtLeast(0f)
                            liveY = (liveY + deltaRoot.y / density.density).coerceAtLeast(0f)
                            currentOnDragProgress(liveX + currentW, liveY + currentH)
                        }
                    }
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .background(Color(0xFF8E1B1B))
                .wikiBorder(Color.White.copy(alpha = 0.4f))
                .clickable { togglePlay() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = if (playing) "Pause audio" else "Play audio",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, style = sansStyle(10.sp, Color.White, FontWeight.Medium), maxLines = 1)
            androidx.compose.material3.Slider(
                value = progress,
                onValueChange = { fraction ->
                    progress = fraction
                    val targetMs = (fraction * durationMs).toInt()
                    runCatching { mediaPlayer?.seekTo(targetMs) }
                },
                modifier = Modifier.height(20.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
        }
        Text("${(durationMs / 1000).coerceAtLeast(1)}s", style = monoStyle(10.sp, Color.White))
    }
}

@Composable
private fun VideoCard(
    v: CanvasVideo,
    selected: Boolean,
    editable: Boolean,
    hScroll: ScrollState,
    vScroll: ScrollState,
    viewportWPx: Float,
    viewportHPx: Float,
    viewportRootOrigin: Offset,
    loadThumbnail: suspend (String) -> ImageBitmap?,
    openMediaDataSource: suspend (String) -> MediaDataSource?,
    onStatus: (String) -> Unit,
    onSelect: () -> Unit,
    onDragProgress: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val w = wiki()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var liveX by remember(v.id) { mutableStateOf(v.x) }
    var liveY by remember(v.id) { mutableStateOf(v.y) }
    var isDragging by remember(v.id) { mutableStateOf(false) }
    var fingerRootPos by remember(v.id) { mutableStateOf(Offset.Zero) }
    var itemCoords by remember(v.id) { mutableStateOf<LayoutCoordinates?>(null) }

    val currentW by rememberUpdatedState(v.width)
    val currentH by rememberUpdatedState(v.height)
    val currentViewportOrigin by rememberUpdatedState(viewportRootOrigin)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnDragProgress by rememberUpdatedState(onDragProgress)
    val currentOnMove by rememberUpdatedState(onMove)

    LaunchedEffect(v.x, v.y) {
        if (!isDragging) {
            liveX = v.x
            liveY = v.y
        }
    }

    LaunchedEffect(isDragging) {
        if (!isDragging) return@LaunchedEffect
        val edgeZonePx = with(density) { 44.dp.toPx() }
        val maxStepPx = with(density) { 12.dp.toPx() }
        while (isDragging) {
            val relX = fingerRootPos.x - currentViewportOrigin.x
            val relY = fingerRootPos.y - currentViewportOrigin.y
            var panPxX = 0f
            var panPxY = 0f
            if (relX > viewportWPx - edgeZonePx) {
                val ratio = ((relX - (viewportWPx - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxX = maxStepPx * (0.35f + ratio * 0.65f)
            } else if (relX < edgeZonePx && hScroll.value > 0) {
                val ratio = ((edgeZonePx - relX) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxX = -maxStepPx * (0.35f + ratio * 0.65f)
            }
            if (relY > viewportHPx - edgeZonePx) {
                val ratio = ((relY - (viewportHPx - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxY = maxStepPx * (0.35f + ratio * 0.65f)
            } else if (relY < edgeZonePx && vScroll.value > 0) {
                val ratio = ((edgeZonePx - relY) / edgeZonePx).coerceIn(0f, 1.5f)
                panPxY = -maxStepPx * (0.35f + ratio * 0.65f)
            }
            if (panPxX != 0f || panPxY != 0f) {
                val nextX = (liveX + panPxX / density.density).coerceAtLeast(0f)
                val nextY = (liveY + panPxY / density.density).coerceAtLeast(0f)
                liveX = nextX
                liveY = nextY
                currentOnDragProgress(nextX + currentW, nextY + currentH)
                if (panPxX != 0f) hScroll.scrollBy(panPxX)
                if (panPxY != 0f) vScroll.scrollBy(panPxY)
            }
            delay(16L)
        }
    }

    val thumbnail by produceState<ImageBitmap?>(null, v.source) {
        value = v.source.takeIf { it.isNotBlank() }?.let { loadThumbnail(it) }
    }

    var playing by remember(v.id) { mutableStateOf(false) }
    var hasRenderedFrame by remember(v.id) { mutableStateOf(false) }
    var loadingVideo by remember(v.id) { mutableStateOf(false) }
    var muted by remember(v.id) { mutableStateOf(false) }
    var progress by remember(v.id) { mutableStateOf(0f) }
    var durationMs by remember(v.id) { mutableStateOf((v.durationSec * 1000).coerceAtLeast(1000)) }
    var mediaPlayer by remember(v.id) { mutableStateOf<MediaPlayer?>(null) }
    var activeDataSource by remember(v.id) { mutableStateOf<MediaDataSource?>(null) }
    var videoSurface by remember(v.id) { mutableStateOf<Surface?>(null) }

    DisposableEffect(v.id) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            runCatching { activeDataSource?.close() }
            activeDataSource = null
            runCatching { videoSurface?.release() }
            videoSurface = null
        }
    }

    LaunchedEffect(playing, mediaPlayer) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        while (playing) {
            val cur = runCatching { mp.currentPosition }.getOrDefault(0)
            val total = durationMs.coerceAtLeast(1)
            progress = (cur.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            delay(120L)
        }
    }

    fun togglePlay() {
        if (editable) currentOnSelect()
        val existing = mediaPlayer
        if (existing != null) {
            if (playing) {
                runCatching { existing.pause() }
                playing = false
            } else {
                runCatching {
                    videoSurface?.let { existing.setSurface(it) }
                    val vol = if (muted) 0f else 1f
                    existing.setVolume(vol, vol)
                    existing.start()
                    hasRenderedFrame = true
                    playing = true
                }.onFailure {
                    playing = false
                    onStatus("Unable to play video.")
                }
            }
            return
        }
        if (loadingVideo) return
        loadingVideo = true
        scope.launch {
            val ds = v.source.takeIf { it.isNotBlank() }?.let { openMediaDataSource(it) }
            if (ds == null) {
                loadingVideo = false
                onStatus("Video data unavailable.")
                return@launch
            }
            val preparedPlayer = withContext(Dispatchers.IO) {
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(ds)
                        prepare()
                    }
                }.getOrNull()
            }
            loadingVideo = false
            if (preparedPlayer == null) {
                runCatching { ds.close() }
                onStatus("Unsupported video format.")
                return@launch
            }
            activeDataSource = ds
            preparedPlayer.setOnCompletionListener {
                playing = false
                progress = 0f
                runCatching { it.seekTo(0) }
            }
            durationMs = preparedPlayer.duration.takeIf { it > 0 } ?: (v.durationSec * 1000).coerceAtLeast(1000)
            val vol = if (muted) 0f else 1f
            preparedPlayer.setVolume(vol, vol)
            videoSurface?.let { preparedPlayer.setSurface(it) }
            mediaPlayer = preparedPlayer
            runCatching {
                preparedPlayer.start()
                hasRenderedFrame = true
                playing = true
            }.onFailure {
                playing = false
                onStatus("Unable to play video.")
            }
        }
    }

    Box(
        Modifier
            .offset { IntOffset(with(this) { liveX.dp.roundToPx() }, with(this) { liveY.dp.roundToPx() }) }
            .size(v.width.dp, v.height.dp)
            .rotate(v.rotation)
            .clipToBounds()
            .onGloballyPositioned { itemCoords = it }
            .background(Color(0xFF14161A))
            .wikiBorder(if (selected) w.accent else w.border, if (selected) 1.5.dp else 0.5.dp)
            .pointerInput(v.id, editable) {
                detectTapGestures(
                    onTap = { if (editable) currentOnSelect() else togglePlay() },
                    onDoubleTap = { if (editable) currentOnSelect() }
                )
            }
            .pointerInput(v.id, editable) {
                if (editable) {
                    var prevFingerRoot = Offset.Zero
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val coords = itemCoords
                            if (coords != null && coords.isAttached) {
                                prevFingerRoot = coords.localToRoot(startOffset)
                                fingerRootPos = prevFingerRoot
                            }
                            isDragging = true
                            currentOnSelect()
                            currentOnDragProgress(liveX + currentW, liveY + currentH)
                        },
                        onDragEnd = {
                            isDragging = false
                            currentOnMove(liveX, liveY)
                        },
                        onDragCancel = {
                            isDragging = false
                            currentOnMove(liveX, liveY)
                        },
                    ) { change, _ ->
                        if (change.isConsumed) return@detectDragGestures
                        change.consume()
                        val coords = itemCoords
                        if (coords != null && coords.isAttached) {
                            val curRoot = coords.localToRoot(change.position)
                            val deltaRoot = curRoot - prevFingerRoot
                            prevFingerRoot = curRoot
                            fingerRootPos = curRoot
                            liveX = (liveX + deltaRoot.x / density.density).coerceAtLeast(0f)
                            liveY = (liveY + deltaRoot.y / density.density).coerceAtLeast(0f)
                            currentOnDragProgress(liveX + currentW, liveY + currentH)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            val newSurface = Surface(surface)
                            videoSurface = newSurface
                            runCatching { mediaPlayer?.setSurface(newSurface) }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            runCatching { mediaPlayer?.setSurface(null) }
                            runCatching { videoSurface?.release() }
                            videoSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!hasRenderedFrame) {
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = v.name,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Outlined.VideoFile, contentDescription = v.name, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(36.dp))
            }
        }

        if (!playing) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .wikiBorder(Color.White.copy(alpha = 0.5f))
                    .clickable { togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clickable { togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "Pause video" else "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
            androidx.compose.material3.Slider(
                value = progress,
                onValueChange = { fraction ->
                    progress = fraction
                    val targetMs = (fraction * durationMs).toInt()
                    runCatching { mediaPlayer?.seekTo(targetMs) }
                },
                modifier = Modifier.weight(1f).height(20.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                )
            )
            Text("${(durationMs / 1000).coerceAtLeast(1)}s", style = monoStyle(9.sp, Color.White))
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(26.dp)
                    .clickable {
                        muted = !muted
                        val vol = if (muted) 0f else 1f
                        runCatching { mediaPlayer?.setVolume(vol, vol) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = if (muted) "Unmute video" else "Mute video",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}
