package com.likkho.diary

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
import com.likkho.diary.R
import kotlinx.coroutines.launch

private enum class Pop { NONE, COLOR, HIGHLIGHT, FONTS, SIZE, MEDIA, LINK, SPOILER, LOCK, BG, WIKI, LATEX, WORD }

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
) {
    val w = wiki(); val hooks = vm.hooks; val scope = rememberCoroutineScope()
    var pop by remember { mutableStateOf(Pop.NONE) }
    fun toggle(p: Pop) { pop = if (pop == p) Pop.NONE else p }
    val active = remember { mutableStateMapOf<FormatAction, Boolean>() }
    var hint by remember { mutableStateOf<String?>(null) }
    var sizePx by remember { mutableStateOf(16f) }; var sizeOn by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }; var spoiler by remember { mutableStateOf("") }; var lock by remember { mutableStateOf("") }
    var wikiQ by remember { mutableStateOf("") }; var wikiLang by remember { mutableStateOf("en") }; var wikiImgs by remember { mutableStateOf(true) }
    var wikiBusy by remember { mutableStateOf(false) }; var sugg by remember { mutableStateOf<List<WikiSuggestion>>(emptyList()) }
    var latex by remember { mutableStateOf("") }; var cat by remember { mutableStateOf(0) }
    var word by remember { mutableStateOf("") }; var wordBusy by remember { mutableStateOf(false) }; var wordResult by remember { mutableStateOf<WordAnalysisRecord?>(null) }
    var fontId by remember { mutableStateOf<String?>(null) }; var fontsOn by remember { mutableStateOf(false) }
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
                Text(if (isText) "Text Color (Applies to Selected Text)" else "Highlight Color (Applies to Selected Text)", style = sansStyle(11.sp, w.muted, FontWeight.SemiBold))
                (if (isText) TEXT_COLORS else HIGHLIGHT_COLORS).chunked(10).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        r.forEach { c ->
                            val reset = c == "default" || c == "transparent"
                            Box(Modifier.weight(1f).height(28.dp).background(if (reset) Color.White else parseHex(c) ?: Color.White).wikiBorder(w.border, 0.5.dp).clickable { if (isText) hooks.applyTextColor(c) else hooks.applyHighlight(c); pop = Pop.NONE }, contentAlignment = Alignment.Center) { if (reset) Text("✕", fontSize = 11.sp, color = Color.Black) }
                        }
                    }
                }
            }
            Pop.FONTS -> Pop1 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Custom Fonts", style = sansStyle(11.sp, w.muted, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    Box(Modifier.clickable { fontsOn = !fontsOn; if (!fontsOn) { fontId = null; hooks.applyFont(null) } }) { WikiSwitch(fontsOn) }
                }
                val builtin = listOf("builtin_serif" to "Cormorant", "builtin_sans" to "Jakarta Sans", "builtin_mono" to "Monospace") + vm.customFonts.map { it.id to it.name }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    builtin.forEach { (id, n) -> TChip(n, fontId == id) { fontId = id; hooks.applyFont(id) } }
                    TChip("+ English .ttf", false) { scope.launch { hooks.pickFont("en")?.let { vm.customFonts.add(it) } } }
                    TChip("+ हिन्दी .ttf", false) { scope.launch { hooks.pickFont("hi")?.let { vm.customFonts.add(it) } } }
                }
            }
            Pop.SIZE -> Pop1 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Text Size: ${sizePx.toInt()}px", style = sansStyle(11.sp, w.text, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                    WikiButton("Disable", { sizeOn = false; hooks.applyFontSizePx(null) }, kind = if (sizeOn) BtnKind.DANGER else BtnKind.OUTLINE, height = 30.dp, fontSize = 11)
                }
                WikiSlider(sizePx, { sizePx = it; sizeOn = true; hooks.applyFontSizePx(it.toInt()) }, 10f..48f)
            }
            Pop.MEDIA -> Pop1 {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WikiButton("Add Image", { pop = Pop.NONE; onAddImage() }, Modifier.weight(1f), icon = Icons.Outlined.AddPhotoAlternate)
                    WikiButton("Attach Audio", { pop = Pop.NONE; onAddAudio() }, Modifier.weight(1f), icon = Icons.Outlined.AudioFile)
                }
            }
            Pop.LINK -> Pop1 { PopInput(link, { link = it }, "https://...", "Link", KeyboardType.Uri) { hooks.applyLink(link); link = ""; pop = Pop.NONE } }
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
            Pop.LATEX -> Pop1 {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    WikiTextField(latex, { latex = it }, Modifier.weight(1f), "Tap symbols below or type LaTeX (e.g. \\frac{a}{b})...", mono = true)
                    if (latex.isNotEmpty()) IconBtn(Icons.Outlined.Close, "Clear formula input", { latex = "" }, size = 36.dp, iconSize = 16.dp)
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(w.bg).wikiBorder(w.hairline, 0.5.dp).padding(8.dp)) { Text(latex.ifEmpty { "Live preview" }, style = proseStyle(15.sp, if (latex.isEmpty()) w.muted else w.text, italic = true)) }
                WikiButton("Insert Formula", { if (latex.isNotBlank()) { hooks.insertLatex(latex); onInsertText(" [$latex] "); latex = ""; pop = Pop.NONE } }, Modifier.fillMaxWidth(), BtnKind.PRIMARY, Icons.Outlined.Functions, height = 36.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { MATH_CATEGORIES.forEachIndexed { i, c -> TChip(c.title, cat == i) { cat = i } } }
                MATH_CATEGORIES[cat].symbols.chunked(6).forEach { r -> Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { r.forEach { s -> Box(Modifier.weight(1f).height(36.dp).background(w.bg).wikiBorder(w.border, 0.5.dp).clickable { latex += s.latex }, contentAlignment = Alignment.Center) { Text(s.display, style = proseStyle(14.sp, w.text)) } }; repeat(6 - r.size) { Spacer(Modifier.weight(1f)) } } }
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
            ToolGlyph(painterResource(R.drawable.ic_font_aa), "Fonts", on = pop == Pop.FONTS) { toggle(Pop.FONTS) }
            ToolGlyph(Icons.Outlined.FormatSize, "Text Size", on = pop == Pop.SIZE) { toggle(Pop.SIZE) }
            TextGlyph("H1", FormatAction.H1, active) { fmt(it) }
            TextGlyph("H2", FormatAction.H2, active) { fmt(it) }
            TextGlyph("H3", FormatAction.H3, active) { fmt(it) }

            CategorySep()

            // Group 4: Character Formatting
            ToolGlyph(Icons.Outlined.FormatBold, "Bold", on = active[FormatAction.BOLD] == true) { fmt(FormatAction.BOLD) }
            ToolGlyph(Icons.Outlined.FormatItalic, "Italic", on = active[FormatAction.ITALIC] == true) { fmt(FormatAction.ITALIC) }
            ToolGlyph(Icons.Outlined.FormatUnderlined, "Underline", on = active[FormatAction.UNDERLINE] == true) { fmt(FormatAction.UNDERLINE) }
            ToolGlyph(Icons.Outlined.StrikethroughS, "Strikethrough", on = active[FormatAction.STRIKE] == true) { fmt(FormatAction.STRIKE) }
            ToolGlyph(Icons.Outlined.FormatColorText, "Text Color", on = pop == Pop.COLOR) { toggle(Pop.COLOR) }
            ToolGlyph(Icons.Outlined.Highlight, "Highlight Text", on = pop == Pop.HIGHLIGHT) { toggle(Pop.HIGHLIGHT) }

            CategorySep()

            // Group 5: Lists & Structural Blocks
            ToolGlyph(Icons.Outlined.FormatListBulleted, "Bullet List", on = active[FormatAction.BULLET] == true) { fmt(FormatAction.BULLET) }
            ToolGlyph(Icons.Outlined.FormatListNumbered, "Numbered List", on = active[FormatAction.NUMBERED] == true) { fmt(FormatAction.NUMBERED) }
            ToolGlyph(Icons.Outlined.CheckBox, "Checklist", on = active[FormatAction.TODO] == true) { fmt(FormatAction.TODO) }
            ToolGlyph(Icons.Outlined.FormatQuote, "Quote Block", on = active[FormatAction.QUOTE] == true) { fmt(FormatAction.QUOTE) }
            ToolGlyph(Icons.Outlined.Code, "Code Block", on = active[FormatAction.CODE] == true) { fmt(FormatAction.CODE) }
            ToolGlyph(Icons.Outlined.HorizontalRule, "Section Divider") { fmt(FormatAction.DIVIDER, false) }

            CategorySep()

            // Group 6: Alignment & Indents
            ToolGlyph(Icons.Outlined.FormatAlignLeft, "Align Left", on = active[FormatAction.ALIGN_LEFT] == true) { fmt(FormatAction.ALIGN_LEFT) }
            ToolGlyph(Icons.Outlined.FormatAlignCenter, "Align Center", on = active[FormatAction.ALIGN_CENTER] == true) { fmt(FormatAction.ALIGN_CENTER) }
            ToolGlyph(Icons.Outlined.FormatAlignRight, "Align Right", on = active[FormatAction.ALIGN_RIGHT] == true) { fmt(FormatAction.ALIGN_RIGHT) }
            ToolGlyph(Icons.Outlined.FormatAlignJustify, "Justify", on = active[FormatAction.JUSTIFY] == true) { fmt(FormatAction.JUSTIFY) }
            ToolGlyph(Icons.Outlined.FormatIndentIncrease, "Indent") { fmt(FormatAction.INDENT, false) }
            ToolGlyph(Icons.Outlined.FormatIndentDecrease, "Outdent") { fmt(FormatAction.OUTDENT, false) }

            CategorySep()

            // Group 7: Script & Mathematics
            ToolGlyph(Icons.Outlined.Superscript, "Superscript", on = active[FormatAction.SUPERSCRIPT] == true) { fmt(FormatAction.SUPERSCRIPT) }
            ToolGlyph(Icons.Outlined.Subscript, "Subscript", on = active[FormatAction.SUBSCRIPT] == true) { fmt(FormatAction.SUBSCRIPT) }
            ToolGlyph(Icons.Outlined.Functions, "LaTeX Formulas", on = pop == Pop.LATEX) { toggle(Pop.LATEX) }

            CategorySep()

            // Group 8: Research, Security & Tools
            ToolGlyph(Icons.Outlined.Link, "Link Selected Text", on = pop == Pop.LINK) { toggle(Pop.LINK) }
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
    a: FormatAction,
    active: Map<FormatAction, Boolean>,
    onClick: (FormatAction) -> Unit,
) {
    val w = wiki()
    val on = active[a] == true
    val textColor = if (on) w.accent else w.text.copy(alpha = 0.82f)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick(a) },
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
