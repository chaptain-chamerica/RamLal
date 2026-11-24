package com.likkho.app

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.likkho.app.editor.BlockDecorationLayoutCalculator
import com.likkho.app.editor.FontRegistry
import com.likkho.app.editor.HeadingTypographySpec
import com.likkho.app.editor.HighlightLayoutCalculator
import com.likkho.app.editor.RichTextEditorState
import com.likkho.app.editor.RichTextVisualTransformation
import com.likkho.app.editor.drawCachedBlockDecorations
import com.likkho.app.editor.drawCachedHighlightRects
import com.likkho.app.editor.model.SpanStyle
import com.likkho.app.editor.toComposeTextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WORKSPACE (WritingWorkspace.tsx). Opens in READING mode for existing diary (Edit button), EDIT mode for new.
 * Header: back | PFP | heading | [delete] | Reminder | Edit/Save. Then reminder sub-bar,
 * rich-text document canvas, and the formatting toolbar docked at bottom (edit mode only).
 */
@Composable
fun WorkspaceScreen(vm: AppViewModel) {
    val w = wiki(); val hooks = vm.hooks; val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    val uriHandler = LocalUriHandler.current
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
        textToolbar.hide()
        focusManager.clearFocus(force = true)
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

        // ---------- rich-text document canvas ----------
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(w.bg)
                .clipToBounds()
        ) {
            val canvasViewportHeight = maxHeight
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(vScroll)
            ) {
                WorkspaceWritingLayer(
                    editorState = editorState,
                    reading = reading,
                    minCanvasHeight = canvasViewportHeight,
                    onOpenLink = { url ->
                        runCatching { uriHandler.openUri(url) }.onFailure {
                            vm.showStatus("Could not open link.", BannerKind.ERROR)
                        }
                    },
                    onSpoilerTap = { sp -> spoilerUnlockPrompt = sp },
                    onCopySpoilerBlocked = { blocked ->
                        vm.showStatus("Enter spoiler passcode to copy hidden text.", BannerKind.ERROR)
                        spoilerUnlockPrompt = blocked
                    },
                    onCopyMaskedSpoiler = {
                        vm.showStatus("Copied with hidden spoiler masked.", BannerKind.INFO)
                    }
                )
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
                val start = current.selection.min.coerceIn(0, current.text.length)
                val end = current.selection.max.coerceIn(start, current.text.length)
                val newText = current.text.substring(0, start) + inserted + current.text.substring(end)
                editorState.onTextFieldValueChange(
                    current.copy(
                        text = newText,
                        selection = TextRange(start + inserted.length)
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
    minCanvasHeight: Dp,
    onOpenLink: (String) -> Unit,
    onSpoilerTap: (SpanStyle.Spoiler) -> Unit,
    onCopySpoilerBlocked: (SpanStyle.Spoiler) -> Unit,
    onCopyMaskedSpoiler: () -> Unit,
) {
    val w = wiki()
    val context = LocalContext.current
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val baseClipboard = LocalClipboardManager.current

    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val reusableCheckPath = remember { Path() }

    val devanagariTypeface = remember(context) {
        runCatching { ResourcesCompat.getFont(context, R.font.noto_serif_devanagari) }.getOrNull()
    }
    val baseProseTypeface = remember(context) {
        runCatching { ResourcesCompat.getFont(context, R.font.lora) }.getOrNull() ?: Typeface.SERIF
    }
    val serifTypeface = remember(context) {
        runCatching { ResourcesCompat.getFont(context, R.font.cormorant_garamond) }.getOrNull() ?: Typeface.SERIF
    }
    val monoTypeface = remember(context) {
        runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_mono) }.getOrNull() ?: Typeface.MONOSPACE
    }

    val measurePaint = remember { Paint() }
    val baseFontSizePx = with(density) { 16.sp.toPx() }
    val (baseAscentPx, baseDescentPx) = remember(baseProseTypeface, baseFontSizePx) {
        measurePaint.typeface = baseProseTypeface
        measurePaint.textSize = baseFontSizePx
        val fm = measurePaint.fontMetrics
        fm.ascent to fm.descent
    }

    val segmentLineMetrics: (Int, Int) -> Pair<Float, Float> = remember(
        editorState.text,
        editorState.spans,
        density.density,
        density.fontScale,
        context
    ) {
        { segStart, segEnd ->
            val raw = editorState.text
            var fontId: String? = null
            var sizeSp: Float? = null
            var headingLvl: Int? = null
            var hasCode = false
            var hasQuote = false
            for (sp in editorState.spans) {
                if (sp.start < segEnd && sp.end > segStart) {
                    when (val st = sp.style) {
                        is SpanStyle.FontId -> fontId = st.fontId
                        is SpanStyle.SizePx -> sizeSp = st.sizePx.coerceIn(10f, 48f)
                        is SpanStyle.Heading -> if (headingLvl == null || st.level < headingLvl) headingLvl = st.level
                        is SpanStyle.Code -> hasCode = true
                        is SpanStyle.Quote -> hasQuote = true
                        else -> {}
                    }
                }
            }
            val hasDevanagari = (segStart until segEnd.coerceAtMost(raw.length)).any { idx ->
                raw[idx] in '\u0900'..'\u097F'
            }
            val effSp = sizeSp
                ?: headingLvl?.let { HeadingTypographySpec.fontSize(it).value }
                ?: if (hasCode) 14.5f else 16f
            val tf = when {
                fontId != null -> FontRegistry.resolveTypeface(context, fontId)
                hasCode -> monoTypeface
                headingLvl != null || hasQuote -> serifTypeface
                else -> baseProseTypeface
            } ?: baseProseTypeface
            measurePaint.typeface = if (hasDevanagari && fontId == null && !hasCode) {
                devanagariTypeface ?: tf
            } else {
                tf
            }
            measurePaint.textSize = with(density) { effSp.sp.toPx() }
            val fm = measurePaint.fontMetrics
            fm.ascent to fm.descent
        }
    }

    val cachedBlockDecorations = remember(textLayoutResult, editorState.spans, density.density) {
        val lr = textLayoutResult ?: return@remember null
        BlockDecorationLayoutCalculator.computeStaticDecorations(
            layoutResult = lr,
            spans = editorState.spans,
            densityScale = density.density
        )
    }

    val activeHeadingDivider = remember(
        textLayoutResult,
        cachedBlockDecorations,
        editorState.activeHeadingLevel,
        editorState.selection.start
    ) {
        val lr = textLayoutResult ?: return@remember null
        val cached = cachedBlockDecorations ?: return@remember null
        if (editorState.activeHeadingLevel == null || lr.lineCount <= 0) return@remember null
        val safeCursor = editorState.selection.start.coerceIn(0, lr.layoutInput.text.text.length)
        val cursorLine = lr.getLineForOffset(safeCursor).coerceIn(0, lr.lineCount - 1)
        BlockDecorationLayoutCalculator.computeActiveHeadingDivider(lr, cursorLine, cached.staticDividers)
    }

    val cachedHighlights = remember(textLayoutResult, editorState.spans, density.density, density.fontScale) {
        val lr = textLayoutResult ?: return@remember emptyList()
        HighlightLayoutCalculator.computeCachedHighlights(
            layoutResult = lr,
            spans = editorState.spans,
            ascentPx = baseAscentPx,
            descentPx = baseDescentPx,
            lineMetrics = segmentLineMetrics
        )
    }

    val cachedSpoilers = remember(
        textLayoutResult,
        editorState.spans,
        editorState.unlockedSpoilerIds,
        density.density,
        density.fontScale
    ) {
        val lr = textLayoutResult ?: return@remember emptyList()
        HighlightLayoutCalculator.computeCachedSpoilers(
            layoutResult = lr,
            spans = editorState.spans,
            unlockedSpoilerIds = editorState.unlockedSpoilerIds,
            ascentPx = baseAscentPx,
            descentPx = baseDescentPx,
            lineMetrics = segmentLineMetrics
        )
    }

    val visualTransformation = remember(
        editorState.spans,
        editorState.unlockedSpoilerIds,
        editorState.canvasAlignment,
        w
    ) {
        RichTextVisualTransformation(
            spans = editorState.spans,
            defaultTextColor = w.text,
            quoteTextColor = w.text.copy(alpha = 0.88f),
            codeTextColor = w.text,
            numberedColor = w.accent,
            todoCheckedTextColor = w.muted,
            linkColor = w.link,
            unlockedSpoilerIds = editorState.unlockedSpoilerIds,
            defaultAlignment = editorState.canvasAlignment
        )
    }

    val baseTextStyle = remember(w, editorState.canvasAlignment) {
        TextStyle(
            fontFamily = WikiFonts.prose,
            fontSize = 16.sp,
            lineHeight = 26.sp,
            color = w.text,
            textAlign = editorState.canvasAlignment.toComposeTextAlign()
        )
    }

    val spoilerClipboardManager = remember(baseClipboard, editorState) {
        object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) {
                if (!editorState.hasSelection) {
                    baseClipboard.setText(annotatedString)
                    return
                }
                when (val res = editorState.prepareClipboardCopy()) {
                    is RichTextEditorState.SpoilerCopyResult.AllowPlain -> {
                        baseClipboard.setText(AnnotatedString(res.text))
                    }
                    is RichTextEditorState.SpoilerCopyResult.Masked -> {
                        baseClipboard.setText(AnnotatedString(res.text))
                        onCopyMaskedSpoiler()
                    }
                    is RichTextEditorState.SpoilerCopyResult.Blocked -> {
                        onCopySpoilerBlocked(res.blockingSpoiler)
                    }
                }
            }

            override fun getText(): AnnotatedString? = baseClipboard.getText()

            override fun hasText(): Boolean = baseClipboard.hasText()
        }
    }

    val hasInteractiveSpans = remember(editorState.spans) {
        editorState.spans.any { it.style is SpanStyle.Link || it.style is SpanStyle.Spoiler }
    }
    val currentReading by rememberUpdatedState(reading)
    val currentTextLayout by rememberUpdatedState(textLayoutResult)

    val quoteBgColor = if (w.isDark) w.surface else w.surface.copy(alpha = 0.9f)
    val quoteBarColor = w.accent
    val codeBgColor = w.surface
    val codeBorderColor = w.border.copy(alpha = 0.7f)
    val dividerColor = w.border

    CompositionLocalProvider(LocalClipboardManager provides spoilerClipboardManager) {
        BasicTextField(
            value = editorState.textFieldValue,
            onValueChange = { newVal -> editorState.onTextFieldValueChange(newVal) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minCanvasHeight)
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp)
                .testTag("workspace_rich_editor"),
            readOnly = reading,
            textStyle = baseTextStyle,
            cursorBrush = SolidColor(if (reading) Color.Transparent else w.accent),
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true
            ),
            onTextLayout = { textLayoutResult = it },
            decorationBox = { innerTextField ->
                val tapModifier = if (hasInteractiveSpans) {
                    Modifier.pointerInput(editorState, viewConfig) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                            val dx = up.position.x - down.position.x
                            val dy = up.position.y - down.position.y
                            val slop = viewConfig.touchSlop
                            if (dx * dx + dy * dy > slop * slop) return@awaitEachGesture
                            val lr = currentTextLayout ?: return@awaitEachGesture
                            val docLen = lr.layoutInput.text.text.length
                            if (docLen <= 0) return@awaitEachGesture
                            val offset = lr.getOffsetForPosition(up.position)
                            if (offset !in 0 until docLen) return@awaitEachGesture
                            val box = lr.getBoundingBox(offset)
                            if (up.position.x < box.left - 4f || up.position.x > box.right + 4f ||
                                up.position.y < box.top - 2f || up.position.y > box.bottom + 2f
                            ) {
                                return@awaitEachGesture
                            }
                            val lockedSpoiler = editorState.lockedSpoilerAtOffset(offset)
                            if (lockedSpoiler != null) {
                                onSpoilerTap(lockedSpoiler)
                                return@awaitEachGesture
                            }
                            if (currentReading) {
                                val linkUrl = editorState.linkAtOffset(offset)
                                if (linkUrl != null) {
                                    onOpenLink(linkUrl)
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(tapModifier)
                        .drawBehind {
                            if (cachedHighlights.isNotEmpty()) {
                                drawCachedHighlightRects(cachedHighlights)
                            }
                            if (cachedBlockDecorations != null) {
                                drawCachedBlockDecorations(
                                    cached = cachedBlockDecorations,
                                    activeHeadingDivider = activeHeadingDivider,
                                    reusableCheckPath = reusableCheckPath,
                                    quoteBgColor = quoteBgColor,
                                    quoteBarColor = quoteBarColor,
                                    codeBgColor = codeBgColor,
                                    codeBorderColor = codeBorderColor,
                                    dividerColor = dividerColor,
                                    bulletColor = w.accent,
                                    todoUncheckedBorderColor = w.border,
                                    todoUncheckedBgColor = w.bg,
                                    todoCheckedFillColor = w.accent
                                )
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            if (cachedSpoilers.isNotEmpty()) {
                                drawCachedHighlightRects(cachedSpoilers)
                            }
                        }
                ) {
                    if (editorState.text.isEmpty()) {
                        Text(
                            text = if (reading) "Empty entry." else "Start writing...",
                            style = proseStyle(16.sp, w.muted.copy(alpha = 0.55f))
                                .copy(textAlign = editorState.effectiveAlignment.toComposeTextAlign()),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    innerTextField()
                    val todos = cachedBlockDecorations?.todos
                    if (!todos.isNullOrEmpty()) {
                        todos.forEachIndexed { idx, td ->
                            val leftDp = with(density) { td.hitLeft.toDp() }
                            val topDp = with(density) { td.hitTop.toDp() }
                            val widthDp = with(density) { (td.hitRight - td.hitLeft).coerceAtLeast(24f).toDp() }
                            val heightDp = with(density) { (td.hitBottom - td.hitTop).coerceAtLeast(24f).toDp() }
                            Box(
                                Modifier
                                    .offset(x = leftDp, y = topDp)
                                    .size(width = widthDp, height = heightDp)
                                    .testTag("canvas_checkbox_$idx")
                                    .clickable(
                                        enabled = !reading,
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        editorState.toggleTodoAt(td.spanStart)
                                    }
                            )
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
