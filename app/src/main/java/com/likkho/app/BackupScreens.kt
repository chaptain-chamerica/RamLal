package com.likkho.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ======================================================================================
// BACKUP & RESTORE  (BackupRestorePage.tsx)
// 3 cards: Set Encryption Key (+ "✓ Key Set" chip) | Backup | Restore. All use the same Encryption Key rules.
// Wrong key => failure counter shared with the lock screen (5th = 30s cooldown, then 30s*n).
// ======================================================================================
@Composable
fun BackupRestoreScreen(vm: AppViewModel) {
    val w = wiki()
    val scope = rememberCoroutineScope()
    var newKey by remember { mutableStateOf("") }
    var confirmKey by remember { mutableStateOf("") }
    var backupPass by remember { mutableStateOf("") }
    var restorePass by remember { mutableStateOf("") }
    var showKeyText by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<Pair<BannerKind, String>?>(null) }
    var cooldown by remember { mutableStateOf(vm.remainingCooldownSeconds()) }
    LaunchedEffect(Unit) { while (true) { cooldown = vm.remainingCooldownSeconds(); delay(500) } }
    val cooling = cooldown > 0
    BackHandler { vm.navigateTo(Route.HOME) }

    fun err(t: String) { banner = BannerKind.ERROR to t }
    fun ok(t: String) { banner = BannerKind.SUCCESS to t }

    fun saveKey() {
        if (newKey.trim().length < 4) { err("Encryption Key must be at least 4 characters."); return }
        if (newKey != confirmKey) { err("Encryption Keys do not match."); return }
        scope.launch {
            vm.savedKeyHash = vm.hooks.hashPassphrase(newKey.trim())
            backupPass = newKey.trim(); newKey = ""; confirmKey = ""
            ok("Encryption Key saved.")
        }
    }

    fun backup() {
        val rem = vm.remainingCooldownSeconds()
        if (rem > 0) { cooldown = rem; err("Too many wrong attempts. Please wait ${rem}s."); return }
        if (backupPass.isBlank()) { err("Please enter your Encryption Key."); return }
        busy = true
        scope.launch {
            try {
                val hash = vm.hooks.hashPassphrase(backupPass.trim())
                val saved = vm.savedKeyHash
                if (saved != null && hash != saved) {
                    val secs = vm.recordPasscodeFailure()
                    if (secs > 0) { cooldown = secs; err("Incorrect Encryption Key. Locked for ${secs}s.") } else err("Incorrect Encryption Key.")
                    return@launch
                }
                vm.resetPasscodeFailures()
                val meta = vm.hooks.performBackup(backupPass.trim())
                if (saved == null) vm.savedKeyHash = hash
                if (meta.failedMediaCount > 0) err("Backup finished with issues: ${meta.failedMediaCount} media files backup me nahi aa payi.")
                else ok("Backup completed successfully.")
            } catch (e: Exception) { err(e.message ?: "Backup failed.") } finally { busy = false }
        }
    }

    fun restore() {
        val rem = vm.remainingCooldownSeconds()
        if (rem > 0) { cooldown = rem; err("Too many wrong attempts. Please wait ${rem}s."); return }
        if (restorePass.isBlank()) { err("Please enter your Encryption Key."); return }
        busy = true
        scope.launch {
            try {
                val res = vm.hooks.performRestore(restorePass.trim())
                vm.resetPasscodeFailures()
                vm.savedKeyHash = vm.hooks.hashPassphrase(restorePass.trim())
                vm.onRestoreBundle(res.logs)
                restorePass = ""
                if (res.skippedMediaCount > 0) err("Data restored with warnings: ${res.skippedMediaCount} incomplete media items were skipped.")
                else ok("Data restored successfully.")
            } catch (e: IncorrectKeyException) {
                val secs = vm.recordPasscodeFailure()
                if (secs > 0) { cooldown = secs; err("Incorrect Encryption Key. Locked for ${secs}s.") } else err("Incorrect Encryption Key.")
            } catch (e: Exception) { err(e.message ?: "Restore failed.") } finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(w.bg)) {
        ScreenHeader("Backup & Restore", { vm.navigateTo(Route.HOME) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (cooling) StatusBanner("Cooldown active due to continuous wrong attempts: ${cooldown}s remaining", BannerKind.ERROR, icon = Icons.Outlined.Warning)
            banner?.let { StatusBanner(it.second, it.first) }

            // 1. key
            SectionCard(Icons.Outlined.VpnKey, "Set Encryption Key", chip = if (vm.savedKeyHash != null) "✓ Key Set" else null) {
                KeyField("Encryption Key", newKey, { newKey = it }, "Enter encryption key", showKeyText, { showKeyText = !showKeyText }, withEye = true)
                KeyField("Confirm Encryption Key", confirmKey, { confirmKey = it }, "Confirm encryption key", showKeyText, {})
                WikiButton("Save Key", { saveKey() }, Modifier.fillMaxWidth(), BtnKind.PRIMARY, Icons.Outlined.Shield)
            }
            // 2. backup
            SectionCard(Icons.Outlined.Backup, "Backup") {
                KeyField("Encryption Key", backupPass, { backupPass = it }, "Enter encryption key", showKeyText, {}, enabled = !cooling)
                WikiButton(if (cooling) "Wait ${cooldown}s" else if (busy) "Backing up..." else "Backup", { backup() }, Modifier.fillMaxWidth(), BtnKind.PRIMARY, Icons.Outlined.Lock, enabled = !busy && !cooling)
            }
            // 3. restore
            SectionCard(Icons.Outlined.Restore, "Restore") {
                KeyField("Encryption Key", restorePass, { restorePass = it }, "Enter encryption key", showKeyText, {}, enabled = !cooling)
                WikiButton(if (cooling) "Wait ${cooldown}s" else if (busy) "Restoring..." else "Restore", { restore() }, Modifier.fillMaxWidth(), BtnKind.PRIMARY, Icons.Outlined.Restore, enabled = !busy && !cooling)
            }
        }
    }
}

@Composable
private fun SectionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, chip: String? = null, content: @Composable () -> Unit) {
    val w = wiki()
    Column(Modifier.fillMaxWidth().background(w.surface).wikiBorder(w.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = w.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = serifStyle(17.sp, w.text), modifier = Modifier.weight(1f))
            if (chip != null) Text(chip, style = sansStyle(10.sp, w.success, FontWeight.Bold), modifier = Modifier.background(w.success.copy(alpha = 0.10f)).wikiBorder(w.success).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        content()
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, show: Boolean, toggle: () -> Unit, withEye: Boolean = false, enabled: Boolean = true) {
    val w = wiki()
    Column {
        FieldLabel(label)
        WikiTextField(value, onChange, Modifier.fillMaxWidth(), placeholder, password = !show, enabled = enabled, height = 40.dp, trailing = if (withEye) ({
            Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Toggle visibility", tint = w.muted, modifier = Modifier.size(18.dp).clickable { toggle() })
        }) else null)
    }
}

// ======================================================================================
// BACKED-UP DIARIES (BackedUpDiariesModal.tsx)
// Step 1: "Unlock Backup Vault" (encryption key, optional biometric key). Step 2: list with search, Read, Delete.
// Reading: if diary has its own PIN => asks it first. Footer: "Restore this Diary to App" + Close.
// ======================================================================================
@Composable
fun BackedUpDiariesScreen(vm: AppViewModel, onClose: () -> Unit) {
    val w = wiki()
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableStateOf(vm.remainingCooldownSeconds()) }
    var bundle by remember { mutableStateOf<List<DiaryLog>>(emptyList()) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf<DiaryLog?>(null) }
    var readingUnlocked by remember { mutableStateOf(false) }
    var diaryPin by remember { mutableStateOf("") }
    var diaryPinError by remember { mutableStateOf<String?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DiaryLog?>(null) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { cooldown = vm.remainingCooldownSeconds(); delay(500) } }
    BackHandler { if (searchOpen) { searchOpen = false; query = "" } else onClose() }

    fun flash(msg: String) { success = msg; scope.launch { delay(3000); if (success == msg) success = null } }

    fun unlock() {
        val rem = vm.remainingCooldownSeconds()
        if (rem > 0) { cooldown = rem; error = "Too many wrong attempts. Wait ${rem}s."; return }
        if (pass.isBlank()) return
        busy = true; error = null
        scope.launch {
            try {
                bundle = vm.hooks.openBackupVault(pass.trim())
                vm.resetPasscodeFailures(); unlocked = true
            } catch (e: IncorrectKeyException) {
                val secs = vm.recordPasscodeFailure()
                if (secs > 0) { cooldown = secs; error = "Incorrect Encryption Key. Locked for ${secs}s." } else error = "Incorrect Encryption Key."
            } catch (e: Exception) { error = e.message ?: "Could not read backup vault." } finally { busy = false }
        }
    }

    if (!unlocked) {
        Column(Modifier.fillMaxSize().background(w.bg)) {
            ScreenHeader("Backed-up Diaries Vault", onClose)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Column(Modifier.fillMaxWidth().background(w.surface).wikiBorder(w.border).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(w.bg).wikiBorder(w.border), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.VpnKey, null, tint = w.accent) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Unlock Backup Vault", style = serifStyle(18.sp, w.text))
                            Text("Enter your encryption key to view and manage backed-up diaries.", style = sansStyle(12.sp, w.muted, FontWeight.Normal))
                        }
                    }
                    error?.let { StatusBanner(it, BannerKind.ERROR, icon = Icons.Outlined.Warning) }
                    success?.let { StatusBanner(it, BannerKind.SUCCESS) }
                    Column {
                        FieldLabel("Encryption Key")
                        WikiTextField(pass, { pass = it; error = null }, Modifier.fillMaxWidth(), "Enter your key...", password = !showPass, enabled = !busy && cooldown == 0, height = 40.dp, onDone = { unlock() }, trailing = {
                            Icon(if (showPass) Icons.Outlined.Visibility else Icons.Outlined.Lock, null, tint = w.muted, modifier = Modifier.size(18.dp).clickable { showPass = !showPass })
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WikiButton(
                            if (busy) "Reading Backup Vault..." else if (cooldown > 0) "Locked (${cooldown}s)" else "Unlock & Read Diaries", { unlock() },
                            Modifier.weight(1f), BtnKind.PRIMARY, Icons.Outlined.LockOpen, enabled = !busy && cooldown == 0 && pass.isNotBlank(), height = 44.dp,
                        )
                        if (vm.biometricsEnabled) {
                            IconBtn(Icons.Outlined.Fingerprint, "Unlock with Biometrics", {
                                // TODO(StudioAI): biometric => reuse stored key to open vault
                                vm.hooks.authenticateBiometric(true) { _, m -> if (!m.isNullOrBlank()) error = m }
                            }, size = 44.dp, tint = w.accent, enabled = !busy)
                        }
                    }
                }
            }
        }
        return
    }

    val filtered = bundle.filter {
        val q = query.trim().lowercase()
        q.isEmpty() || it.heading.lowercase().contains(q) || it.plainPreview.lowercase().contains(q) || it.contentHtml.lowercase().contains(q)
    }

    Column(Modifier.fillMaxSize().background(w.bg)) {
        Column(Modifier.fillMaxWidth().background(w.surface)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searchOpen) {
                    IconBtn(Icons.Outlined.ArrowBack, "Close Search", { searchOpen = false; query = "" }, size = 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Search, null, tint = w.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    WikiTextField(query, { query = it }, Modifier.weight(1f), "Search backed-up diaries...", onDone = {})
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Icons.Outlined.Close, "Close", { searchOpen = false; query = "" }, size = 36.dp, tint = w.muted, iconSize = 16.dp)
                } else {
                    IconBtn(Icons.Outlined.ArrowBack, "Back to Settings", onClose)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Backed-up Diaries", style = serifStyle(20.sp, w.text))
                        Text("Vault Folder: ${if (vm.vaultMode == VaultMode.DECOY) ".ramlal_decoy_backups" else ".ramlal_backups"} (${bundle.size} entries)", style = monoStyle(10.sp, w.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconBtn(Icons.Outlined.Search, "Search Backed-up Diaries", { searchOpen = true })
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.border))
        }
        success?.let { Box(Modifier.padding(12.dp)) { StatusBanner(it, BannerKind.SUCCESS) } }
        error?.let { Box(Modifier.padding(12.dp)) { StatusBanner(it, BannerKind.ERROR, icon = Icons.Outlined.Warning) } }

        if (filtered.isEmpty()) {
            EmptyState(
                Icons.Outlined.MenuBook,
                if (query.isNotBlank()) "No Matching Backed-up Diaries" else "No Backed-up Diaries Found",
                if (query.isNotBlank()) "No backed-up entry matches \"$query\"." else "There are currently no diaries in your backup vault file. When you back up your diaries from Backup & Restore, they will appear here.",
                action = if (query.isBlank()) ({ WikiButton("Go to Backup & Restore", { onClose(); vm.navigateTo(Route.BACKUP) }, kind = BtnKind.PRIMARY) }) else null,
            )
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filtered.forEach { log ->
                    Column(Modifier.fillMaxWidth().background(w.bg).wikiBorder(w.border).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (log.pinned) Tag(Icons.Outlined.PushPin, "Pinned", w.accent)
                                    if (log.diaryLockPin != null) Tag(Icons.Outlined.Lock, "Locked", w.danger)
                                }
                                Text(log.heading.ifBlank { "Untitled Diary" }, style = serifStyle(17.sp, w.text.copy(alpha = 0.90f), weight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.CalendarToday, null, tint = w.muted, modifier = Modifier.size(11.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${log.dateStamp} · ${log.timeStamp}", style = monoStyle(11.sp, w.muted.copy(alpha = 0.78f)))
                                }
                            }
                            WikiButton("Read", { reading = log; readingUnlocked = false; diaryPin = ""; diaryPinError = null }, icon = Icons.Outlined.Visibility, height = 34.dp, fontSize = 11)
                            Spacer(Modifier.width(6.dp))
                            IconBtn(Icons.Outlined.Delete, "Delete from backup vault", { pendingDelete = log }, size = 34.dp, tint = w.danger, iconSize = 16.dp)
                        }
                        if (log.diaryLockPin == null && log.plainPreview.isNotBlank()) Text(log.plainPreview, style = proseStyle(14.sp, w.text.copy(alpha = 0.65f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val ic = log.canvasImages.size; val ac = log.audioAttachments.size
                        if (ic > 0 || ac > 0) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (ic > 0) Tag(Icons.Outlined.Image, "$ic ${if (ic == 1) "image" else "images"}", w.muted)
                            if (ac > 0) Tag(Icons.Outlined.VolumeUp, "$ac ${if (ac == 1) "audio" else "audios"}", w.muted)
                        }
                    }
                }
            }
        }
    }

    // ---- Read diary full modal ----
    reading?.let { log ->
        WikiDialog(onDismiss = { reading = null }, title = log.heading.ifBlank { "Untitled Diary" }, titleIcon = Icons.Outlined.MenuBook, maxWidth = 448.dp) {
            if (log.diaryLockPin != null && !readingUnlocked) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = w.danger, modifier = Modifier.size(28.dp))
                    Text("This Backed-up Diary is Password Protected", style = serifStyle(16.sp, w.text))
                    Text("Enter this diary's 4-digit passcode to unlock and read its content.", style = sansStyle(12.sp, w.muted, FontWeight.Normal))
                    diaryPinError?.let { Text(it, style = sansStyle(12.sp, w.danger, FontWeight.Medium)) }
                    fun check() { if (vm.checkDiaryLockPin(log, diaryPin)) { readingUnlocked = true; diaryPinError = null } else diaryPinError = "Incorrect passcode for this diary." }
                    WikiTextField(diaryPin, { diaryPin = it.take(10) }, Modifier.fillMaxWidth(), "Diary passcode...", password = true, onDone = { check() })
                    WikiButton("Unlock", { check() }, Modifier.fillMaxWidth(), BtnKind.PRIMARY)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(log.heading.ifBlank { "Untitled Diary" }, style = serifStyle(22.sp, w.text))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Schedule, null, tint = w.muted, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp))
                        Text("${log.dateStamp} · ${log.timeStamp}" + if (log.pinned) "   • Pinned" else "", style = monoStyle(10.sp, w.muted.copy(alpha = 0.78f)))
                    }
                    // TODO(StudioAI): render real rich content (contentHtml) here
                    Text(log.contentHtml.ifBlank { "Empty content." }, style = proseStyle(16.sp, if (log.contentHtml.isBlank()) w.muted else w.text), lineHeight = 27.sp)
                    if (log.canvasImages.isNotEmpty()) {
                        Text("Attached Photos (${log.canvasImages.size})", style = sansStyle(12.sp, w.text, FontWeight.Bold))
                        log.canvasImages.forEach { // TODO(StudioAI): real bitmap
                            Box(Modifier.fillMaxWidth().height(140.dp).background(w.surface).wikiBorder(w.border), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Image, null, tint = w.muted, modifier = Modifier.size(32.dp)) }
                        }
                    }
                    if (log.audioAttachments.isNotEmpty()) {
                        Text("Attached Voice Notes (${log.audioAttachments.size})", style = sansStyle(12.sp, w.text, FontWeight.Bold))
                        log.audioAttachments.forEach { a ->
                            val playing = playingId == a.id
                            Row(Modifier.fillMaxWidth().background(w.surface).wikiBorder(w.border).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.VolumeUp, null, tint = w.accent, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                                Text("${a.name.ifBlank { "Voice Note" }} (${a.durationSec}s)", style = sansStyle(12.sp, w.text, FontWeight.Medium), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                WikiButton(if (playing) "Pause" else "Play", { playingId = if (playing) null else a.id /* TODO(StudioAI): real playback */ }, icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, height = 32.dp, fontSize = 11)
                            }
                        }
                    }
                }
            }
            HairlineDivider()
            Row(Modifier.fillMaxWidth().background(w.surface).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                WikiButton("Restore this Diary to App", { vm.restoreSingleDiary(log); flash("Restored \"${log.heading.ifBlank { "Diary" }}\" to in-app diaries.") }, icon = Icons.Outlined.Restore, kind = BtnKind.PRIMARY, height = 36.dp)
                WikiButton("Close", { reading = null }, height = 36.dp)
            }
        }
    }

    // ---- Delete from vault ----
    pendingDelete?.let { log ->
        WikiDialog(onDismiss = { if (!deleting) pendingDelete = null }, title = "Delete from Backup Vault?", titleIcon = Icons.Outlined.Warning, titleIconTint = w.danger, showClose = !deleting) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Are you sure you want to permanently delete \"${log.heading.ifBlank { "Untitled Diary" }}\" from your backup vault file?", style = sansStyle(12.sp, w.text, FontWeight.Normal))
                Text("This will remove it from the backup file in your chosen folder on disk. This action cannot be undone.", style = sansStyle(11.sp, w.muted, FontWeight.Normal))
                ConfirmButtons(confirmText = if (deleting) "Deleting..." else "Delete from Vault", confirmIcon = Icons.Outlined.Delete, busy = deleting, onCancel = { pendingDelete = null }, onConfirm = {
                    deleting = true
                    scope.launch {
                        runCatching { vm.hooks.deleteFromBackup(log) }
                        bundle = bundle.filter { it.id != log.id }; deleting = false; pendingDelete = null; flash("Deleted from backup vault.")
                    }
                })
            }
        }
    }
}

@Composable
private fun Tag(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(11.dp)); Spacer(Modifier.width(3.dp))
        Text(label, style = sansStyle(10.sp, color, FontWeight.SemiBold))
    }
}
