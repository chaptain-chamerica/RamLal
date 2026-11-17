package com.likkho.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.likkho.app.R
import com.likkho.app.editor.FontRegistry
import com.likkho.app.editor.RichTextEditorState
import com.likkho.app.editor.model.TextAlignment
import kotlinx.coroutines.launch

private enum class Pop { NONE, COLOR, HIGHLIGHT, FONTS, SIZE, MEDIA, LINK, SPOILER, LOCK, BG, WIKI, WORD }

/**
 * Editorial formatting toolbar docked at bottom of canvas workspace.
 * Tools are unboxed glyphs organized into logical categories separated by hair-thin vertical lines.
 * The entire toolbar is separated from the main canvas by a continuous hair-thin line.
 */
@Composable
fun RichToolbar(
    vm: AppViewModel, lockPin: String?, bgSrc: String?, bgOpacity: Float, recording: Boolean,
    onRecordToggle: () -> Unit, onAddImage: () -> Unit, onAddAudio: () -> Unit, onLock: (String?) -> Unit,
    onBgOpacity: (Float) -> Unit, onPickBg: () -> Unit, onRemoveBg: () -> Unit, onEditBg: () -> Unit,
    onInsertText: (String) -> Unit, onSpoiler: (String) -> Unit,
    editorState: RichTextEditorState? = null,
) {
    val w = wiki(); val hooks = vm.hooks; val scope = rememberCoroutineScope()
    var pop by remember { mutableStateOf(Pop.NONE) }
    var link by remember { mutableStateOf("") }
    var pendingLinkRange by remember { mutableStateOf<androidx.compose.ui.text.TextRange?>(null) }
    fun toggle(p: Pop) {
        if (p != Pop.LINK) pendingLinkRange = null
        pop = if (pop == p) Pop.NONE else p
    }
    val active = remember { mutableStateMapOf<FormatAction, Boolean>() }
    var hint by remember { mutableStateOf<String?>(null) }
    var sizePx by remember { mutableStateOf(16f) }; var sizeOn by remember { mutableStateOf(false) }
    var spoiler by remember { mutableStateOf("") }; var lock by remember { mutableStateOf("") }
    var wikiQ by remember { mutableStateOf("") }; var wikiLang by remember { mutableStateOf("en") }; var wikiImgs by remember { mutableStateOf(true) }
    var wikiBusy by remember { mutableStateOf(false) }; var sugg by remember { mutableStateOf<List<WikiSuggestion>>(emptyList()) }
    var word by remember { mutableStateOf("") }; var wordBusy by remember { mutableStateOf(false) }; var wordResult by remember { mutableStateOf<WordAnalysisRecord?>(null) }
    var fontId by remember { mutableStateOf<String?>(null) }
    val fontPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val imported = vm.importCustomFont(uri)
                if (imported != null) {
                    editorState?.setFont(imported.id)
                    fontId = imported.id
                    hooks.applyFont(imported.id)
                    hint = "Added font: ${imported.name}"
                    kotlinx.coroutines.delay(2200)
                    hint = null
                } else {
                    vm.showStatus("Please select a valid .ttf or .otf font file.")
                }
            }
        }
    }
    LaunchedEffect(wikiQ, wikiLang) { sugg = if (wikiQ.isBlank()) emptyList() else runCatching { hooks.wikipediaSuggest(wikiQ, wikiLang) }.getOrDefault(emptyList()) }

    fun fmt(a: FormatAction, sticky: Boolean = true) { hooks.applyFormat(a); if (sticky) active[a] = !(active[a] ?: false) }

    Column(Modifier.fillMaxWidth().background(w.surface)) {
        // Hair-thin line separating toolbar from main canvas
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.border))

        hint?.let { Text(it, style = sansStyle(11.sp, w.accent, FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        if (recording) Row(Modifier.fillMaxWidth().background(w.danger).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("● Recording voice... tap mic icon to stop", style = sansStyle(12.sp, Color.White, FontWeight.SemiBold))
        }

        // ================= popovers =================
        when (pop) {
            Pop.COLOR, Pop.HIGHLIGHT -> Pop1 {
                val isText = pop == Pop.COLOR
                val activeColor = if (isText) editorState?.activeTextColor else editorState?.activeHighlightColor
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isText) "Text Color (Tap to Enable/Disable Typing Color)" else "Highlight Color (Tap to Enable/Disable Typing Highlight)",
                        style = sansStyle(11.sp, w.muted, FontWeight.SemiBold),
                        modifier = Modifier.weight(1f)
                    )
                    if (activeColor != null) {
                        Text("Active: $activeColor", style = monoStyle(10.sp, w.accent))
                    }
                }
                val colorPalette = if (isText) TEXT_COLORS else HIGHLIGHT_COLORS
                for (row in colorPalette.chunked(10)) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in row) {
                            val reset = c == "default" || c == "transparent"
                            val isSelected = if (reset) activeColor == null else activeColor.equals(c, ignoreCase = true)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(28.dp)
                                    .background(if (reset) Color.White else parseHex(c) ?: Color.White)
                                    .wikiBorder(if (isSelected) w.accent else w.border, if (isSelected) 1.5.dp else 0.5.dp)
                                    .clickable {
                                        if (isText) {
                                            val newCol = if (reset) null else c
                                            editorState?.setTextColor(newCol)
                                            hooks.applyTextColor(c)
                                        } else {
                                            val newHi = if (reset) null else c
                                            editorState?.setHighlightColor(newHi)
                                            hooks.applyHighlight(c)
                                        }
                                        pop = Pop.NONE
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (reset) Text("✕", fontSize = 11.sp, color = Color.Black)
                                else if (isSelected) Icon(
                                    Icons.Outlined.Check,
                                    null,
                                    tint = if (c in listOf("#fef08a", "#fde047", "#fed7aa", "#fef6e7")) Color.Black else Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
            Pop.FONTS -> Pop1 {
                val activeFont = if (editorState != null) editorState.activeFontId else fontId
                val allFonts = FontRegistry.builtinFonts + vm.customFonts.map { it.id to it.name }
                val activeFontName = allFonts.firstOrNull { it.first == activeFont }?.second
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Fonts (Tap to Enable/Disable)",
                        style = sansStyle(11.sp, w.muted, FontWeight.SemiBold),
                        modifier = Modifier.weight(1f)
                    )
                    if (activeFontName != null) {
                        Text("Active: $activeFontName", style = monoStyle(10.sp, w.accent))
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((id, name) in allFonts) {
                        val selected = activeFont == id
                        TChip(name, selected) {
                            if (selected) {
                                editorState?.setFont(null)
                                fontId = null
                                hooks.applyFont(null)
                            } else {
                                editorState?.setFont(id)
                                fontId = id
                                hooks.applyFont(id)
                            }
                            pop = Pop.NONE
                        }
                    }
                    TChip("+ Add .ttf", false) {
                        fontPickerLauncher.launch(arrayOf("*/*"))
                    }
                }
            }
            Pop.SIZE -> Pop1 {
                val currentPx = editorState?.activeFontSizePx ?: sizePx.toInt()
                Text("Text Size: ${currentPx}px", style = sansStyle(11.sp, w.text, FontWeight.SemiBold))
                WikiSlider(
                    currentPx.toFloat(),
                    {
                        sizePx = it
                        sizeOn = true
                        editorState?.setFontSizePx(it.toInt())
                        hooks.applyFontSizePx(it.toInt())
                    },
                    10f..48f
                )
            }
            Pop.MEDIA -> Pop1 {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WikiButton("Add Image", { pop = Pop.NONE; onAddImage() }, Modifier.weight(1f), icon = Icons.Outlined.AddPhotoAlternate)
                    WikiButton("Attach Audio", { pop = Pop.NONE; onAddAudio() }, Modifier.weight(1f), icon = Icons.Outlined.AudioFile)
                }
            }
            Pop.LINK -> Pop1 {
                val targetRange = pendingLinkRange ?: editorState?.selection
                val existingUrl = if (targetRange != null) editorState?.selectedLinkUrl(targetRange) else null
                val selectedSnippet = if (editorState != null && targetRange != null && targetRange.min < targetRange.max) {
                    val s = targetRange.min.coerceIn(0, editorState.text.length)
                    val e = targetRange.max.coerceIn(s, editorState.text.length)
                    editorState.text.substring(s, e).replace('\n', ' ').trim().take(32)
                } else {
                    ""
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (existingUrl != null) "Edit WebLink" else "Add WebLink",
                        style = sansStyle(11.sp, w.muted, FontWeight.SemiBold),
                        modifier = Modifier.weight(1f)
                    )
                    if (selectedSnippet.isNotEmpty()) {
                        Text("\"$selectedSnippet\"", style = monoStyle(10.sp, w.accent), maxLines = 1)
                    }
                }
                Spacer(Modifier.height(6.dp))
                fun submitLink() {
                    val trimmed = link.trim()
                    if (trimmed.isNotEmpty()) {
                        if (editorState != null && targetRange != null) {
                            editorState.applyLinkToRange(trimmed, targetRange)
                        }
                        hooks.applyLink(trimmed)
                        link = ""
                        pendingLinkRange = null
                        pop = Pop.NONE
                    }
                }
                PopInput(
                    link,
                    { link = it },
                    "https://...",
                    if (existingUrl != null) "Update" else "Link",
                    KeyboardType.Uri
                ) { submitLink() }
                if (existingUrl != null && targetRange != null) {
                    WikiButton(
                        "Delink",
                        {
                            editorState?.removeLinkFromRange(targetRange)
                            link = ""
                            pendingLinkRange = null
                            pop = Pop.NONE
                        },
                        Modifier.padding(top = 6.dp),
                        kind = BtnKind.DANGER,
                        icon = Icons.Outlined.LinkOff,
                        height = 32.dp,
                        fontSize = 11
                    )
                }
            }
            Pop.SPOILER -> Pop1 { PopInput(spoiler, { spoiler = it }, "Set Spoiler Passcode...", "Blur", KeyboardType.Text, pw = true) { onSpoiler(spoiler); spoiler = ""; pop = Pop.NONE } }
            Pop.LOCK -> Pop1 {
                PopInput(lock, { lock = it }, if (lockPin != null) "Change Diary Lock Passcode..." else "Set Diary Lock Passcode...", if (lockPin != null) "Update" else "Lock Diary", KeyboardType.Text, pw = true) { if (lock.isNotBlank()) { onLock(lock.trim()); lock = ""; pop = Pop.NONE } }
                if (lockPin != null) WikiButton("Remove Lock", { onLock(null); pop = Pop.NONE }, Modifier.padding(top = 6.dp), icon = Icons.Outlined.LockOpen, height = 32.dp, fontSize = 11)
            }
            Pop.BG -> Pop1 {
                Text(if (bgSrc != null) "Canvas background active" else "No canvas background", style = sansStyle(11.sp, w.muted, FontWeight.SemiBold))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WikiButton(if (bgSrc != null) "Change Image" else "Select Image", onPickBg, icon = Icons.Outlined.Wallpaper, height = 32.dp, fontSize = 11)
                    if (bgSrc != null) { WikiButton("Compress / Edit", onEditBg, icon = Icons.Outlined.Edit, height = 32.dp, fontSize = 11); WikiButton("Remove", onRemoveBg, kind = BtnKind.DANGER, icon = Icons.Outlined.Delete, height = 32.dp, fontSize = 11) }
                }
                Text("Opacity ${(bgOpacity * 100).toInt()}%", style = sansStyle(11.sp, w.text, FontWeight.Medium), modifier = Modifier.padding(top = 6.dp))
                WikiSlider(bgOpacity, onBgOpacity, 0.05f..1f)
            }
            Pop.WIKI -> Pop1 {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    WikiTextField(wikiQ, { wikiQ = it }, Modifier.weight(1f), if (wikiLang == "en") "Search English Wikipedia..." else "हिन्दी विकिपीडिया खोजें...", onDone = {})
                    WikiButton(if (wikiBusy) "Searching..." else "Search", { if (wikiQ.isNotBlank()) { wikiBusy = true; scope.launch { vm.showStatus(runCatching { hooks.wikipediaInsert(wikiQ.trim(), wikiLang, wikiImgs) }.getOrElse { "Wikipedia failed." }); wikiBusy = false; pop = Pop.NONE } } }, kind = BtnKind.PRIMARY, height = 36.dp, enabled = !wikiBusy)
                }
                if (sugg.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 6.dp).background(w.bg).wikiBorder(w.border, 0.5.dp)) {
                    sugg.forEach { s -> Column(Modifier.fillMaxWidth().clickable { wikiQ = s.title }.padding(8.dp)) { Text(s.title, style = sansStyle(12.sp, w.text, FontWeight.SemiBold)); Text(s.description, style = sansStyle(10.sp, w.muted, FontWeight.Normal)) }; HairlineDivider() }
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TChip("English", wikiLang == "en") { wikiLang = "en" }; TChip("हिन्दी", wikiLang == "hi") { wikiLang = "hi" }
                    TChip("With Images", wikiImgs) { wikiImgs = true }; TChip("Without Images", !wikiImgs) { wikiImgs = false }
                }
            }
            Pop.WORD -> Pop1 {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    WikiTextField(word, { word = it }, Modifier.weight(1f), "Enter or select a word to analyze...", onDone = {})
                    WikiButton(if (wordBusy) "Analyzing..." else "Analyze", { if (word.isNotBlank()) { wordBusy = true; scope.launch { wordResult = runCatching { hooks.analyzeWord(word.trim()) }.getOrNull(); wordBusy = false } } }, kind = BtnKind.PRIMARY, height = 36.dp, enabled = !wordBusy)
                }
            }
            Pop.NONE -> {}
        }
        if (pop != Pop.NONE) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.hairline))
        }

        // ================= main unboxed tools row =================
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Group 1: History
            ToolGlyph(Icons.Outlined.Undo, "Undo") { fmt(FormatAction.UNDO, false) }
            ToolGlyph(Icons.Outlined.Redo, "Redo") { fmt(FormatAction.REDO, false) }

            CategorySep()

            // Group 2: Capture & Media
            ToolGlyph(Icons.Outlined.Mic, "Record Voice Audio", on = recording, danger = recording) { onRecordToggle() }
            ToolGlyph(Icons.Outlined.PermMedia, "Media Picker", on = pop == Pop.MEDIA) { toggle(Pop.MEDIA) }
            ToolGlyph(Icons.Outlined.Wallpaper, "Canvas Background Image", on = pop == Pop.BG) { toggle(Pop.BG) }

            CategorySep()

            // Group 3: Typography & Sizing
            val fontToolOn = pop == Pop.FONTS || (editorState?.activeFontId ?: fontId) != null
            ToolGlyph(painterResource(R.drawable.ic_font_aa), "Fonts", on = fontToolOn) { toggle(Pop.FONTS) }
            val textPxOn = editorState?.activeFontSizePx != null || (editorState == null && sizeOn)
            ToolGlyph(Icons.Outlined.FormatSize, "Text Size", on = textPxOn) {
                if (textPxOn) {
                    sizeOn = false
                    editorState?.setFontSizePx(null)
                    hooks.applyFontSizePx(null)
                    if (pop == Pop.SIZE) pop = Pop.NONE
                } else {
                    sizeOn = true
                    editorState?.setFontSizePx(sizePx.toInt())
                    hooks.applyFontSizePx(sizePx.toInt())
                    pop = Pop.SIZE
                }
            }
            val h1On = editorState?.isH1Active ?: (active[FormatAction.H1] == true)
            val h2On = editorState?.isH2Active ?: (active[FormatAction.H2] == true)
            val h3On = editorState?.isH3Active ?: (active[FormatAction.H3] == true)
            TextGlyph("H1", h1On) {
                if (editorState != null) editorState.toggleHeading(1) else fmt(FormatAction.H1)
            }
            TextGlyph("H2", h2On) {
                if (editorState != null) editorState.toggleHeading(2) else fmt(FormatAction.H2)
            }
            TextGlyph("H3", h3On) {
                if (editorState != null) editorState.toggleHeading(3) else fmt(FormatAction.H3)
            }

            CategorySep()

            // Group 4: Character Formatting
            val boldOn = editorState?.isBoldActive ?: (active[FormatAction.BOLD] == true)
            val italicOn = editorState?.isItalicActive ?: (active[FormatAction.ITALIC] == true)
            val underlineOn = editorState?.isUnderlineActive ?: (active[FormatAction.UNDERLINE] == true)
            val strikeOn = editorState?.isStrikeActive ?: (active[FormatAction.STRIKE] == true)
            val colorOn = pop == Pop.COLOR || editorState?.activeTextColor != null
            val highlightOn = pop == Pop.HIGHLIGHT || editorState?.activeHighlightColor != null

            ToolGlyph(Icons.Outlined.FormatBold, "Bold", on = boldOn) {
                if (editorState != null) editorState.toggleBold() else fmt(FormatAction.BOLD)
            }
            ToolGlyph(Icons.Outlined.FormatItalic, "Italic", on = italicOn) {
                if (editorState != null) editorState.toggleItalic() else fmt(FormatAction.ITALIC)
            }
            ToolGlyph(Icons.Outlined.FormatUnderlined, "Underline", on = underlineOn) {
                if (editorState != null) editorState.toggleUnderline() else fmt(FormatAction.UNDERLINE)
            }
            ToolGlyph(Icons.Outlined.StrikethroughS, "Strikethrough", on = strikeOn) {
                if (editorState != null) editorState.toggleStrike() else fmt(FormatAction.STRIKE)
            }
            ToolGlyph(Icons.Outlined.FormatColorText, "Text Color", on = colorOn) { toggle(Pop.COLOR) }
            ToolGlyph(Icons.Outlined.Highlight, "Highlight Text", on = highlightOn) { toggle(Pop.HIGHLIGHT) }

            CategorySep()

            // Group 5: Lists & Structural Blocks
            val bulletOn = editorState?.isBulletActive ?: (active[FormatAction.BULLET] == true)
            val numberedOn = editorState?.isNumberedActive ?: (active[FormatAction.NUMBERED] == true)
            val todoOn = editorState?.isTodoActive ?: (active[FormatAction.TODO] == true)
            ToolGlyph(Icons.Outlined.FormatListBulleted, "Bullet List", on = bulletOn) {
                if (editorState != null) editorState.toggleBullet() else fmt(FormatAction.BULLET)
            }
            ToolGlyph(Icons.Outlined.FormatListNumbered, "Numbered List", on = numberedOn) {
                if (editorState != null) editorState.toggleNumbered() else fmt(FormatAction.NUMBERED)
            }
            ToolGlyph(Icons.Outlined.CheckBox, "Checklist", on = todoOn) {
                if (editorState != null) editorState.toggleTodo() else fmt(FormatAction.TODO)
            }
            val quoteOn = editorState?.isQuoteActive ?: (active[FormatAction.QUOTE] == true)
            val codeOn = editorState?.isCodeActive ?: (active[FormatAction.CODE] == true)
            ToolGlyph(Icons.Outlined.FormatQuote, "Quote Block", on = quoteOn) {
                if (editorState != null) editorState.toggleQuote() else fmt(FormatAction.QUOTE)
            }
            ToolGlyph(Icons.Outlined.Code, "Code Block", on = codeOn) {
                if (editorState != null) editorState.toggleCode() else fmt(FormatAction.CODE)
            }
            ToolGlyph(Icons.Outlined.HorizontalRule, "Section Divider") {
                if (editorState != null) editorState.insertDivider() else fmt(FormatAction.DIVIDER, false)
            }

            CategorySep()

            // Group 6: Alignment & Indents
            val effAlign = editorState?.effectiveAlignment
            ToolGlyph(
                Icons.Outlined.FormatAlignLeft,
                "Align Left",
                on = effAlign == TextAlignment.LEFT || (editorState == null && active[FormatAction.ALIGN_LEFT] == true)
            ) {
                if (editorState != null) editorState.setAlignment(TextAlignment.LEFT) else fmt(FormatAction.ALIGN_LEFT)
            }
            ToolGlyph(
                Icons.Outlined.FormatAlignCenter,
                "Align Center",
                on = effAlign == TextAlignment.CENTER || (editorState == null && active[FormatAction.ALIGN_CENTER] == true)
            ) {
                if (editorState != null) editorState.setAlignment(TextAlignment.CENTER) else fmt(FormatAction.ALIGN_CENTER)
            }
            ToolGlyph(
                Icons.Outlined.FormatAlignRight,
                "Align Right",
                on = effAlign == TextAlignment.RIGHT || (editorState == null && active[FormatAction.ALIGN_RIGHT] == true)
            ) {
                if (editorState != null) editorState.setAlignment(TextAlignment.RIGHT) else fmt(FormatAction.ALIGN_RIGHT)
            }
            ToolGlyph(
                Icons.Outlined.FormatAlignJustify,
                "Justify",
                on = effAlign == TextAlignment.JUSTIFY || (editorState == null && active[FormatAction.JUSTIFY] == true)
            ) {
                if (editorState != null) editorState.setAlignment(TextAlignment.JUSTIFY) else fmt(FormatAction.JUSTIFY)
            }
            val indentOn = (editorState?.currentIndentLevel ?: 0) > 0
            ToolGlyph(Icons.Outlined.FormatIndentIncrease, "Indent", on = indentOn) {
                if (editorState != null) editorState.increaseIndent() else fmt(FormatAction.INDENT, false)
            }
            ToolGlyph(Icons.Outlined.FormatIndentDecrease, "Outdent") {
                if (editorState != null) editorState.decreaseIndent() else fmt(FormatAction.OUTDENT, false)
            }

            CategorySep()

            // Group 7: Script & Mathematics
            val superOn = editorState?.isSuperscriptActive ?: (active[FormatAction.SUPERSCRIPT] == true)
            val subOn = editorState?.isSubscriptActive ?: (active[FormatAction.SUBSCRIPT] == true)
            ToolGlyph(Icons.Outlined.Superscript, "Superscript", on = superOn) {
                if (editorState != null) editorState.toggleSuperscript() else fmt(FormatAction.SUPERSCRIPT)
            }
            ToolGlyph(Icons.Outlined.Subscript, "Subscript", on = subOn) {
                if (editorState != null) editorState.toggleSubscript() else fmt(FormatAction.SUBSCRIPT)
            }

            CategorySep()

            // Group 8: Research, Security & Tools
            val linkToolOn = pop == Pop.LINK || editorState?.hasLinkOnSelection == true
            ToolGlyph(Icons.Outlined.Link, "WebLink (Selected Text)", on = linkToolOn) {
                if (editorState == null) {
                    toggle(Pop.LINK)
                } else if (pop == Pop.LINK) {
                    pendingLinkRange = null
                    pop = Pop.NONE
                } else if (!editorState.hasSelection) {
                    scope.launch {
                        hint = "Select text first to add a link."
                        kotlinx.coroutines.delay(2200)
                        hint = null
                    }
                } else {
                    val sel = editorState.selection
                    val range = androidx.compose.ui.text.TextRange(sel.min, sel.max)
                    pendingLinkRange = range
                    link = editorState.selectedLinkUrl(range).orEmpty()
                    pop = Pop.LINK
                }
            }
            ToolGlyph(Icons.Outlined.VisibilityOff, "Spoiler", on = pop == Pop.SPOILER) { toggle(Pop.SPOILER) }
            ToolGlyph(if (lockPin != null) Icons.Outlined.Lock else Icons.Outlined.LockOpen, if (lockPin != null) "Diary Locked" else "Lock This Diary Entry", on = pop == Pop.LOCK || lockPin != null) { toggle(Pop.LOCK) }
            ToolGlyph(Icons.Outlined.Public, "Wikipedia (English & Hindi)", on = pop == Pop.WIKI) { toggle(Pop.WIKI) }
            ToolGlyph(Icons.Outlined.Spellcheck, "Word Analysis", on = pop == Pop.WORD) { toggle(Pop.WORD) }
            ToolGlyph(Icons.Outlined.Schedule, "Insert Date/Time Stamp") { onInsertText(" " + fmtDateTimeShort(System.currentTimeMillis()) + " ") }
            ToolGlyph(Icons.Outlined.UploadFile, "Import Document") { scope.launch { hint = "Importing..."; hint = runCatching { hooks.importDocument() }.getOrElse { "Import failed." }; kotlinx.coroutines.delay(2500); hint = null } }
        }
    }
    wordResult?.let { WordAnalysisDialog(it) { wordResult = null } }
}

@Composable
private fun Pop1(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(wiki().bg).padding(10.dp), content = content)
}

/**
 * Hair-thin vertical line separating tool categories (without labels).
 */
@Composable
private fun CategorySep() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .width(0.5.dp)
            .height(18.dp)
            .background(wiki().border.copy(alpha = 0.55f))
    )
}

/**
 * Unboxed tool icon glyph: crisp, borderless, thin SVG glyph appearance.
 * Active state is indicated by the accent color and a subtle micro-dot.
 */
@Composable
private fun ToolGlyph(
    icon: ImageVector,
    desc: String,
    on: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val w = wiki()
    val tint = when {
        danger -> w.danger
        on -> w.accent
        else -> w.text.copy(alpha = 0.78f)
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
            if (on) {
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(if (danger) w.danger else w.accent)
                )
            }
        }
    }
}

@Composable
private fun ToolGlyph(
    painter: Painter,
    desc: String,
    on: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val w = wiki()
    val tint = when {
        danger -> w.danger
        on -> w.accent
        else -> w.text.copy(alpha = 0.78f)
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painter,
                contentDescription = desc,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
            if (on) {
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(if (danger) w.danger else w.accent)
                )
            }
        }
    }
}

/**
 * Unboxed typographic heading glyph (H1, H2, H3).
 */
@Composable
private fun TextGlyph(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    val w = wiki()
    val textColor = if (on) w.accent else w.text.copy(alpha = 0.82f)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label,
                style = serifStyle(15.sp, textColor, if (on) FontWeight.Bold else FontWeight.Medium),
            )
            if (on) {
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(w.accent)
                )
            }
        }
    }
}

@Composable
private fun TChip(text: String, sel: Boolean, onClick: () -> Unit) {
    val w = wiki()
    Box(
        Modifier
            .height(30.dp)
            .background(if (sel) w.accent else w.surface)
            .wikiBorder(if (sel) w.accent else w.border, 0.5.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = sansStyle(11.sp, if (sel) Color.White else w.text, FontWeight.SemiBold), maxLines = 1)
    }
}

@Composable
private fun PopInput(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    action: String,
    kb: KeyboardType,
    pw: Boolean = false,
    onAction: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        WikiTextField(value, onChange, Modifier.weight(1f), hint, password = pw, keyboardType = kb, onDone = onAction)
        WikiButton(action, onAction, kind = BtnKind.PRIMARY, height = 36.dp)
    }
}
