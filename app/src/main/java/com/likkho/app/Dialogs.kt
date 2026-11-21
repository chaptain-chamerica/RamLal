package com.likkho.app

import android.app.TimePickerDialog as AndroidTimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/** Root-level dialogs of App.tsx: Locked-diary unlock, Delete entry, Export formats, App update. */

@Composable
fun UnlockDiaryDialog(vm: AppViewModel) {
    val pending = vm.pendingUnlock ?: return
    val w = wiki()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var input by remember(pending) { mutableStateOf("") }
    var error by remember(pending) { mutableStateOf<String?>(null) }
    var busy by remember(pending) { mutableStateOf(false) }
    var cooldown by remember(pending) { mutableStateOf(vm.remainingCooldownSeconds()) }
    LaunchedEffect(pending) { while (true) { cooldown = vm.remainingCooldownSeconds(); delay(500) } }

    fun submit() {
        if (busy) return
        val rem = vm.remainingCooldownSeconds()
        if (rem > 0) { cooldown = rem; error = "Too many wrong attempts. Wait ${rem}s."; return }
        busy = true
        val pinAttempt = input.trim()
        scope.launch {
            val ok = vm.verifyDiaryLockPin(pending.log, pinAttempt)
            busy = false
            if (ok) {
                vm.resetPasscodeFailures(); cooldown = 0
                val log = pending.log; val act = pending.action
                vm.pendingUnlock = null
                when (act) {
                    UnlockAction.OPEN -> vm.openUnlockedLog(log)
                    UnlockAction.EXPORT -> vm.openUnlockedExport(log)
                    UnlockAction.DELETE -> vm.pendingDeleteLog = log
                }
            } else {
                val secs = vm.recordPasscodeFailure()
                if (secs > 0) { cooldown = secs; error = "Incorrect passcode. Locked for ${secs}s." } else error = "Incorrect diary passcode."
            }
        }
    }

    WikiDialog(
        onDismiss = { if (!busy) vm.pendingUnlock = null },
        title = "Unlock \"${pending.log.heading}\"",
        titleIcon = Icons.Outlined.Lock, titleIconTint = w.danger, maxWidth = 320.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (cooldown > 0) Text("Locked for ${cooldown}s due to continuous wrong attempts.", style = sansStyle(12.sp, w.danger, FontWeight.SemiBold))
            else if (error != null) Text(error!!, style = sansStyle(12.sp, w.danger, FontWeight.Medium))
            Column {
                FieldLabel("Enter Diary Passcode")
                WikiTextField(input, { input = it; error = null }, Modifier.fillMaxWidth(), "Passcode...", password = true, enabled = cooldown == 0 && !busy, onDone = { submit() })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                WikiButton("Cancel", { vm.pendingUnlock = null }, enabled = !busy, height = 36.dp)
                Spacer(Modifier.width(8.dp))
                WikiButton(if (cooldown > 0) "Wait ${cooldown}s" else if (busy) "Verifying…" else "Unlock", { submit() }, kind = BtnKind.PRIMARY, enabled = cooldown == 0 && !busy, height = 36.dp)
            }
        }
    }
}

@Composable
fun DeleteEntryDialog(vm: AppViewModel) {
    val log = vm.pendingDeleteLog ?: return
    val w = wiki()
    WikiDialog(onDismiss = { vm.pendingDeleteLog = null }, title = "Delete Entry?", titleIcon = Icons.Outlined.Warning, titleIconTint = w.danger) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Are you sure you want to permanently delete \"${log.heading}\"? This action cannot be undone.",
                style = sansStyle(12.sp, w.text, FontWeight.Normal),
            )
            ConfirmButtons(confirmText = "Delete", confirmIcon = Icons.Outlined.Delete, onCancel = { vm.pendingDeleteLog = null }, onConfirm = { vm.confirmDelete() })
        }
    }
}

@Composable
fun ExportDialog(vm: AppViewModel) {
    val log = vm.exportingLog ?: return
    val w = wiki()
    WikiDialog(onDismiss = { vm.exportingLog = null }, title = "Export \"${log.heading}\"", titleIcon = Icons.Outlined.FileDownload) {
        Column(Modifier.padding(16.dp)) {
            Text("Choose a file format to save to Downloads/Likkho:", style = sansStyle(12.sp, w.muted, FontWeight.Normal), modifier = Modifier.padding(bottom = 12.dp))
            EXPORT_FORMATS.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { f ->
                        Column(
                            Modifier.weight(1f).background(w.surface).wikiBorder(w.border).clickable { vm.exportLog(f) }.padding(10.dp)
                        ) {
                            Text(f.label, style = monoStyle(12.sp, w.accent, FontWeight.Bold))
                            Text(f.desc, style = sansStyle(10.sp, w.muted, FontWeight.Normal), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun ShareDiaryDialog(vm: AppViewModel) {
    val log = vm.sharingLog ?: return
    val w = wiki()
    var passInput by remember { mutableStateOf("") }
    var passError by remember { mutableStateOf("") }

    WikiDialog(
        onDismiss = { vm.sharingLog = null },
        title = "Share \"${log.heading}\"",
        titleIcon = Icons.Outlined.Share,
        maxWidth = 360.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Create a secure encrypted .likkho file. Enter an encryption passphrase (minimum 8 characters). The recipient must enter this exact passphrase to open this diary.",
                style = sansStyle(11.sp, w.muted, androidx.compose.ui.text.font.FontWeight.Normal)
            )

            WikiTextField(
                value = passInput,
                onValueChange = { passInput = it; passError = "" },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Enter passphrase (min 8 chars)",
                password = true,
                height = 44.dp
            )

            if (passError.isNotBlank()) {
                Text(passError, style = sansStyle(11.sp, w.danger, androidx.compose.ui.text.font.FontWeight.Medium))
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WikiButton("Cancel", { vm.sharingLog = null }, height = 36.dp)
                WikiButton("Share (.likkho)", {
                    if (passInput.length < 8) {
                        passError = "Passphrase must be at least 8 characters"
                    } else {
                        vm.confirmShareLog(passInput)
                    }
                }, kind = BtnKind.PRIMARY, height = 36.dp)
            }
        }
    }
}

@Composable
fun UpdateDialog(info: AppUpdateInfo, hooks: FeatureHooks, onClose: () -> Unit) {
    val w = wiki()
    var downloadStarted by remember { mutableStateOf(false) }
    LaunchedEffect(downloadStarted) { if (downloadStarted) { delay(5000); downloadStarted = false } }
    WikiDialog(
        onDismiss = onClose, title = "New Update Available", titleIcon = Icons.Outlined.AutoAwesome,
        subtitle = "v${info.currentVersion} → v${info.latestVersion}",
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(info.releaseName.ifBlank { "Likkho v${info.latestVersion}" }, style = serifStyle(16.sp, w.text))
                info.publishedAt?.let { Text("Released: $it", style = monoStyle(10.sp, w.muted)) }
            }
            if (info.releaseNotes.isNotBlank()) {
                Column(Modifier.fillMaxWidth().background(w.surface).wikiBorder(w.hairline).padding(10.dp)) {
                    Text("What's New:", style = sansStyle(11.sp, w.text, FontWeight.Bold))
                    Spacer(Modifier.height(4.dp))
                    Text(info.releaseNotes, style = sansStyle(11.sp, w.muted, FontWeight.Normal))
                }
            }
            if (downloadStarted) StatusBanner("Download started in your browser/device. Tap downloaded APK to install.", BannerKind.SUCCESS, icon = Icons.Outlined.Check)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                WikiButton("Later", onClose, height = 36.dp)
                if (info.apkDownloadUrl != null) {
                    WikiButton("GitHub Page", { hooks.openUrl(info.releasePageUrl) }, icon = Icons.Outlined.OpenInNew, height = 36.dp)
                    WikiButton("Download & Install APK", { downloadStarted = true; hooks.downloadApk(info) }, kind = BtnKind.PRIMARY, icon = Icons.Outlined.Download, height = 36.dp)
                } else {
                    WikiButton("Open on GitHub & Download", { hooks.openUrl(info.releasePageUrl) }, kind = BtnKind.PRIMARY, icon = Icons.Outlined.OpenInNew, height = 36.dp)
                }
            }
        }
    }
}

/** Reminder "Set Date & Time" (ReminderModal.tsx). Errors: invalid / "Please choose a future time." */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(currentReminder: Long?, onClose: () -> Unit, onSave: (Long?) -> Unit) {
    val w = wiki()
    val ctx = LocalContext.current
    val initial = remember { Calendar.getInstance().apply { timeInMillis = currentReminder ?: (System.currentTimeMillis() + 5 * 60_000L) } }
    var year by remember { mutableStateOf(initial.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(initial.get(Calendar.MONTH)) }
    var day by remember { mutableStateOf(initial.get(Calendar.DAY_OF_MONTH)) }
    var hour by remember { mutableStateOf(initial.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableStateOf(initial.get(Calendar.MINUTE)) }
    var error by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }

    val dateLabel = "%02d/%02d/%04d".format(day, month + 1, year)
    val timeLabel = run {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        "%02d:%02d %s".format(h12, minute, if (hour < 12) "AM" else "PM")
    }

    WikiDialog(onDismiss = onClose, title = "Set Date & Time", titleIcon = Icons.Outlined.NotificationsActive, maxWidth = 340.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel("Date")
                    Box(Modifier.fillMaxWidth().height(36.dp).background(w.surface).wikiBorder(w.border).clickable { showDate = true }.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                        Text(dateLabel, style = monoStyle(12.sp, w.text))
                    }
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("Time")
                    Box(
                        Modifier.fillMaxWidth().height(36.dp).background(w.surface).wikiBorder(w.border).clickable {
                            AndroidTimePickerDialog(ctx, { _, h, m -> hour = h; minute = m; error = "" }, hour, minute, false).show()
                        }.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(timeLabel, style = monoStyle(12.sp, w.text)) }
                }
            }
            if (error.isNotEmpty()) Text(error, style = sansStyle(12.sp, w.danger, FontWeight.Medium))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (currentReminder != null) {
                    WikiButton("Clear", { onSave(null); onClose() }, kind = BtnKind.OUTLINE, icon = Icons.Outlined.Delete, height = 36.dp)
                }
                Spacer(Modifier.weight(1f))
                WikiButton("Cancel", onClose, height = 36.dp)
                Spacer(Modifier.width(8.dp))
                WikiButton("Set", {
                    val cal = Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }
                    if (cal.timeInMillis <= System.currentTimeMillis()) error = "Please choose a future time."
                    else { onSave(cal.timeInMillis); onClose() }
                }, kind = BtnKind.PRIMARY, icon = Icons.Outlined.Notifications, height = 36.dp)
            }
        }
    }

    if (showDate) {
        val utcMillis = remember {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month, day) }.timeInMillis
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = utcMillis)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = it }
                        year = c.get(Calendar.YEAR); month = c.get(Calendar.MONTH); day = c.get(Calendar.DAY_OF_MONTH); error = ""
                    }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
            shape = RectangleShape,
        ) { DatePicker(state = state) }
    }
}

/** Heads-up "ringing" notification banner (App.tsx ringingAlert). Shown at top. */
@Composable
fun RingingBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val a = vm.ringingAlert ?: return
    val w = wiki()
    Row(
        modifier.widthIn(max = 448.dp).fillMaxWidth().background(w.bg).wikiBorder(w.accent, 2.dp).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp).background(w.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.NotificationsActive, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.heading, style = serifStyle(16.sp, w.text), maxLines = 1)
            Text(a.preview, style = sansStyle(12.sp, w.muted, FontWeight.Normal), maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.height(32.dp).background(w.danger).clickable { vm.dismissRinging() }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.VolumeOff, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Dismiss", style = sansStyle(12.sp, androidx.compose.ui.graphics.Color.White, FontWeight.SemiBold))
        }
    }
}
