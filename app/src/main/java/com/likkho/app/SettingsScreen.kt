package com.likkho.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

const val APP_VERSION = "1.5.0"

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val w = wiki()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val hooks = vm.hooks

    var toast by remember { mutableStateOf<String?>(null) }
    fun showToast(m: String) { toast = m; scope.launch { delay(2600); if (toast == m) toast = null } }

    var showLegal by remember { mutableStateOf(false) }
    var showSavedWords by remember { mutableStateOf(false) }
    var showPasswords by remember { mutableStateOf(false) }
    var showBackedUp by remember { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    var showMic by remember { mutableStateOf(false) }
    var showPasscodeModal by remember { mutableStateOf(false) }
    var configuringSecondary by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf("") }
    var manualUpdate by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var passwordCount by remember { mutableStateOf(hooks.loadPasswords(vm.vaultMode).size) }

    val activePin = if (vm.vaultMode == VaultMode.DECOY) vm.secondaryPasscode else vm.savedPasscode

    // ---------- sub pages ----------
    when {
        showBackedUp -> { BackedUpDiariesScreen(vm, onClose = { showBackedUp = false }); return }
        showStorage -> { StoragePage(vm, onBack = { showStorage = false }); return }
        showLegal -> { LegalPage(vm, toast, ::showToast) { showLegal = false }; return }
        showSavedWords -> { SavedWordsPage(vm) { showSavedWords = false }; return }
        showPasswords -> { PasswordsPage(vm, toast, ::showToast) { showPasswords = false; passwordCount = hooks.loadPasswords(vm.vaultMode).size }; return }
    }

    BackHandler { vm.navigateTo(Route.HOME) }

    fun openPasscodeModal(secondary: Boolean) {
        configuringSecondary = secondary
        pinInput = if (secondary) vm.secondaryPasscode.orEmpty() else vm.savedPasscode.orEmpty()
        pinError = ""
        showPasscodeModal = true
    }

    Column(Modifier.fillMaxSize().background(w.bg)) {
        ScreenHeader("Settings", { vm.navigateTo(Route.HOME) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            toast?.let { InlineToast(it) }
            Column(Modifier.fillMaxWidth().background(w.bg).wikiBorder(w.border)) {
                // 1 dark
                SettingsRow(
                    icon = if (vm.darkMode) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                    iconTint = if (vm.darkMode) Color(0xFF6699FF) else Color(0xFFAC6600),
                    title = "Dark Theme", subtitle = if (vm.darkMode) "Dark Mode" else "Light Mode",
                    trailing = { WikiSwitch(vm.darkMode) }, onClick = { vm.darkMode = !vm.darkMode },
                )
                HairlineDivider()
                // 1b fullscreen
                SettingsRow(
                    icon = if (vm.fullScreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    title = "Full Screen Mode", subtitle = if (vm.fullScreen) "Immersive full screen active" else "Hide status & navigation bars",
                    trailing = { WikiSwitch(vm.fullScreen) }, onClick = { vm.fullScreen = !vm.fullScreen },
                )
                HairlineDivider()
                // 2 passcode (tap = set, HOLD 10s in primary with passcode = covert secondary vault)
                var holdTriggered by remember { mutableStateOf(false) }
                SettingsRow(
                    icon = Icons.Outlined.VpnKey, title = "Set Passcode",
                    subtitle = if (activePin != null) "Passcode enabled" else "Set a passcode for lock",
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = w.muted) },
                    enabled = false,
                    onClick = {},
                    modifier = Modifier.pointerInput(vm.vaultMode, vm.savedPasscode, vm.secondaryPasscode) {
                        detectTapGestures(
                            onPress = {
                                holdTriggered = false
                                var job: Job? = null
                                if (vm.vaultMode == VaultMode.PRIMARY && vm.savedPasscode != null) {
                                    job = scope.launch {
                                        delay(10_000)
                                        holdTriggered = true
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        openPasscodeModal(true)
                                    }
                                }
                                tryAwaitRelease()
                                job?.cancel()
                            },
                            onTap = { if (holdTriggered) holdTriggered = false else openPasscodeModal(false) },
                        )
                    },
                )
                HairlineDivider()
                // 3 biometric (hidden in secondary vault)
                if (vm.vaultMode == VaultMode.PRIMARY) {
                    val bioAvailability = remember(vm.vaultMode, vm.biometricsEnabled) {
                        hooks.checkBiometricAvailability()
                    }
                    var bioBusy by remember { mutableStateOf(false) }
                    val bioHint = when (bioAvailability) {
                        BiometricAvailability.Available -> null
                        is BiometricAvailability.NotEnrolled -> bioAvailability.hint
                        is BiometricAvailability.Unavailable -> bioAvailability.hint
                    }
                    val rowEnabled = !bioBusy && (bioAvailability is BiometricAvailability.Available || vm.biometricsEnabled)
                    val subtitleText = when {
                        bioHint != null && !vm.biometricsEnabled -> bioHint
                        vm.biometricsEnabled -> "Fingerprint / Face unlock enabled"
                        else -> "Use device biometrics with passcode"
                    }
                    SettingsRow(
                        icon = Icons.Outlined.Fingerprint, title = "Biometric Unlock",
                        subtitle = subtitleText,
                        trailing = { WikiSwitch(vm.biometricsEnabled) },
                        enabled = rowEnabled,
                        onClick = {
                            if (vm.savedPasscode == null) {
                                configuringSecondary = false; pinInput = ""
                                pinError = "Please set a Passcode first to enable Biometric unlock."
                                showPasscodeModal = true
                            } else if (!vm.biometricsEnabled) {
                                bioBusy = true
                                hooks.enableBiometric { _, msg ->
                                    bioBusy = false
                                    vm.refreshBiometricState()
                                    showToast(msg ?: "Biometric status updated.")
                                }
                            } else {
                                bioBusy = true
                                hooks.disableBiometric { _, msg ->
                                    bioBusy = false
                                    vm.refreshBiometricState()
                                    showToast(msg ?: "Biometric unlock disabled.")
                                }
                            }
                        },
                    )
                    HairlineDivider()
                }
                // 3b passwords
                SettingsRow(
                    icon = Icons.Outlined.Key, title = "Passwords",
                    subtitle = if (passwordCount > 0) "$passwordCount saved password chip${if (passwordCount > 1) "s" else ""}" else "Manage saved account & service passwords",
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = w.muted) }, onClick = { showPasswords = true },
                )
                HairlineDivider()
                // 4 backup
                SettingsRow(Icons.Outlined.FolderOpen, title = "Backup & Restore", subtitle = "Backup and restore diary text & media",
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = w.muted) }, onClick = { vm.navigateTo(Route.BACKUP) })
                HairlineDivider()
                // 4b backed-up diaries
                SettingsRow(Icons.Outlined.MenuBook, title = "Backed-up Diaries", subtitle = "Read, inspect & delete diaries saved in your backup vault",
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = w.muted) }, onClick = { showBackedUp = true })
                HairlineDivider()
                // 5 saved words
                SettingsRow(Icons.Outlined.AutoStories, title = "Saved Word Analysis", subtitle = "View saved words in alphabetical order (A–Z)",
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = w.muted) }, onClick = { showSavedWords = true })
                HairlineDivider()
                // 6 audio settings
                val ms = vm.micSettings
                SettingsRow(Icons.Outlined.Mic, title = "Audio Recording Settings",
                    subtitle = "Format: .${ms.format} · ${(ms.sampleRate / 1000.0).toString().removeSuffix(".0")} kHz · ${ms.bitRate / 1000} kbps",
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = w.muted) }, onClick = { showMic = true })
                HairlineDivider()
                // 7 legal
                SettingsRow(Icons.Outlined.Description, title = "Legal, About & Data Notice", subtitle = "Privacy Policy, Terms, About, Licenses, Export & Clear Data",
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = w.muted) }, onClick = { showLegal = true })
                HairlineDivider()
                // 7b storage
                SettingsRow(
                    icon = Icons.Outlined.DeleteSweep,
                    title = "Storage",
                    subtitle = "See what uses space",
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = w.muted) },
                    onClick = { showStorage = true },
                )
                HairlineDivider()
                // 8 update
                SettingsRow(
                    icon = if (checkingUpdate) Icons.Outlined.Refresh else Icons.Outlined.AutoAwesome,
                    title = "Check for Updates", titleSuffix = "v$APP_VERSION",
                    subtitle = if (checkingUpdate) "Checking GitHub Releases..." else "Tap to check for new APK versions on GitHub",
                    trailing = { Icon(Icons.Outlined.ChevronRight, null, tint = w.muted) },
                    enabled = !checkingUpdate,
                    onClick = {
                        checkingUpdate = true
                        scope.launch {
                            when (val r = runCatching { hooks.checkForUpdate(true) }.getOrDefault(UpdateCheck.Failed)) {
                                is UpdateCheck.Available -> manualUpdate = r.info
                                is UpdateCheck.UpToDate -> showToast("Likkho is up to date (v${r.version}).")
                                UpdateCheck.Offline -> showToast("Internet offline. Connect to check for updates.")
                                UpdateCheck.Failed -> showToast("Could not reach GitHub Releases. Try again later.")
                            }
                            checkingUpdate = false
                        }
                    },
                )
            }
        }
    }

    // ---------- Audio recording settings dialog ----------
    if (showMic) {
        WikiDialog(onDismiss = { showMic = false }, title = "Audio Recording Settings", titleIcon = Icons.Outlined.Mic, subtitle = "Configure recording format, sample rate & bitrate") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    FieldLabel("Recording Format")
                    AUDIO_FORMATS.chunked(4).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowItems.forEach { f ->
                                val sel = vm.micSettings.format == f
                                Box(
                                    Modifier.weight(1f).height(34.dp).background(if (sel) w.accent else w.surface).wikiBorder(if (sel) w.accent else w.border)
                                        .clickable { vm.micSettings = vm.micSettings.copy(format = f) },
                                    contentAlignment = Alignment.Center,
                                ) { Text(".$f", style = monoStyle(12.sp, if (sel) Color.White else w.text, FontWeight.SemiBold)) }
                            }
                            repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Sample Rate")
                        WikiSelect(SAMPLE_RATES, vm.micSettings.sampleRate) { vm.micSettings = vm.micSettings.copy(sampleRate = it) }
                    }
                    Column(Modifier.weight(1f)) {
                        FieldLabel("Bitrate")
                        WikiSelect(BIT_RATES, vm.micSettings.bitRate) { vm.micSettings = vm.micSettings.copy(bitRate = it) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WikiButton("Done", { showMic = false; showToast("Audio recording settings saved.") }, kind = BtnKind.PRIMARY)
                }
            }
        }
    }

    // ---------- Set passcode dialog (same UI for primary & covert secondary) ----------
    if (showPasscodeModal) {
        val hasExisting = if (configuringSecondary) vm.secondaryPasscode != null else vm.savedPasscode != null
        fun save() {
            val cleaned = pinInput.filter { it.isDigit() }
            if (cleaned.length < 4 || cleaned.length > 6) { pinError = "Passcode must be 4 to 6 digits."; return }
            val ok = if (configuringSecondary) vm.updateSecondaryPasscode(cleaned) else vm.updatePasscode(cleaned)
            if (!ok) { pinError = "Passcode must be different from existing passcode."; return }
            showPasscodeModal = false; configuringSecondary = false; showToast("Passcode saved.")
        }
        WikiDialog(onDismiss = { showPasscodeModal = false; configuringSecondary = false }, title = "Set Passcode", showClose = false, maxWidth = 320.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter a 4 to 6 digit PIN.", style = sansStyle(12.sp, w.muted, FontWeight.Normal))
                WikiTextField(pinInput, { pinInput = it.filter { c -> c.isDigit() }.take(6); pinError = "" }, Modifier.fillMaxWidth(), "••••", password = true, keyboardType = KeyboardType.NumberPassword, height = 44.dp, onDone = { save() })
                if (pinError.isNotEmpty()) Text(pinError, style = sansStyle(12.sp, w.danger, FontWeight.Medium))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (hasExisting) {
                        WikiButton("Remove", {
                            if (configuringSecondary) vm.updateSecondaryPasscode(null) else { vm.updatePasscode(null) }
                            showPasscodeModal = false; configuringSecondary = false; showToast("Passcode removed.")
                        }, kind = BtnKind.OUTLINE, icon = Icons.Outlined.Delete, height = 36.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    WikiButton("Cancel", { showPasscodeModal = false; configuringSecondary = false }, height = 36.dp)
                    Spacer(Modifier.width(8.dp))
                    WikiButton("Save", { save() }, kind = BtnKind.PRIMARY, height = 36.dp)
                }
            }
        }
    }

    manualUpdate?.let { UpdateDialog(it, hooks) { manualUpdate = null } }
}

// ======================================================================================
// Shared row + select + toast
// ======================================================================================
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = wiki().accent,
    titleSuffix: String? = null,
    enabled: Boolean = true,
) {
    val w = wiki()
    Row(
        modifier.fillMaxWidth().then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(w.surface).wikiBorder(w.border), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = serifStyle(16.sp, w.text))
                if (titleSuffix != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(titleSuffix, style = monoStyle(10.sp, w.accent, FontWeight.SemiBold), modifier = Modifier.background(w.accent.copy(alpha = 0.10f)).wikiBorder(w.accent).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Text(subtitle, style = sansStyle(12.sp, w.muted, FontWeight.Normal))
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
fun InlineToast(text: String) {
    val w = wiki()
    Row(Modifier.fillMaxWidth().background(w.success.copy(alpha = 0.10f)).wikiBorder(w.success).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Check, null, tint = w.success, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = sansStyle(12.sp, w.text, FontWeight.Medium))
    }
}

/** Native-looking <select>: bordered box + dropdown list. */
@Composable
fun WikiSelect(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val w = wiki()
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().height(36.dp).background(w.surface).wikiBorder(w.border).clickable { open = true }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(options.firstOrNull { it.first == selected }?.second ?: "", style = sansStyle(12.sp, w.text, FontWeight.Normal), modifier = Modifier.weight(1f))
            Text("▾", style = sansStyle(12.sp, w.muted))
        }
        DropdownMenu(open, { open = false }, shape = RectangleShape, containerColor = w.bg, border = androidx.compose.foundation.BorderStroke(0.5.dp, w.border)) {
            options.forEach { (v, label) ->
                DropdownMenuItem(text = { Text(label, style = sansStyle(12.sp, if (v == selected) w.accent else w.text, FontWeight.Medium)) }, onClick = { onSelect(v); open = false })
            }
        }
    }
}

// ======================================================================================
// Legal page
// ======================================================================================
@Composable
private fun LegalPage(vm: AppViewModel, toast: String?, showToast: (String) -> Unit, onBack: () -> Unit) {
    val w = wiki()
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(w.bg)) {
        ScreenHeader("Legal, About & Data Notice", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            toast?.let { InlineToast(it) }
            LEGAL_SECTIONS.forEach { sec ->
                Column(Modifier.fillMaxWidth().background(w.bg).wikiBorder(w.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(sec.heading, style = serifStyle(18.sp, w.text), modifier = Modifier.weight(1f))
                        sec.lastUpdated?.let { Text("Updated: $it", style = monoStyle(10.sp, w.muted)) }
                    }
                    sec.paragraphs.forEach { Text(it, style = sansStyle(12.sp, w.text, FontWeight.Normal), lineHeight = 19.sp) }
                    sec.bulletPoints.forEach { Row { Text("•  ", style = sansStyle(12.sp, w.accent, FontWeight.Bold)); Text(it, style = sansStyle(12.sp, w.text, FontWeight.Normal), lineHeight = 19.sp) } }
                    if (sec.links.isNotEmpty()) {
                        Text("Reference Links:", style = sansStyle(11.sp, w.muted, FontWeight.SemiBold))
                        sec.links.forEach { l ->
                            Row(Modifier.fillMaxWidth().background(w.surface).wikiBorder(w.hairline).clickable { vm.hooks.openUrl(l.url) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.OpenInNew, null, tint = w.link, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(l.label, style = sansStyle(12.sp, w.link, FontWeight.Medium))
                            }
                        }
                    }
                    if (sec.id == "data-deletion-export") {
                        WikiButton(if (exporting) "Exporting ZIP Archive..." else "Export All Data (.zip)", {
                            exporting = true
                            scope.launch { showToast(runCatching { vm.hooks.exportAllDataZip() }.getOrElse { "Failed to export ZIP archive." }); exporting = false }
                        }, Modifier.fillMaxWidth(), BtnKind.PRIMARY, Icons.Outlined.Download, enabled = !exporting && !clearing)
                        WikiButton("Clear All Data", { confirmClear = true }, Modifier.fillMaxWidth(), BtnKind.DANGER, Icons.Outlined.Delete, enabled = !exporting && !clearing)
                    }
                }
            }
        }
    }
    if (confirmClear) {
        WikiDialog(onDismiss = { if (!clearing) confirmClear = false }, title = "Clear All Data?", titleIcon = Icons.Outlined.Warning, titleIconTint = w.danger, showClose = !clearing) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Are you sure you want to permanently delete all data from Likkho (all diaries, images, audio recordings, custom fonts, saved word analyses, and passcodes)? This action cannot be undone.",
                    style = sansStyle(12.sp, w.text, FontWeight.Normal), lineHeight = 18.sp,
                )
                ConfirmButtons(confirmText = if (clearing) "Deleting..." else "Delete All Data", confirmIcon = Icons.Outlined.Delete, busy = clearing,
                    onCancel = { confirmClear = false },
                    onConfirm = {
                        clearing = true
                        scope.launch { vm.clearAllAppData(); delay(900); clearing = false; confirmClear = false; onBack(); vm.navigateTo(Route.HOME) }
                    })
            }
        }
    }
}

// ======================================================================================
// Saved Word Analysis page (A-Z)
// ======================================================================================
@Composable
private fun SavedWordsPage(vm: AppViewModel, onBack: () -> Unit) {
    val w = wiki()
    var words by remember { mutableStateOf(vm.hooks.loadSavedWords(vm.vaultMode)) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<WordAnalysisRecord?>(null) }
    val filtered = words.filter { it.word.lowercase().contains(query.trim().lowercase()) }
    BackHandler { if (searchOpen) { searchOpen = false; query = "" } else onBack() }

    Column(Modifier.fillMaxSize().background(w.bg)) {
        Column(Modifier.fillMaxWidth().background(w.surface)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searchOpen) {
                    IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Close Search", { searchOpen = false; query = "" }, size = 48.dp, bordered = false, bg = Color.Transparent)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Search, null, tint = w.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    WikiTextField(query, { query = it }, Modifier.weight(1f), "Search saved words...", onDone = {})
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Icons.Outlined.Close, "Close search", { searchOpen = false; query = "" }, size = 36.dp, tint = w.muted, iconSize = 16.dp)
                } else {
                    IconBtn(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Settings", onBack, size = 48.dp, bordered = false, bg = Color.Transparent)
                    Spacer(Modifier.width(4.dp))
                    Text("Saved Word Analysis", style = serifStyle(20.sp, w.text), modifier = Modifier.weight(1f))
                    IconBtn(Icons.Outlined.Search, "Search saved words", { searchOpen = true })
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.border))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            when {
                words.isEmpty() -> Column(Modifier.fillMaxWidth().background(w.bg).wikiBorder(w.border)) {
                    EmptyState(Icons.Outlined.AutoStories, "No Saved Words Yet", "Words analyzed on the canvas will automatically save here in alphabetical order (A–Z) for offline viewing.", Modifier.padding(vertical = 40.dp))
                }
                filtered.isEmpty() -> Box(Modifier.fillMaxWidth().wikiBorder(w.border).padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    Text("No saved word matches \"$query\".", style = sansStyle(12.sp, w.muted, FontWeight.Normal))
                }
                else -> Column(Modifier.fillMaxWidth().wikiBorder(w.border)) {
                    filtered.forEachIndexed { i, item ->
                        if (i > 0) HairlineDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { selected = item }) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(item.word, style = serifStyle(18.sp, w.text))
                                    Spacer(Modifier.width(8.dp))
                                    Text(item.phoneticTranscription, style = monoStyle(11.sp, w.accent))
                                }
                                Text(item.definitions.firstOrNull()?.definition ?: item.partOfSpeech.joinToString(", "), style = sansStyle(12.sp, w.muted, FontWeight.Normal), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            WikiButton("View", { selected = item }, height = 34.dp, fontSize = 11)
                            Spacer(Modifier.width(6.dp))
                            IconBtn(Icons.Outlined.Delete, "Delete saved word", { words = vm.hooks.deleteSavedWord(item.word, vm.vaultMode) }, size = 34.dp, tint = w.danger, iconSize = 16.dp)
                        }
                    }
                }
            }
        }
    }
    selected?.let { WordAnalysisDialog(it) { selected = null } }
}

// ======================================================================================
// Passwords page: 3-column chips, + Add, view popup
// ======================================================================================
@Composable
private fun PasswordsPage(vm: AppViewModel, toast: String?, showToast: (String) -> Unit, onBack: () -> Unit) {
    val w = wiki()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var items by remember { mutableStateOf(vm.hooks.loadPasswords(vm.vaultMode)) }
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<SavedPassword?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var copiedId by remember { mutableStateOf<String?>(null) }
    BackHandler { onBack() }

    fun persist(list: List<SavedPassword>) { items = list; vm.hooks.persistPasswords(vm.vaultMode, list) }

    Column(Modifier.fillMaxSize().background(w.bg)) {
        ScreenHeader("Passwords", onBack, trailing = {
            WikiButton("Add", { name = ""; value = ""; showText = false; addError = ""; showAdd = true }, kind = BtnKind.PRIMARY, icon = Icons.Outlined.Add, height = 36.dp)
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            toast?.let { InlineToast(it) }
            if (items.isEmpty()) {
                Column(Modifier.fillMaxWidth().wikiBorder(w.border)) {
                    EmptyState(Icons.Outlined.Key, "No passwords saved", "Tap the + Add button above to save your first account or service password.", Modifier.padding(vertical = 40.dp))
                }
            } else {
                Text("SAVED PASSWORD CHIPS (${items.size})", style = sansStyle(11.sp, w.muted, FontWeight.SemiBold))
                items.chunked(3).forEach { rowItems ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { p ->
                            Column(
                                Modifier.weight(1f).heightIn(min = 78.dp).background(w.surface).wikiBorder(w.border)
                                    .clickable { viewing = p; revealed = false; copiedId = null }.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Box(Modifier.size(24.dp).background(w.bg).wikiBorder(w.border), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Key, null, tint = w.accent, modifier = Modifier.size(14.dp))
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(p.name, style = sansStyle(12.sp, w.text, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("••••••", style = monoStyle(10.sp, w.muted))
                            }
                        }
                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    if (showAdd) {
        fun save() {
            if (name.isBlank()) { addError = "Please enter a name."; return }
            if (value.isEmpty()) { addError = "Please enter a password."; return }
            val item = SavedPassword("pwd_${System.currentTimeMillis()}", name.trim(), value, System.currentTimeMillis())
            persist(items + item); showAdd = false; showToast("Password \"${item.name}\" saved.")
        }
        WikiDialog(onDismiss = { showAdd = false }, title = "Add Password", titleIcon = Icons.Outlined.Key, maxWidth = 340.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (addError.isNotEmpty()) StatusBanner(addError, BannerKind.ERROR)
                Column { FieldLabel("Name (Service / Account)"); WikiTextField(name, { name = it; addError = "" }, Modifier.fillMaxWidth(), "e.g. Google, WiFi, Bank") }
                Column {
                    FieldLabel("Password")
                    WikiTextField(value, { value = it; addError = "" }, Modifier.fillMaxWidth(), "Enter password...", password = !showText, onDone = { save() }, trailing = {
                        Icon(if (showText) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showText) "Hide password" else "Show password", tint = w.muted, modifier = Modifier.size(18.dp).clickable { showText = !showText })
                    })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WikiButton("Cancel", { showAdd = false }, height = 36.dp)
                    Spacer(Modifier.width(8.dp))
                    WikiButton("Save", { save() }, kind = BtnKind.PRIMARY, icon = Icons.Outlined.Check, height = 36.dp)
                }
            }
        }
    }

    viewing?.let { p ->
        WikiDialog(onDismiss = { viewing = null }, title = p.name, titleIcon = Icons.Outlined.Key, maxWidth = 340.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().background(w.surface).wikiBorder(w.border).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SAVED PASSWORD", style = sansStyle(10.sp, w.muted, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        Row(Modifier.clickable { revealed = !revealed }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = w.accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (revealed) "Hide" else "Show", style = sansStyle(11.sp, w.accent, FontWeight.SemiBold))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (revealed) p.password else "•".repeat(minOf(p.password.length, 18)), style = monoStyle(14.sp, w.text), modifier = Modifier.weight(1f))
                        WikiButton(if (copiedId == p.id) "Copied" else "Copy", {
                            clipboard.setText(AnnotatedString(p.password)); copiedId = p.id; showToast("Password copied to clipboard!")
                            scope.launch { delay(2000); copiedId = null }
                        }, icon = if (copiedId == p.id) Icons.Outlined.Check else Icons.Outlined.ContentCopy, height = 32.dp, fontSize = 11)
                    }
                }
                Text("Saved: ${DateFormat.getDateInstance().format(Date(p.createdAt))}", style = monoStyle(10.sp, w.muted))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    WikiButton("Delete", { persist(items.filter { it.id != p.id }); viewing = null; showToast("Password deleted.") }, kind = BtnKind.DANGER, icon = Icons.Outlined.Delete, height = 36.dp)
                    Spacer(Modifier.width(8.dp))
                    WikiButton("Close", { viewing = null }, height = 36.dp)
                }
            }
        }
    }
}
