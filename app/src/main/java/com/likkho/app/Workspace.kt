package com.likkho.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.likkho.app.editor.BlockDecorationLayoutCalculator
import com.likkho.app.editor.HeadingTypographySpec
import com.likkho.app.editor.RichTextEditorState
import com.likkho.app.editor.RichTextVisualTransformation
import com.likkho.app.editor.drawBlockDecorations
import com.likkho.app.editor.drawTextHighlights
import com.likkho.app.editor.drawTextSpoilers
import com.likkho.app.editor.model.SpanStyle
import com.likkho.app.editor.toComposeTextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private class CanvasViewportMetrics {
    var widthPx: Int by mutableIntStateOf(0)
    var heightPx: Int by mutableIntStateOf(0)
    var unshrunkHeightPx: Int by mutableIntStateOf(0)
}

/**
 * WORKSPACE (WritingWorkspace.tsx). Opens in READING mode for existing diary (Edit button), EDIT mode for new.
 * Header: back | PFP | heading | [delete] | Reminder | Edit/Save. Then reminder sub-bar,
 * text-only canvas, and the formatting toolbar docked at bottom (edit mode only).
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
    val preservedBgSource = initial?.canvasBgSource
    val preservedBgOpacity = initial?.canvasBgOpacity ?: 0.35f
    val preservedImages = initial?.canvasImages ?: emptyList()
    val preservedAudios = initial?.audioAttachments ?: emptyList()
    val preservedVideos = initial?.videoAttachments ?: emptyList()
    val editorState = remember(initial?.id, initial?.isContentLoaded) { RichTextEditorState.fromHtml(initial?.contentHtml.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    var showPfp by remember { mutableStateOf(false) }
    var spoilerUnlockPrompt by remember { mutableStateOf<SpanStyle.Spoiler?>(null) }

    val initialMediaIds = remember {
        collectCanvasMediaIds(pfp, preservedBgSource, preservedImages, preservedAudios, preservedVideos)
    }
    var lastSavedMediaIds by remember { mutableStateOf(initialMediaIds) }
    val sessionImportedMediaIds = remember { mutableStateListOf<String>() }

    fun cleanupUnsavedSessionMedia() {
        cleanupAbandonedSessionMedia(
            sessionImportedMediaIds = sessionImportedMediaIds,
            savedMediaIds = lastSavedMediaIds,
            onCleanup = { discarded -> vm.cleanupUnusedSessionMedia(discarded) }
        )
    }
    val currentCleanupUnsavedSessionMedia by rememberUpdatedState { cleanupUnsavedSessionMedia() }

    DisposableEffect(Unit) {
        onDispose {
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

    val vScroll = rememberScrollState()

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
            canvasBgSource = preservedBgSource,
            canvasBgOpacity = preservedBgOpacity,
            canvasImages = preservedImages,
            audioAttachments = preservedAudios,
            videoAttachments = preservedVideos
        )
        val currentMediaIds = collectCanvasMediaIds(
            pfp,
            preservedBgSource,
            preservedImages,
            preservedAudios,
            preservedVideos
        )
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
        cleanupUnsavedSessionMedia()
        vm.navigateTo(Route.HOME)
    }

    BackHandler {
        exitWorkspace()
    }

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
                else WikiButton(
                    text = if (saved) "Saved" else "Save",
                    onClick = { save() },
                    modifier = Modifier.testTag("workspace_save_button"),
                    kind = if (saved) BtnKind.SUCCESS else BtnKind.PRIMARY,
                    icon = if (saved) Icons.Outlined.Check else Icons.Outlined.Save,
                    height = 40.dp
                )
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

        // ---------- canvas (text-only) ----------
        val viewportMetrics = remember { CanvasViewportMetrics() }
        val editorFocusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        LaunchedEffect(reading) {
            if (viewportMetrics.heightPx > 0) {
                viewportMetrics.unshrunkHeightPx = viewportMetrics.heightPx
            }
        }
        val density = LocalDensity.current

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(w.bg)
                .clipToBounds()
                .onSizeChanged { size ->
                    val widthChanged = viewportMetrics.widthPx != size.width
                    if (widthChanged) {
                        viewportMetrics.widthPx = size.width
                        viewportMetrics.unshrunkHeightPx = size.height
                    } else if (viewportMetrics.unshrunkHeightPx == 0 || size.height > viewportMetrics.unshrunkHeightPx) {
                        viewportMetrics.unshrunkHeightPx = size.height
                    }
                    if (viewportMetrics.heightPx != size.height) {
                        viewportMetrics.heightPx = size.height
                    }
                }
        ) {
            val boundedHeightPx = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
            val effectiveUnshrunkHeightPx = maxOf(viewportMetrics.unshrunkHeightPx, boundedHeightPx)
            val stableMinHeightDp = remember(effectiveUnshrunkHeightPx, density) {
                if (effectiveUnshrunkHeightPx > 0) {
                    with(density) {
                        (effectiveUnshrunkHeightPx - 32.dp.roundToPx())
                            .coerceAtLeast(240.dp.roundToPx())
                            .toDp()
                    }
                } else {
                    240.dp
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(vScroll)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(reading) {
                            if (!reading) {
                                detectTapGestures {
                                    editorFocusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            }
                        }
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        WorkspaceWritingLayer(
                            editorState = editorState,
                            reading = reading,
                            customFontsCount = vm.customFonts.size,
                            hooks = hooks,
                            vScroll = vScroll,
                            viewportMetrics = viewportMetrics,
                            editorFocusRequester = editorFocusRequester,
                            minHeightDp = stableMinHeightDp,
                            onSpoilerPrompt = { spoilerUnlockPrompt = it },
                            onStatus = { msg, kind -> vm.showStatus(msg, kind) },
                            onSaveSilent = { save(showFeedback = false) },
                        )
                    }
                    Spacer(Modifier.height(120.dp))
                }
            }
        }

        // ---------- toolbar ----------
        if (!reading) RichToolbar(
            vm = vm,
            lockPin = lockPin,
            bgSrc = null,
            bgOpacity = 0.35f,
            recording = false,
            onRecordToggle = { vm.showStatus("Canvas media insertion will be added later.", BannerKind.INFO) },
            onAddImage = { vm.showStatus("Canvas media insertion will be added later.", BannerKind.INFO) },
            onAddVideo = { vm.showStatus("Canvas media insertion will be added later.", BannerKind.INFO) },
            onAddAudio = { vm.showStatus("Canvas media insertion will be added later.", BannerKind.INFO) },
            onLock = { lockPin = it },
            onBgOpacity = {},
            onPickBg = { vm.showStatus("Canvas media insertion will be added later.", BannerKind.INFO) },
            onRemoveBg = {},
            onEditBg = {},
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
            mediaImportBusy = false,
        )
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

@Composable
private fun WorkspaceWritingLayer(
    editorState: RichTextEditorState,
    reading: Boolean,
    customFontsCount: Int,
    hooks: FeatureHooks,
    vScroll: ScrollState,
    viewportMetrics: CanvasViewportMetrics,
    editorFocusRequester: FocusRequester,
    minHeightDp: Dp,
    onSpoilerPrompt: (SpanStyle.Spoiler) -> Unit,
    onStatus: (String, BannerKind) -> Unit,
    onSaveSilent: () -> Unit,
) {
    val w = wiki()
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val systemClipboard = LocalClipboardManager.current

    val isTextEmpty by remember(editorState) { derivedStateOf { editorState.text.isEmpty() } }
    val hasTodoSpans by remember(editorState) { derivedStateOf { editorState.spans.any { it.style is SpanStyle.Todo } } }
    val hasInteractiveSpans by remember(editorState) {
        derivedStateOf {
            editorState.spans.any {
                val st = it.style
                st is SpanStyle.Link || (st is SpanStyle.Spoiler && !editorState.isSpoilerUnlocked(st))
            }
        }
    }
    val emptyPendingIndent = if (isTextEmpty && editorState.activePendingIndent > 0) {
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
    val resolvedTypefaceState = remember(fontFamilyResolver) {
        fontFamilyResolver.resolve(
            fontFamily = WikiFonts.prose,
            fontWeight = FontWeight.Normal,
            fontStyle = FontStyle.Normal,
            fontSynthesis = FontSynthesis.All,
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
    val spoilerClipboardManager = remember(systemClipboard, editorState) {
        object : ClipboardManager by systemClipboard {
            override fun setText(annotatedString: AnnotatedString) {
                when (val copyResult = editorState.prepareClipboardCopy()) {
                    is RichTextEditorState.SpoilerCopyResult.AllowPlain -> {
                        systemClipboard.setText(annotatedString)
                    }
                    is RichTextEditorState.SpoilerCopyResult.Masked -> {
                        systemClipboard.setText(AnnotatedString(copyResult.text))
                        onStatus("Copied with hidden spoiler text masked.", BannerKind.INFO)
                    }
                    is RichTextEditorState.SpoilerCopyResult.Blocked -> {
                        onStatus("Enter spoiler passcode to copy hidden text.", BannerKind.ERROR)
                        onSpoilerPrompt(copyResult.blockingSpoiler)
                    }
                }
            }
        }
    }
    val scratchPaint = remember { android.graphics.Paint() }
    val segmentLineMetrics: (Int, Int) -> Pair<Float, Float> = remember(
        editorState,
        fontMetrics,
        headingFontMetrics,
        resolvedTypefaceState.value,
        density,
        context
    ) {
        { segStart, segEnd ->
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
            val (baseAscent, baseDescent) = when {
                resolvedTf != null -> {
                    scratchPaint.typeface = resolvedTf
                    scratchPaint.textSize = pxSize
                    val fm = scratchPaint.fontMetrics
                    fm.ascent to fm.descent
                }
                bestLevel != null && !isScript -> {
                    val fm = headingFontMetrics[bestLevel] ?: fontMetrics
                    fm.ascent to fm.descent
                }
                isScript -> {
                    scratchPaint.typeface = resolvedTypefaceState.value as? android.graphics.Typeface
                    scratchPaint.textSize = pxSize
                    val fm = scratchPaint.fontMetrics
                    fm.ascent to fm.descent
                }
                else -> fontMetrics.ascent to fontMetrics.descent
            }
            val curText = editorState.text
            val hasDevanagari = (segStart until segEnd.coerceAtMost(curText.length)).any { idx ->
                curText[idx] in '\u0900'..'\u097F'
            }
            if (hasDevanagari) {
                scratchPaint.typeface = android.graphics.Typeface.DEFAULT
                scratchPaint.textSize = pxSize
                val devFm = scratchPaint.fontMetrics
                minOf(baseAscent, devFm.ascent) to maxOf(baseDescent, devFm.descent)
            } else {
                baseAscent to baseDescent
            }
        }
    }

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val showFallbackCursor by remember { derivedStateOf { textLayoutResult == null } }
    var isEditorFocused by remember { mutableStateOf(false) }
    var cursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(isEditorFocused, reading) {
        if (!isEditorFocused || reading) {
            cursorVisible = false
            return@LaunchedEffect
        }
        snapshotFlow {
            editorState.selection to editorState.text.length
        }.collectLatest {
            cursorVisible = true
            while (true) {
                delay(530L)
                cursorVisible = !cursorVisible
            }
        }
    }

    // Keep the cursor smoothly visible above the rising keyboard & toolbar when IME opens
    val imeInsets = WindowInsets.ime
    LaunchedEffect(isEditorFocused, reading) {
        if (!isEditorFocused || reading) return@LaunchedEffect
        var prevImeBottom = imeInsets.getBottom(density)
        var prevVpH = viewportMetrics.heightPx
        snapshotFlow { imeInsets.getBottom(density) to viewportMetrics.heightPx }
            .collect { (imeBottom, vpH) ->
                val imeGrowing = imeBottom > prevImeBottom || (imeBottom > 0 && vpH < prevVpH)
                prevImeBottom = imeBottom
                prevVpH = vpH
                if (imeGrowing && vpH > 0) {
                    val layout = textLayoutResult ?: return@collect
                    if (layout.lineCount <= 0 || layout.layoutInput.text.text != editorState.text) {
                        return@collect
                    }
                    val cursorOffset = editorState.selection.start.coerceIn(0, layout.layoutInput.text.text.length)
                    val cursorRect = layout.getCursorRect(cursorOffset)
                    val padPx = with(density) { 16.dp.toPx() }
                    val marginPx = with(density) { 24.dp.toPx() }
                    val cursorBottomInCanvas = padPx + cursorRect.bottom + marginPx
                    val visibleBottom = vScroll.value.toFloat() + vpH.toFloat()
                    val overflow = cursorBottomInCanvas - visibleBottom
                    if (overflow > 0.5f) {
                        vScroll.scrollBy(overflow)
                    }
                }
            }
    }

    CompositionLocalProvider(LocalClipboardManager provides spoilerClipboardManager) {
        BasicTextField(
            value = editorState.textFieldValue,
            onValueChange = { editorState.onTextFieldValueChange(it) },
            readOnly = reading,
            textStyle = editorTextStyle,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            cursorBrush = SolidColor(if (showFallbackCursor) w.accent else Color.Transparent),
            visualTransformation = remember(
                editorState.spans,
                editorState.canvasAlignment,
                editorState.unlockedSpoilerIds,
                customFontsCount,
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
                .heightIn(min = minHeightDp)
                .focusRequester(editorFocusRequester)
                .onFocusChanged { isEditorFocused = it.isFocused }
                .then(
                    if (hasInteractiveSpans) {
                        Modifier.pointerInput(editorState, hooks) {
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
                                                onSpoilerPrompt(tappedSpoiler)
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
                        }
                    } else Modifier
                ),
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
                        }
                ) {
                    if (isTextEmpty) {
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
                    Spacer(
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
                                scratchPaint.typeface = activeTypeface
                                scratchPaint.textSize = cursorSizePx
                                val cursorFm = scratchPaint.fontMetrics
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
                    )
                    if (hasTodoSpans) {
                        val layout = textLayoutResult
                        if (layout != null &&
                            layout.lineCount > 0 &&
                            layout.layoutInput.text.text == editorState.text
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
                                                    onSaveSilent()
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
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
