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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.likkho.app.editor.BlockDecorationLayoutCalculator
import com.likkho.app.editor.HeadingTypographySpec
import com.likkho.app.editor.HighlightLayoutCalculator
import com.likkho.app.editor.RichTextEditorState
import com.likkho.app.editor.RichTextVisualTransformation
import com.likkho.app.editor.computeBlockDecorationCache
import com.likkho.app.editor.computeCachedHighlightRects
import com.likkho.app.editor.drawCachedBlockDecorations
import com.likkho.app.editor.drawCachedTextHighlights
import com.likkho.app.editor.drawCachedTextSpoilers
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
    val editorState = remember(initial?.id, initial?.isContentLoaded) { RichTextEditorState.fromHtml(initial?.contentHtml.orEmpty()) }
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

    val initialMediaIds = remember {
        collectCanvasMediaIds(pfp, bgSrc, images, audios, videos)
    }
    var baselineLog by remember { mutableStateOf(initial) }
    var baselineHtml by remember(initial?.id, initial?.isContentLoaded) { mutableStateOf(editorState.toHtml()) }
    var hasSavedDiary by remember { mutableStateOf(initial != null) }
    var lastSavedMediaIds by remember { mutableStateOf(initialMediaIds) }
    val sessionImportedMediaIds = remember { mutableStateListOf<String>() }

    var importingItem by remember { mutableStateOf<ImportingItem?>(null) }
    var showImportSkeleton by remember { mutableStateOf(false) }
    var showImportCancelBackDialog by remember { mutableStateOf(false) }
    val activeCancelFlag = remember { java.util.concurrent.atomic.AtomicReference<java.util.concurrent.atomic.AtomicBoolean?>(null) }
    val activeImportToken = remember { java.util.concurrent.atomic.AtomicLong(0L) }
    var runningImportToken by remember { mutableStateOf(0L) }
    val workspaceContext = androidx.compose.ui.platform.LocalContext.current
    val textToolbar = LocalTextToolbar.current

    fun cleanupUnsavedSessionMedia() {
        cleanupAbandonedSessionMedia(
            sessionImportedMediaIds = sessionImportedMediaIds,
            savedMediaIds = lastSavedMediaIds,
            onCleanup = { discarded -> vm.cleanupUnusedSessionMedia(discarded) }
        )
    }
    val currentCleanupUnsavedSessionMedia by rememberUpdatedState { cleanupUnsavedSessionMedia() }

    LaunchedEffect(reading) {
        if (reading) textToolbar.hide()
    }

    DisposableEffect(textToolbar) {
        onDispose { textToolbar.hide() }
    }

    fun cancelActiveImport(showBanner: Boolean = false) {
        activeCancelFlag.get()?.set(true)
        activeImportToken.incrementAndGet()
        runningImportToken = 0L
        importingItem = null
        showImportSkeleton = false
        if (showBanner) {
            vm.showStatus("Import cancelled", BannerKind.INFO)
        }
    }

    LaunchedEffect(runningImportToken, importingItem != null) {
        if (importingItem != null && runningImportToken != 0L) {
            showImportSkeleton = false
            delay(IMPORT_SKELETON_DELAY_MS)
            if (importingItem != null && activeImportToken.get() == runningImportToken) {
                showImportSkeleton = true
            }
        } else {
            showImportSkeleton = false
            showImportCancelBackDialog = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeCancelFlag.get()?.set(true)
            activeImportToken.incrementAndGet()
            currentCleanupUnsavedSessionMedia()
        }
    }

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

    fun <R> launchSingleCanvasImport(
        kind: ImportingMediaKind,
        defaultName: String,
        defaultWidth: Float,
        defaultHeight: Float,
        uri: android.net.Uri,
        failMessage: String,
        extractMediaId: (R) -> String?,
        performImport: suspend (
            sx: Float,
            sy: Float,
            isCancelled: () -> Boolean,
            onPhase: (ImportPhase) -> Unit,
            onProgress: (Long, Long) -> Unit
        ) -> R?,
        onImported: (R) -> Unit,
    ) {
        if (importingItem != null) {
            vm.showStatus("Ek import chal raha hai", BannerKind.INFO)
            return
        }
        val (displayName, reportedSize) = queryUriDisplayAndSize(workspaceContext, uri, defaultName)
        val (sx, sy) = currentSpawnCoords()
        val token = activeImportToken.incrementAndGet()
        val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val lastUiProgressMs = java.util.concurrent.atomic.AtomicLong(0L)
        activeCancelFlag.set(cancelFlag)
        runningImportToken = token
        importingItem = ImportingItem(
            kind = kind,
            name = displayName,
            x = sx,
            y = sy,
            width = defaultWidth,
            height = defaultHeight,
            phase = ImportPhase.PREPARING,
            bytesDone = 0L,
            bytesTotal = reportedSize,
        )

        val onPhase: (ImportPhase) -> Unit = { newPhase ->
            scope.launch {
                if (activeImportToken.get() == token && !cancelFlag.get()) {
                    importingItem = importingItem?.let { cur ->
                        val monotonicPhase = if (cur.phase == ImportPhase.VERIFYING && newPhase == ImportPhase.ENCRYPTING) {
                            ImportPhase.VERIFYING
                        } else newPhase
                        cur.copy(phase = monotonicPhase)
                    }
                }
            }
        }
        val onProgress: (Long, Long) -> Unit = { bytesDone, bytesTotal ->
            val nowMs = System.nanoTime() / 1_000_000L
            val prevMs = lastUiProgressMs.get()
            if (prevMs == 0L || nowMs - prevMs >= 90L) {
                lastUiProgressMs.set(nowMs)
                scope.launch {
                    if (activeImportToken.get() == token && !cancelFlag.get()) {
                        importingItem = importingItem?.let { cur ->
                            if (cur.phase == ImportPhase.VERIFYING) cur
                            else cur.copy(
                                phase = ImportPhase.ENCRYPTING,
                                bytesDone = bytesDone,
                                bytesTotal = if (bytesTotal > 0L) bytesTotal else cur.bytesTotal
                            )
                        }
                    }
                }
            }
        }

        scope.launch {
            try {
                val result = performImport(sx, sy, { cancelFlag.get() }, onPhase, onProgress)
                handleCompletedImportOutcome(
                    result = result,
                    cancelled = cancelFlag.get() || activeImportToken.get() != token,
                    failMessage = failMessage,
                    extractMediaId = extractMediaId,
                    onImported = onImported,
                    onCleanupCancelledMedia = { orphanId ->
                        vm.cleanupUnusedSessionMedia(setOf(orphanId))
                    },
                    onFailStatus = { msg -> vm.showStatus(msg, BannerKind.ERROR) }
                )
            } catch (_: ImportCancelledException) {
                if (activeImportToken.get() == token) {
                    vm.showStatus("Import cancelled", BannerKind.INFO)
                }
            } catch (_: Throwable) {
                if (activeImportToken.get() == token) {
                    if (cancelFlag.get()) {
                        vm.showStatus("Import cancelled", BannerKind.INFO)
                    } else {
                        vm.showStatus(failMessage, BannerKind.ERROR)
                    }
                }
            } finally {
                activeCancelFlag.compareAndSet(cancelFlag, null)
                if (activeImportToken.get() == token) {
                    runningImportToken = 0L
                    importingItem = null
                    showImportSkeleton = false
                }
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            launchSingleCanvasImport(
                kind = ImportingMediaKind.IMAGE,
                defaultName = "Image",
                defaultWidth = 160f,
                defaultHeight = 120f,
                uri = uri,
                failMessage = "Could not import image.",
                extractMediaId = { it.id },
                performImport = { sx, sy, isCancelled, onPhase, onProgress ->
                    vm.importImageUri(uri, sx, sy, isCancelled, onPhase, onProgress)
                },
                onImported = { imported ->
                    sessionImportedMediaIds.add(imported.id)
                    images = images + imported
                    selImg = imported.id
                    selAud = null
                    selVid = null
                }
            )
        }
    }
    val videoPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            launchSingleCanvasImport(
                kind = ImportingMediaKind.VIDEO,
                defaultName = "Video",
                defaultWidth = 240f,
                defaultHeight = 150f,
                uri = uri,
                failMessage = "Could not import video.",
                extractMediaId = { it.source.ifBlank { it.id } },
                performImport = { sx, sy, isCancelled, onPhase, onProgress ->
                    vm.importVideoUri(uri, sx, sy, isCancelled, onPhase, onProgress)
                },
                onImported = { imported ->
                    sessionImportedMediaIds.add(imported.id)
                    videos = videos + imported
                    selVid = imported.id
                    selImg = null
                    selAud = null
                }
            )
        }
    }
    val audioPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            launchSingleCanvasImport(
                kind = ImportingMediaKind.AUDIO,
                defaultName = "Audio",
                defaultWidth = 270f,
                defaultHeight = 56f,
                uri = uri,
                failMessage = "Could not import audio.",
                extractMediaId = { it.audioUri ?: it.id },
                performImport = { sx, sy, isCancelled, onPhase, onProgress ->
                    vm.importAudioUri(uri, sx, sy, isCancelled, onPhase, onProgress)
                },
                onImported = { imported ->
                    sessionImportedMediaIds.add(imported.audioUri ?: imported.id)
                    audios = audios + imported
                    selAud = imported.id
                    selImg = null
                    selVid = null
                }
            )
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

    fun save(showFeedback: Boolean = true, exitAfterSave: Boolean = false) {
        val now = System.currentTimeMillis()
        val base = baselineLog ?: DiaryLog(id = "log_$now", heading = "", createdAt = now)
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
        hasSavedDiary = true
        baselineLog = log
        baselineHtml = htmlContent
        lastSavedMediaIds = currentMediaIds
        sessionImportedMediaIds.clear()
        vm.upsertLog(log, exitAfterSave, obsoleteMediaIds) { persistResult ->
            persistResult.onSuccess {
                hooks.scheduleReminder(log, reminderAt) { remRes ->
                    remRes.onFailure { err ->
                        scope.launch {
                            val st = storageFailureBanner("Reminder update failed", err)
                            vm.showStatus(st.message, st.kind, 4000)
                        }
                    }
                }
                if (showFeedback && !exitAfterSave) {
                    saved = true
                    scope.launch { delay(1500); saved = false }
                }
            }.onFailure {
                saved = false
            }
        }
    }

    fun exitWorkspace() {
        textToolbar.hide()
        cleanupUnsavedSessionMedia()
        vm.navigateTo(Route.HOME)
    }

    fun requestExitWorkspace() {
        if (importingItem != null) {
            showImportCancelBackDialog = true
            return
        }
        when (
            evaluateWorkspaceExitAction(
                isReading = reading,
                baseline = baselineLog,
                baselineHtml = baselineHtml,
                currentHeading = heading,
                currentContentHtml = editorState.toHtml(),
                currentPlainText = editorState.text,
                hasDividerSpan = editorState.spans.any { it.style == SpanStyle.Divider },
                pfp = pfp,
                bgSrc = bgSrc,
                bgOpacity = bgOpacity,
                images = images,
                audios = audios,
                videos = videos,
                reminderAt = reminderAt,
                lockPin = lockPin,
            )
        ) {
            WorkspaceExitAction.AUTO_SAVE_AND_EXIT -> {
                textToolbar.hide()
                save(showFeedback = false, exitAfterSave = true)
            }
            WorkspaceExitAction.NO_SAVE_EXIT -> exitWorkspace()
        }
    }

    BackHandler {
        if (selImg != null || selAud != null || selVid != null) {
            selImg = null; selAud = null; selVid = null
        } else {
            requestExitWorkspace()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(w.bg)
            .windowInsetsPadding(
                WindowInsets.navigationBars
                    .add(WindowInsets(bottom = if (reading) 0.dp else 8.dp))
                    .union(WindowInsets.ime)
            )
    ) {
        // ---------- header ----------
        Column(Modifier.fillMaxWidth().background(w.surface)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    "Back to Homepage",
                    { requestExitWorkspace() },
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
                else WikiButton(
                    text = if (saved) "Saved" else "Save",
                    onClick = { save() },
                    modifier = Modifier.testTag("workspace_save_button"),
                    kind = if (saved) BtnKind.SUCCESS else BtnKind.PRIMARY,
                    icon = if (saved) Icons.Outlined.Check else Icons.Outlined.Save,
                    enabled = canSaveDiary(importingItem),
                    height = 40.dp
                )
            }
            if (importingItem != null && !reading) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Media import chal raha hai",
                        style = sansStyle(10.sp, w.muted, FontWeight.Medium)
                    )
                }
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
        val keyLifecycle = LocalBitmapKeyLifecycle.current
        DisposableEffect(bgSrc) {
            val k = bgSrc?.takeIf { it.isNotBlank() }
            if (k != null) keyLifecycle?.acquire(k)
            onDispose { if (k != null) keyLifecycle?.release(k) }
        }
        val bgBitmap by produceState<ImageBitmap?>(null, bgSrc) {
            value = bgSrc?.takeIf { it.isNotBlank() }?.let { vm.loadDecryptedImageBitmap(it) }
        }
        var writingBlockHeightPx by remember { mutableIntStateOf(0) }
        var activeDragBoundsDp by remember { mutableStateOf<Pair<Float, Float>?>(null) }
        var viewportWidthPx by remember { mutableIntStateOf(0) }
        val viewportHeightPxState = remember { mutableIntStateOf(0) }
        val viewportRootOriginRef = remember { object { var origin = Offset.Zero } }
        val editorFocusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
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

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(w.bg)
                .onSizeChanged { sz ->
                    if (sz.width != viewportWidthPx) viewportWidthPx = sz.width
                    if (sz.height != viewportHeightPxState.intValue) viewportHeightPxState.intValue = sz.height
                }
                .onGloballyPositioned { coords ->
                    viewportRootOriginRef.origin = coords.positionInRoot()
                }
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

            if (viewportWidthPx > 0) {
                val density = LocalDensity.current
                val viewportW = with(density) { viewportWidthPx.toDp() }
                val writingHeightDp = with(density) { writingBlockHeightPx.toDp() }

                val skeletonRightDp = if (showImportSkeleton) importingItem?.let { it.x + it.width } ?: 0f else 0f
                val skeletonBottomDp = if (showImportSkeleton) importingItem?.let { it.y + it.height } ?: 0f else 0f
                val mediaMaxRightDp = maxOf(
                    images.maxOfOrNull { it.x + it.width } ?: 0f,
                    audios.maxOfOrNull { it.x + it.width } ?: 0f,
                    videos.maxOfOrNull { it.x + it.width } ?: 0f,
                    activeDragBoundsDp?.first ?: 0f,
                    skeletonRightDp
                )
                val mediaMaxBottomDp = maxOf(
                    images.maxOfOrNull { it.y + it.height } ?: 0f,
                    audios.maxOfOrNull { it.y + it.height } ?: 0f,
                    videos.maxOfOrNull { it.y + it.height } ?: 0f,
                    activeDragBoundsDp?.second ?: 0f,
                    skeletonBottomDp
                )
                val needExtraWidth = mediaMaxRightDp > viewportW.value - 32f || activeDragBoundsDp != null
                val canHorizontalScroll by remember(needExtraWidth) {
                    derivedStateOf { needExtraWidth || hScroll.value > 0 }
                }
                val contentNeedW = if (canHorizontalScroll) {
                    maxOf(viewportW, (mediaMaxRightDp + 240f).dp)
                } else {
                    viewportW
                }
                val contentNeedWPx = with(density) { contentNeedW.roundToPx() }
                val contentNeedHPx = with(density) {
                    maxOf(writingHeightDp + 120.dp, (mediaMaxBottomDp + 240f).dp).roundToPx()
                }
                val getViewportSizePx: () -> Pair<Float, Float> = remember {
                    { viewportWidthPx.toFloat() to viewportHeightPxState.intValue.toFloat() }
                }
                val getViewportRootOrigin: () -> Offset = remember {
                    { viewportRootOriginRef.origin }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .horizontalScroll(hScroll, enabled = canHorizontalScroll)
                        .verticalScroll(vScroll)
                ) {
                    Box(
                        Modifier.layout { measurable, constraints ->
                            val curVpH = viewportHeightPxState.intValue
                            val wPx = maxOf(viewportWidthPx, contentNeedWPx)
                                .coerceIn(constraints.minWidth, constraints.maxWidth)
                            val hPx = maxOf(curVpH, contentNeedHPx)
                                .coerceIn(constraints.minHeight, constraints.maxHeight)
                            val placeable = measurable.measure(Constraints.fixed(wPx, hPx))
                            layout(wPx, hPx) {
                                placeable.placeRelative(0, 0)
                            }
                        }
                    ) {
                        // Layer 0: Unconsumed canvas tap focuses editor & reveals IME smoothly
                        if (!reading) {
                            Box(
                                Modifier
                                    .matchParentSize()
                                    .pointerInput(editorState) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = true)
                                            val downPos = down.position
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                if ((change.position - downPos).getDistance() > viewConfiguration.touchSlop) break
                                                if (change.changedToUpIgnoreConsumed()) {
                                                    if (!change.isConsumed) {
                                                        change.consume()
                                                        selImg = null
                                                        selAud = null
                                                        selVid = null
                                                        editorFocusRequester.requestFocus()
                                                        keyboardController?.show()
                                                    }
                                                    break
                                                }
                                            }
                                        }
                                    }
                            )
                        }

                        // Layer 1: Behind-text media
                        images.filter { it.behindText }.forEach { im ->
                            key(im.id) {
                                DraggableImage(
                                    im = im,
                                    selected = selImg == im.id,
                                    editable = !reading,
                                    hScroll = hScroll,
                                    vScroll = vScroll,
                                    getViewportSizePx = getViewportSizePx,
                                    getViewportRootOrigin = getViewportRootOrigin,
                                    loadImageBitmap = { id -> vm.loadDecryptedImageBitmap(id) },
                                    onSelect = { selImg = im.id; selAud = null; selVid = null },
                                    onDragProgress = { maxR, maxB ->
                                        val expanded = computeExpandedDragBounds(
                                            itemRightDp = maxR,
                                            itemBottomDp = maxB,
                                            currentCanvasWidthDp = contentNeedW.value,
                                            currentCanvasHeightDp = with(density) { maxOf(viewportHeightPxState.intValue, contentNeedHPx).toDp().value },
                                            currentBounds = activeDragBoundsDp
                                        )
                                        if (expanded != activeDragBoundsDp) activeDragBoundsDp = expanded
                                    },
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
                                    getViewportSizePx = getViewportSizePx,
                                    getViewportRootOrigin = getViewportRootOrigin,
                                    loadThumbnail = { id -> vm.loadVideoThumbnailBitmap(id) },
                                    openMediaDataSource = { id -> vm.openDecryptedMediaDataSource(id) },
                                    onStatus = { vm.showStatus(it, BannerKind.ERROR) },
                                    onSelect = { selVid = v.id; selImg = null; selAud = null },
                                    onDragProgress = { maxR, maxB ->
                                        val expanded = computeExpandedDragBounds(
                                            itemRightDp = maxR,
                                            itemBottomDp = maxB,
                                            currentCanvasWidthDp = contentNeedW.value,
                                            currentCanvasHeightDp = with(density) { maxOf(viewportHeightPxState.intValue, contentNeedHPx).toDp().value },
                                            currentBounds = activeDragBoundsDp
                                        )
                                        if (expanded != activeDragBoundsDp) activeDragBoundsDp = expanded
                                    },
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
                                    getViewportSizePx = getViewportSizePx,
                                    getViewportRootOrigin = getViewportRootOrigin,
                                    openMediaDataSource = { id -> vm.openDecryptedMediaDataSource(id) },
                                    onStatus = { vm.showStatus(it, BannerKind.ERROR) },
                                    onSelect = { selAud = a.id; selImg = null; selVid = null },
                                    onDragProgress = { maxR, maxB ->
                                        val expanded = computeExpandedDragBounds(
                                            itemRightDp = maxR,
                                            itemBottomDp = maxB,
                                            currentCanvasWidthDp = contentNeedW.value,
                                            currentCanvasHeightDp = with(density) { maxOf(viewportHeightPxState.intValue, contentNeedHPx).toDp().value },
                                            currentBounds = activeDragBoundsDp
                                        )
                                        if (expanded != activeDragBoundsDp) activeDragBoundsDp = expanded
                                    },
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
                                .graphicsLayer()
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
                            val resolvedTypefaceState = remember(editorTextStyle, fontFamilyResolver) {
                                fontFamilyResolver.resolve(
                                    fontFamily = editorTextStyle.fontFamily,
                                    fontWeight = editorTextStyle.fontWeight ?: FontWeight.Normal,
                                    fontStyle = editorTextStyle.fontStyle ?: FontStyle.Normal,
                                    fontSynthesis = editorTextStyle.fontSynthesis ?: FontSynthesis.All,
                                )
                            }
                            val reusableMeasurePaint = remember { android.graphics.Paint() }
                            val reusableDevanagariPaint = remember {
                                android.graphics.Paint().apply { typeface = android.graphics.Typeface.DEFAULT }
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
                            val currentSpans = editorState.spans
                            val currentText = editorState.text
                            val metricRelevantSpans = remember(currentSpans) {
                                currentSpans.filter {
                                    when (it.style) {
                                        is SpanStyle.Heading,
                                        is SpanStyle.FontId,
                                        is SpanStyle.SizePx,
                                        is SpanStyle.Quote,
                                        is SpanStyle.Code,
                                        is SpanStyle.Superscript,
                                        is SpanStyle.Subscript -> true
                                        else -> false
                                    }
                                }
                            }
                            val segmentLineMetrics: (Int, Int) -> Pair<Float, Float> = remember(
                                metricRelevantSpans,
                                currentText,
                                density,
                                fontMetrics,
                                headingFontMetrics,
                                resolvedTypefaceState.value,
                                context,
                                vm.customFonts.size
                            ) {
                                { segStart, segEnd ->
                                    var bestLevel: Int? = null
                                    var activeFont: String? = null
                                    var customSizePx: Float? = null
                                    var hasQuote = false
                                    var hasCode = false
                                    var isScript = false
                                    for (sp in metricRelevantSpans) {
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
                                            reusableMeasurePaint.typeface = resolvedTf
                                            reusableMeasurePaint.textSize = pxSize
                                            reusableMeasurePaint.fontMetrics
                                        }
                                        bestLevel != null && !isScript -> headingFontMetrics[bestLevel] ?: fontMetrics
                                        isScript -> {
                                            reusableMeasurePaint.typeface = resolvedTypefaceState.value as? android.graphics.Typeface
                                            reusableMeasurePaint.textSize = pxSize
                                            reusableMeasurePaint.fontMetrics
                                        }
                                        else -> fontMetrics
                                    }
                                    var hasDevanagari = false
                                    val clampedEnd = segEnd.coerceAtMost(currentText.length)
                                    for (idx in segStart until clampedEnd) {
                                        if (currentText[idx] in '\u0900'..'\u097F') {
                                            hasDevanagari = true
                                            break
                                        }
                                    }
                                    if (hasDevanagari) {
                                        reusableDevanagariPaint.textSize = pxSize
                                        val devFm = reusableDevanagariPaint.fontMetrics
                                        minOf(fm.ascent, devFm.ascent) to maxOf(fm.descent, devFm.descent)
                                    } else {
                                        fm.ascent to fm.descent
                                    }
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
                                    .heightIn(min = 360.dp)
                                    .focusRequester(editorFocusRequester)
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
                                    val quoteBgColor = w.accent.copy(alpha = if (w.isDark) 0.11f else 0.055f)
                                    val quoteBarColor = w.accent
                                    val codeBgColor = w.surface
                                    val codeBorderColor = w.border.copy(alpha = 0.55f)
                                    val dividerColor = w.border
                                    val bulletColor = w.text.copy(alpha = 0.85f)
                                    val todoUncheckedBorderColor = w.text.copy(alpha = 0.58f)
                                    val todoUncheckedBgColor = w.surface
                                    val todoCheckedFillColor = w.accent
                                    Box(
                                        modifier = Modifier.drawWithCache {
                                            val layout = textLayoutResult
                                            val docText = editorState.text
                                            val docSpans = editorState.spans
                                            val unlockedSpoilers = editorState.unlockedSpoilerIds
                                            val headingLevel = editorState.activeHeadingLevel
                                            val headingCursorOffset = if (headingLevel != null) editorState.selection.start else 0
                                            val layoutValid = layout != null && layout.layoutInput.text.text == docText
                                            val cachedBlocks = if (layoutValid) {
                                                computeBlockDecorationCache(
                                                    layoutResult = layout!!,
                                                    spans = docSpans,
                                                    canvasWidth = size.width,
                                                    densityScale = this.density,
                                                    activeHeadingLevel = headingLevel,
                                                    cursorOffset = headingCursorOffset
                                                )
                                            } else null
                                            val cachedHighlights = if (layoutValid) {
                                                computeCachedHighlightRects(
                                                    layoutResult = layout!!,
                                                    spans = docSpans,
                                                    ascentPx = fontMetrics.ascent,
                                                    descentPx = fontMetrics.descent,
                                                    lineMetrics = segmentLineMetrics
                                                )
                                            } else emptyList()
                                            val cachedSpoilers = if (layoutValid) {
                                                HighlightLayoutCalculator.computeSpoilerRects(
                                                    layoutResult = layout!!,
                                                    spans = docSpans,
                                                    unlockedSpoilerIds = unlockedSpoilers,
                                                    ascentPx = fontMetrics.ascent,
                                                    descentPx = fontMetrics.descent,
                                                    lineMetrics = segmentLineMetrics
                                                )
                                            } else emptyList()

                                            onDrawWithContent {
                                                if (cachedBlocks != null) {
                                                    drawCachedBlockDecorations(
                                                        cache = cachedBlocks,
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
                                                drawCachedTextHighlights(cachedHighlights)
                                                drawContent()
                                                drawCachedTextSpoilers(cachedSpoilers)
                                            }
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
                                        Box(
                                            Modifier
                                                .matchParentSize()
                                                .drawBehind {
                                                    if (!isEditorFocused || reading || !cursorVisible) return@drawBehind
                                                    val layout = textLayoutResult ?: return@drawBehind
                                                    if (layout.lineCount <= 0) return@drawBehind
                                                    val sel = editorState.textFieldValue.selection
                                                    if (!sel.collapsed) return@drawBehind

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
                                                    reusableMeasurePaint.typeface = activeTypeface
                                                    reusableMeasurePaint.textSize = cursorSizePx
                                                    val cursorFm = reusableMeasurePaint.fontMetrics
                                                    val cursorH = (cursorFm.descent - cursorFm.ascent).coerceAtLeast(cursorSizePx)
                                                    val rawTop = baseline + cursorFm.ascent
                                                    val top = if (line == 0 && rawTop < 0f) 0f else rawTop
                                                    val cursorW = 2.dp.toPx()
                                                    val x = cursorRect.left.coerceIn(0f, (size.width - cursorW).coerceAtLeast(0f))
                                                    drawRect(
                                                        color = w.accent,
                                                        topLeft = Offset(x, top),
                                                        size = androidx.compose.ui.geometry.Size(cursorW, cursorH)
                                                    )
                                                }
                                        )
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
                                    getViewportSizePx = getViewportSizePx,
                                    getViewportRootOrigin = getViewportRootOrigin,
                                    loadImageBitmap = { id -> vm.loadDecryptedImageBitmap(id) },
                                    onSelect = { selImg = im.id; selAud = null; selVid = null },
                                    onDragProgress = { maxR, maxB ->
                                        val expanded = computeExpandedDragBounds(
                                            itemRightDp = maxR,
                                            itemBottomDp = maxB,
                                            currentCanvasWidthDp = contentNeedW.value,
                                            currentCanvasHeightDp = with(density) { maxOf(viewportHeightPxState.intValue, contentNeedHPx).toDp().value },
                                            currentBounds = activeDragBoundsDp
                                        )
                                        if (expanded != activeDragBoundsDp) activeDragBoundsDp = expanded
                                    },
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
                                    getViewportSizePx = getViewportSizePx,
                                    getViewportRootOrigin = getViewportRootOrigin,
                                    loadThumbnail = { id -> vm.loadVideoThumbnailBitmap(id) },
                                    openMediaDataSource = { id -> vm.openDecryptedMediaDataSource(id) },
                                    onStatus = { vm.showStatus(it, BannerKind.ERROR) },
                                    onSelect = { selVid = v.id; selImg = null; selAud = null },
                                    onDragProgress = { maxR, maxB ->
                                        val expanded = computeExpandedDragBounds(
                                            itemRightDp = maxR,
                                            itemBottomDp = maxB,
                                            currentCanvasWidthDp = contentNeedW.value,
                                            currentCanvasHeightDp = with(density) { maxOf(viewportHeightPxState.intValue, contentNeedHPx).toDp().value },
                                            currentBounds = activeDragBoundsDp
                                        )
                                        if (expanded != activeDragBoundsDp) activeDragBoundsDp = expanded
                                    },
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
                                    getViewportSizePx = getViewportSizePx,
                                    getViewportRootOrigin = getViewportRootOrigin,
                                    openMediaDataSource = { id -> vm.openDecryptedMediaDataSource(id) },
                                    onStatus = { vm.showStatus(it, BannerKind.ERROR) },
                                    onSelect = { selAud = a.id; selImg = null; selVid = null },
                                    onDragProgress = { maxR, maxB ->
                                        val expanded = computeExpandedDragBounds(
                                            itemRightDp = maxR,
                                            itemBottomDp = maxB,
                                            currentCanvasWidthDp = contentNeedW.value,
                                            currentCanvasHeightDp = with(density) { maxOf(viewportHeightPxState.intValue, contentNeedHPx).toDp().value },
                                            currentBounds = activeDragBoundsDp
                                        )
                                        if (expanded != activeDragBoundsDp) activeDragBoundsDp = expanded
                                    },
                                    onMove = { x, y ->
                                        activeDragBoundsDp = null
                                        audios = audios.map { aud -> if (aud.id == a.id) aud.copy(x = x, y = y) else aud }
                                    }
                                )
                            }
                        }
                        if (showImportSkeleton) {
                            importingItem?.let { skel ->
                                ImportSkeletonCard(
                                    item = skel,
                                    onCancel = { cancelActiveImport(showBanner = true) }
                                )
                            }
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
            mediaImportBusy = importingItem != null,
        )
    }

    if (showImportCancelBackDialog) {
        WikiDialog(
            onDismiss = { showImportCancelBackDialog = false },
            title = "Import chal raha hai",
            titleIcon = Icons.Outlined.Warning,
            titleIconTint = w.danger,
            maxWidth = 340.dp
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Back jaane par import cancel ho jayega.",
                    style = sansStyle(12.sp, w.text, FontWeight.Medium)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WikiButton("Wapas", { showImportCancelBackDialog = false }, height = 36.dp)
                    Spacer(Modifier.width(8.dp))
                    WikiButton(
                        text = "Cancel import aur Back",
                        onClick = {
                            showImportCancelBackDialog = false
                            cancelActiveImport(showBanner = false)
                            requestExitWorkspace()
                        },
                        kind = BtnKind.DANGER,
                        height = 36.dp
                    )
                }
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
            val studioKeyLifecycle = LocalBitmapKeyLifecycle.current
            DisposableEffect(targetImg.source) {
                val k = targetImg.source.takeIf { it.isNotBlank() }
                if (k != null) studioKeyLifecycle?.acquire(k)
                onDispose { if (k != null) studioKeyLifecycle?.release(k) }
            }
            val previewBmp by produceState<ImageBitmap?>(null, targetImg.source) {
                value = targetImg.source.takeIf { it.isNotBlank() }?.let { vm.loadDecryptedImageBitmap(it) }
            }
            val diskSizeBytes by produceState(targetImg.sizeBytes, targetImg.source) {
                value = if (targetImg.sizeBytes > 0L) targetImg.sizeBytes
                else targetImg.source.takeIf { it.isNotBlank() }?.let { vm.getMediaSizeBytes(it) } ?: 0L
            }
            ImageStudioDialog(
                title = "Image Studio",
                sizeBytes = diskSizeBytes,
                onClose = { imgStudio = null },
                previewBitmap = previewBmp,
                loadSourceBitmap = targetImg.source.takeIf { it.isNotBlank() }?.let { srcId ->
                    { vm.loadEditableImageBitmap(srcId) }
                },
                initialOpacity = targetImg.opacity,
                onApplyOpacity = { newOpacity ->
                    images = images.map { m -> if (m.id == targetImg.id) m.copy(opacity = newOpacity) else m }
                },
                onApplyEdited = { encoded, previewImg ->
                    val newMediaId = vm.importEditedImageBytes(encoded.bytes, previewImg)
                    if (newMediaId != null) {
                        sessionImportedMediaIds.add(newMediaId)
                        images = images.map { m ->
                            if (m.id == targetImg.id) {
                                val (nextW, nextH) = if (encoded.params.hasGeometryChanges()) {
                                    ImageEditorEngine.computeUpdatedCanvasSize(m.width, m.height, encoded.width, encoded.height)
                                } else {
                                    m.width to m.height
                                }
                                m.copy(
                                    source = newMediaId,
                                    width = nextW,
                                    height = nextH,
                                    opacity = encoded.params.opacity,
                                    sizeBytes = encoded.bytes.size.toLong()
                                )
                            } else m
                        }
                        vm.showStatus("Image updated (${formatBytes(encoded.bytes.size.toLong())}).", BannerKind.SUCCESS)
                    } else {
                        vm.showStatus("Failed to save edited image.", BannerKind.ERROR)
                    }
                }
            )
        } ?: run { imgStudio = null }
    }
    if (bgStudio) {
        val bgStudioKeyLifecycle = LocalBitmapKeyLifecycle.current
        DisposableEffect(bgSrc) {
            val k = bgSrc?.takeIf { it.isNotBlank() }
            if (k != null) bgStudioKeyLifecycle?.acquire(k)
            onDispose { if (k != null) bgStudioKeyLifecycle?.release(k) }
        }
        val bgPreviewBmp by produceState<ImageBitmap?>(null, bgSrc) {
            value = bgSrc?.takeIf { it.isNotBlank() }?.let { vm.loadDecryptedImageBitmap(it) }
        }
        val bgSizeBytes by produceState(0L, bgSrc) {
            value = bgSrc?.takeIf { it.isNotBlank() }?.let { vm.getMediaSizeBytes(it) } ?: 0L
        }
        ImageStudioDialog(
            title = "Background Image Studio",
            sizeBytes = bgSizeBytes,
            onClose = { bgStudio = false },
            previewBitmap = bgPreviewBmp,
            loadSourceBitmap = bgSrc?.takeIf { it.isNotBlank() }?.let { srcId ->
                { vm.loadEditableImageBitmap(srcId) }
            },
            initialOpacity = bgOpacity,
            onApplyOpacity = { newOpacity -> bgOpacity = newOpacity },
            onApplyEdited = { encoded, previewImg ->
                val newMediaId = vm.importEditedImageBytes(encoded.bytes, previewImg)
                if (newMediaId != null) {
                    sessionImportedMediaIds.add(newMediaId)
                    bgSrc = newMediaId
                    bgOpacity = encoded.params.opacity
                    vm.showStatus("Background updated (${formatBytes(encoded.bytes.size.toLong())}).", BannerKind.SUCCESS)
                } else {
                    vm.showStatus("Failed to save background image.", BannerKind.ERROR)
                }
            }
        )
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

internal fun cleanupAbandonedSessionMedia(
    sessionImportedMediaIds: MutableCollection<String>,
    savedMediaIds: Set<String>,
    onCleanup: (Set<String>) -> Unit,
): Set<String> {
    val discardedMediaIds = sessionImportedMediaIds.toSet() - savedMediaIds
    sessionImportedMediaIds.clear()
    if (discardedMediaIds.isNotEmpty()) {
        onCleanup(discardedMediaIds)
    }
    return discardedMediaIds
}

internal fun <R> handleCompletedImportOutcome(
    result: R?,
    cancelled: Boolean,
    failMessage: String,
    extractMediaId: (R) -> String?,
    onImported: (R) -> Unit,
    onCleanupCancelledMedia: (String) -> Unit,
    onFailStatus: (String) -> Unit,
) {
    if (cancelled) {
        if (result != null) {
            extractMediaId(result)?.takeIf { it.isNotBlank() }?.let(onCleanupCancelledMedia)
        }
        return
    }
    if (result != null) {
        onImported(result)
    } else {
        onFailStatus(failMessage)
    }
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
internal enum class WorkspaceExitAction {
    AUTO_SAVE_AND_EXIT,
    NO_SAVE_EXIT,
}

internal fun isDiaryContentEmpty(
    heading: String,
    plainText: String,
    hasDividerSpan: Boolean,
    pfp: Pfp?,
    bgSrc: String?,
    images: List<CanvasImage>,
    audios: List<CanvasAudio>,
    videos: List<CanvasVideo>,
    reminderAt: Long?,
    lockPin: String?,
): Boolean {
    val hasHeading = heading.trim().isNotEmpty()
    val hasBodyText = plainText.trim { it.isWhitespace() || it == '\u200B' }.isNotEmpty() || hasDividerSpan
    val hasPfp = pfp != null
    val hasBg = !bgSrc.isNullOrBlank()
    val hasMedia = images.isNotEmpty() || audios.isNotEmpty() || videos.isNotEmpty()
    val hasReminder = reminderAt != null && reminderAt > 0L
    val hasLock = !lockPin.isNullOrBlank()
    return !(hasHeading || hasBodyText || hasPfp || hasBg || hasMedia || hasReminder || hasLock)
}

internal fun evaluateWorkspaceExitAction(
    isReading: Boolean,
    baseline: DiaryLog?,
    baselineHtml: String,
    currentHeading: String,
    currentContentHtml: String,
    currentPlainText: String,
    hasDividerSpan: Boolean,
    pfp: Pfp?,
    bgSrc: String?,
    bgOpacity: Float,
    images: List<CanvasImage>,
    audios: List<CanvasAudio>,
    videos: List<CanvasVideo>,
    reminderAt: Long?,
    lockPin: String?,
): WorkspaceExitAction {
    val empty = isDiaryContentEmpty(
        heading = currentHeading,
        plainText = currentPlainText,
        hasDividerSpan = hasDividerSpan,
        pfp = pfp,
        bgSrc = bgSrc,
        images = images,
        audios = audios,
        videos = videos,
        reminderAt = reminderAt,
        lockPin = lockPin,
    )
    if (empty) return WorkspaceExitAction.NO_SAVE_EXIT
    if (baseline == null) return WorkspaceExitAction.AUTO_SAVE_AND_EXIT
    if (isReading) return WorkspaceExitAction.NO_SAVE_EXIT

    val expectedHeading = currentHeading.ifBlank { "Untitled" }
    val changed = expectedHeading != baseline.heading ||
        currentContentHtml != baselineHtml ||
        pfp != baseline.pfp ||
        reminderAt != baseline.reminderAt ||
        lockPin != baseline.diaryLockPin ||
        bgSrc != baseline.canvasBgSource ||
        kotlin.math.abs(bgOpacity - baseline.canvasBgOpacity) > 0.0005f ||
        images != baseline.canvasImages ||
        audios != baseline.audioAttachments ||
        videos != baseline.videoAttachments
    return if (changed) WorkspaceExitAction.AUTO_SAVE_AND_EXIT else WorkspaceExitAction.NO_SAVE_EXIT
}

internal fun computeExpandedDragBounds(
    itemRightDp: Float,
    itemBottomDp: Float,
    currentCanvasWidthDp: Float,
    currentCanvasHeightDp: Float,
    currentBounds: Pair<Float, Float>?,
    marginDp: Float = 48f,
    stepDp: Float = 320f,
): Pair<Float, Float>? {
    val curRight = currentBounds?.first ?: (currentCanvasWidthDp - 240f)
    val curBottom = currentBounds?.second ?: (currentCanvasHeightDp - 240f)
    val needX = itemRightDp > (currentCanvasWidthDp - marginDp) && itemRightDp > curRight
    val needY = itemBottomDp > (currentCanvasHeightDp - marginDp) && itemBottomDp > curBottom
    if (!needX && !needY) return currentBounds
    val nextR = if (needX) itemRightDp + stepDp else (currentBounds?.first ?: 0f)
    val nextB = if (needY) itemBottomDp + stepDp else (currentBounds?.second ?: 0f)
    return nextR to nextB
}

internal fun computeAutoScrollStepPx(
    fingerRelPx: Float,
    viewportSizePx: Float,
    scrollValuePx: Int,
    edgeZonePx: Float,
    maxStepPx: Float,
): Float {
    if (viewportSizePx <= 0f || edgeZonePx <= 0f) return 0f
    return when {
        fingerRelPx > viewportSizePx - edgeZonePx -> {
            val ratio = ((fingerRelPx - (viewportSizePx - edgeZonePx)) / edgeZonePx).coerceIn(0f, 1.5f)
            maxStepPx * (0.35f + ratio * 0.65f)
        }
        fingerRelPx < edgeZonePx && scrollValuePx > 0 -> {
            val ratio = ((edgeZonePx - fingerRelPx) / edgeZonePx).coerceIn(0f, 1.5f)
            -maxStepPx * (0.35f + ratio * 0.65f)
        }
        else -> 0f
    }
}

private fun Modifier.smoothCanvasDraggable(
    itemId: String,
    editable: Boolean,
    getSelected: () -> Boolean,
    density: androidx.compose.ui.unit.Density,
    getCoords: () -> LayoutCoordinates?,
    getLivePosDp: () -> Pair<Float, Float>,
    setLivePosDp: (Float, Float) -> Unit,
    setDragging: (Boolean) -> Unit,
    setFingerRoot: (Offset) -> Unit,
    onTapSelect: () -> Unit,
    onDragProgress: (Float, Float) -> Unit,
    onMoveEnd: (Float, Float) -> Unit,
    getItemSizeDp: () -> Pair<Float, Float>,
): Modifier = this.pointerInput(itemId, editable) {
    val touchSlopPx = minOf(viewConfiguration.touchSlop * 0.45f, with(density) { 4.dp.toPx() })
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!editable) {
            // In read-only mode just pass tap if not dragged (e.g. VideoCard play toggle)
            var dragged = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (!dragged && !change.isConsumed) {
                        onTapSelect()
                    }
                    break
                }
                if ((change.position - down.position).getDistance() > touchSlopPx) {
                    dragged = true
                }
            }
            return@awaitEachGesture
        }

        var draggingStarted = false
        var prevFingerRoot = Offset.Zero

        while (true) {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            if (pressedCount >= 2) {
                if (draggingStarted) {
                    setDragging(false)
                    draggingStarted = false
                    val (lx, ly) = getLivePosDp()
                    onMoveEnd(lx, ly)
                }
                break
            }

            if (change.changedToUpIgnoreConsumed()) {
                if (draggingStarted) {
                    change.consume()
                    setDragging(false)
                    val (lx, ly) = getLivePosDp()
                    onMoveEnd(lx, ly)
                    if (!getSelected()) onTapSelect()
                } else if (!change.isConsumed) {
                    change.consume()
                    onTapSelect()
                }
                break
            }

            if (change.isConsumed) {
                if (draggingStarted) {
                    setDragging(false)
                    val (lx, ly) = getLivePosDp()
                    onMoveEnd(lx, ly)
                }
                break
            }

            if (!draggingStarted) {
                val dist = (change.position - down.position).getDistance()
                if (dist > touchSlopPx) {
                    val coords = getCoords()
                    if (coords != null && coords.isAttached) {
                        prevFingerRoot = coords.localToRoot(change.position)
                        setFingerRoot(prevFingerRoot)
                        draggingStarted = true
                        setDragging(true)
                        change.consume()
                        val (lx, ly) = getLivePosDp()
                        val (wDp, hDp) = getItemSizeDp()
                        onDragProgress(lx + wDp, ly + hDp)
                    }
                }
            } else {
                change.consume()
                val coords = getCoords()
                if (coords != null && coords.isAttached) {
                    val curRoot = coords.localToRoot(change.position)
                    val deltaRoot = curRoot - prevFingerRoot
                    prevFingerRoot = curRoot
                    setFingerRoot(curRoot)
                    if (deltaRoot.x != 0f || deltaRoot.y != 0f) {
                        val (lx, ly) = getLivePosDp()
                        val nextX = (lx + deltaRoot.x / density.density).coerceAtLeast(0f)
                        val nextY = (ly + deltaRoot.y / density.density).coerceAtLeast(0f)
                        setLivePosDp(nextX, nextY)
                        val (wDp, hDp) = getItemSizeDp()
                        onDragProgress(nextX + wDp, nextY + hDp)
                    }
                }
            }
        }
        if (draggingStarted) {
            setDragging(false)
            val (lx, ly) = getLivePosDp()
            onMoveEnd(lx, ly)
        }
    }
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
    getViewportSizePx: () -> Pair<Float, Float>,
    getViewportRootOrigin: () -> Offset,
    loadImageBitmap: suspend (String) -> ImageBitmap?,
    onSelect: () -> Unit,
    onDragProgress: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val w = wiki()
    val density = LocalDensity.current
    var liveX by remember(im.id) { mutableFloatStateOf(im.x) }
    var liveY by remember(im.id) { mutableFloatStateOf(im.y) }
    var isDragging by remember(im.id) { mutableStateOf(false) }
    val fingerRootRef = remember(im.id) { object { var pos = Offset.Zero } }
    val itemCoordsRef = remember(im.id) { object { var coords: LayoutCoordinates? = null } }

    val currentW by rememberUpdatedState(im.width)
    val currentH by rememberUpdatedState(im.height)
    val currentSelected by rememberUpdatedState(selected)
    val currentGetViewportSizePx by rememberUpdatedState(getViewportSizePx)
    val currentGetViewportOrigin by rememberUpdatedState(getViewportRootOrigin)
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
            val (vpW, vpH) = currentGetViewportSizePx()
            val vpOrigin = currentGetViewportOrigin()
            val panPxX = computeAutoScrollStepPx(fingerRootRef.pos.x - vpOrigin.x, vpW, hScroll.value, edgeZonePx, maxStepPx)
            val panPxY = computeAutoScrollStepPx(fingerRootRef.pos.y - vpOrigin.y, vpH, vScroll.value, edgeZonePx, maxStepPx)
            if (panPxX != 0f || panPxY != 0f) {
                val consumedX = if (panPxX != 0f) hScroll.scrollBy(panPxX) else 0f
                val consumedY = if (panPxY != 0f) vScroll.scrollBy(panPxY) else 0f
                if (consumedX != 0f || consumedY != 0f) {
                    val nextX = (liveX + consumedX / density.density).coerceAtLeast(0f)
                    val nextY = (liveY + consumedY / density.density).coerceAtLeast(0f)
                    liveX = nextX
                    liveY = nextY
                    currentOnDragProgress(nextX + currentW, nextY + currentH)
                }
            }
            delay(16L)
        }
    }

    val imgKeyLifecycle = LocalBitmapKeyLifecycle.current
    DisposableEffect(im.source) {
        val k = im.source.takeIf { it.isNotBlank() }
        if (k != null) imgKeyLifecycle?.acquire(k)
        onDispose { if (k != null) imgKeyLifecycle?.release(k) }
    }
    val bitmap by produceState<ImageBitmap?>(null, im.source) {
        value = im.source.takeIf { it.isNotBlank() }?.let { loadImageBitmap(it) }
    }

    val highlightBorder = selected || isDragging
    Box(
        Modifier
            .offset { IntOffset((liveX * density.density).roundToInt(), (liveY * density.density).roundToInt()) }
            .size(im.width.dp, im.height.dp)
            .rotate(im.rotation)
            .onGloballyPositioned { itemCoordsRef.coords = it }
            .background(if (bitmap != null) Color.Transparent else w.surface)
            .wikiBorder(if (highlightBorder) w.accent else w.border, if (highlightBorder) 1.5.dp else 0.5.dp)
            .smoothCanvasDraggable(
                itemId = im.id,
                editable = editable,
                getSelected = { currentSelected },
                density = density,
                getCoords = { itemCoordsRef.coords },
                getLivePosDp = { liveX to liveY },
                setLivePosDp = { nx, ny -> liveX = nx; liveY = ny },
                setDragging = { isDragging = it },
                setFingerRoot = { fingerRootRef.pos = it },
                onTapSelect = { if (editable) currentOnSelect() },
                onDragProgress = { r, b -> currentOnDragProgress(r, b) },
                onMoveEnd = { x, y -> currentOnMove(x, y) },
                getItemSizeDp = { currentW to currentH }
            ),
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

/** Opaque RED audio card #b32424. Play button before slider. Draggable infinitely right/bottom. */
@Composable
private fun AudioCard(
    a: CanvasAudio,
    selected: Boolean,
    editable: Boolean,
    hScroll: ScrollState,
    vScroll: ScrollState,
    getViewportSizePx: () -> Pair<Float, Float>,
    getViewportRootOrigin: () -> Offset,
    openMediaDataSource: suspend (String) -> MediaDataSource?,
    onStatus: (String) -> Unit,
    onSelect: () -> Unit,
    onDragProgress: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var liveX by remember(a.id) { mutableFloatStateOf(a.x) }
    var liveY by remember(a.id) { mutableFloatStateOf(a.y) }
    var isDragging by remember(a.id) { mutableStateOf(false) }
    val fingerRootRef = remember(a.id) { object { var pos = Offset.Zero } }
    val itemCoordsRef = remember(a.id) { object { var coords: LayoutCoordinates? = null } }

    val currentW by rememberUpdatedState(a.width)
    val currentH by rememberUpdatedState(a.height)
    val currentSelected by rememberUpdatedState(selected)
    val currentGetViewportSizePx by rememberUpdatedState(getViewportSizePx)
    val currentGetViewportOrigin by rememberUpdatedState(getViewportRootOrigin)
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
            val (vpW, vpH) = currentGetViewportSizePx()
            val vpOrigin = currentGetViewportOrigin()
            val panPxX = computeAutoScrollStepPx(fingerRootRef.pos.x - vpOrigin.x, vpW, hScroll.value, edgeZonePx, maxStepPx)
            val panPxY = computeAutoScrollStepPx(fingerRootRef.pos.y - vpOrigin.y, vpH, vScroll.value, edgeZonePx, maxStepPx)
            if (panPxX != 0f || panPxY != 0f) {
                val consumedX = if (panPxX != 0f) hScroll.scrollBy(panPxX) else 0f
                val consumedY = if (panPxY != 0f) vScroll.scrollBy(panPxY) else 0f
                if (consumedX != 0f || consumedY != 0f) {
                    val nextX = (liveX + consumedX / density.density).coerceAtLeast(0f)
                    val nextY = (liveY + consumedY / density.density).coerceAtLeast(0f)
                    liveX = nextX
                    liveY = nextY
                    currentOnDragProgress(nextX + currentW, nextY + currentH)
                }
            }
            delay(16L)
        }
    }

    var playing by remember(a.id) { mutableStateOf(false) }
    var loadingAudio by remember(a.id) { mutableStateOf(false) }
    var progress by remember(a.id) { mutableStateOf(0f) }
    var durationMs by remember(a.id) { mutableStateOf((a.durationSec * 1000).coerceAtLeast(1000)) }
    var mediaPlayer by remember(a.id) { mutableStateOf<MediaPlayer?>(null) }
    var activeDataSource by remember(a.id) { mutableStateOf<MediaDataSource?>(null) }
    var disposed by remember(a.id) { mutableStateOf(false) }

    DisposableEffect(a.id) {
        disposed = false
        onDispose {
            disposed = true
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            runCatching { activeDataSource?.close() }
            activeDataSource = null
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
            val ds = a.audioUri.takeIf { it.isNotBlank() }?.let { openMediaDataSource(it) }
            if (ds == null) {
                loadingAudio = false
                onStatus("Audio data unavailable.")
                return@launch
            }
            if (disposed) {
                loadingAudio = false
                runCatching { ds.close() }
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
            loadingAudio = false
            if (disposed) {
                runCatching { preparedPlayer?.release() }
                runCatching { ds.close() }
                return@launch
            }
            if (preparedPlayer == null) {
                runCatching { ds.close() }
                onStatus("Unsupported audio format.")
                return@launch
            }
            activeDataSource = ds
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

    val highlightAudioBorder = selected || isDragging
    Row(
        Modifier
            .offset { IntOffset((liveX * density.density).roundToInt(), (liveY * density.density).roundToInt()) }
            .size(a.width.dp, a.height.dp)
            .rotate(a.rotation)
            .onGloballyPositioned { itemCoordsRef.coords = it }
            .background(Color(0xFFB32424))
            .wikiBorder(if (highlightAudioBorder) wiki().accent else Color(0xFF7A1616), if (highlightAudioBorder) 1.5.dp else 0.5.dp)
            .smoothCanvasDraggable(
                itemId = a.id,
                editable = editable,
                getSelected = { currentSelected },
                density = density,
                getCoords = { itemCoordsRef.coords },
                getLivePosDp = { liveX to liveY },
                setLivePosDp = { nx, ny -> liveX = nx; liveY = ny },
                setDragging = { isDragging = it },
                setFingerRoot = { fingerRootRef.pos = it },
                onTapSelect = { if (editable) currentOnSelect() },
                onDragProgress = { r, b -> currentOnDragProgress(r, b) },
                onMoveEnd = { x, y -> currentOnMove(x, y) },
                getItemSizeDp = { currentW to currentH }
            )
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
    getViewportSizePx: () -> Pair<Float, Float>,
    getViewportRootOrigin: () -> Offset,
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
    var liveX by remember(v.id) { mutableFloatStateOf(v.x) }
    var liveY by remember(v.id) { mutableFloatStateOf(v.y) }
    var isDragging by remember(v.id) { mutableStateOf(false) }
    val fingerRootRef = remember(v.id) { object { var pos = Offset.Zero } }
    val itemCoordsRef = remember(v.id) { object { var coords: LayoutCoordinates? = null } }

    val currentW by rememberUpdatedState(v.width)
    val currentH by rememberUpdatedState(v.height)
    val currentSelected by rememberUpdatedState(selected)
    val currentGetViewportSizePx by rememberUpdatedState(getViewportSizePx)
    val currentGetViewportOrigin by rememberUpdatedState(getViewportRootOrigin)
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
            val (vpW, vpH) = currentGetViewportSizePx()
            val vpOrigin = currentGetViewportOrigin()
            val panPxX = computeAutoScrollStepPx(fingerRootRef.pos.x - vpOrigin.x, vpW, hScroll.value, edgeZonePx, maxStepPx)
            val panPxY = computeAutoScrollStepPx(fingerRootRef.pos.y - vpOrigin.y, vpH, vScroll.value, edgeZonePx, maxStepPx)
            if (panPxX != 0f || panPxY != 0f) {
                val consumedX = if (panPxX != 0f) hScroll.scrollBy(panPxX) else 0f
                val consumedY = if (panPxY != 0f) vScroll.scrollBy(panPxY) else 0f
                if (consumedX != 0f || consumedY != 0f) {
                    val nextX = (liveX + consumedX / density.density).coerceAtLeast(0f)
                    val nextY = (liveY + consumedY / density.density).coerceAtLeast(0f)
                    liveX = nextX
                    liveY = nextY
                    currentOnDragProgress(nextX + currentW, nextY + currentH)
                }
            }
            delay(16L)
        }
    }

    val vidKeyLifecycle = LocalBitmapKeyLifecycle.current
    DisposableEffect(v.source) {
        val k = v.source.takeIf { it.isNotBlank() }?.let { "vthumb_$it" }
        if (k != null) vidKeyLifecycle?.acquire(k)
        onDispose { if (k != null) vidKeyLifecycle?.release(k) }
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

    val highlightVideoBorder = selected || isDragging
    Box(
        Modifier
            .offset { IntOffset((liveX * density.density).roundToInt(), (liveY * density.density).roundToInt()) }
            .size(v.width.dp, v.height.dp)
            .rotate(v.rotation)
            .clipToBounds()
            .onGloballyPositioned { itemCoordsRef.coords = it }
            .background(Color(0xFF14161A))
            .wikiBorder(if (highlightVideoBorder) w.accent else w.border, if (highlightVideoBorder) 1.5.dp else 0.5.dp)
            .smoothCanvasDraggable(
                itemId = v.id,
                editable = editable,
                getSelected = { currentSelected },
                density = density,
                getCoords = { itemCoordsRef.coords },
                getLivePosDp = { liveX to liveY },
                setLivePosDp = { nx, ny -> liveX = nx; liveY = ny },
                setDragging = { isDragging = it },
                setFingerRoot = { fingerRootRef.pos = it },
                onTapSelect = { if (editable) currentOnSelect() else togglePlay() },
                onDragProgress = { r, b -> currentOnDragProgress(r, b) },
                onMoveEnd = { x, y -> currentOnMove(x, y) },
                getItemSizeDp = { currentW to currentH }
            ),
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
