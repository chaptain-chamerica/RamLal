package com.likkho.diary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PendingUnlock(val log: DiaryLog, val action: UnlockAction)
enum class UnlockAction { OPEN, EXPORT, DELETE }
data class RingingAlert(val heading: String, val preview: String)

/**
 * All UI state + navigation. UI-level behaviour (what opens/closes when a button is pressed) is implemented here
 * for real. Anything needing real storage/encryption/network goes through [hooks].
 */
class AppViewModel : ViewModel() {

    // TODO(StudioAI): replace with RealFeatureHooks
    val hooks: FeatureHooks = StubFeatureHooks()

    // ---------- core state ----------
    var vaultMode by mutableStateOf(VaultMode.PRIMARY)
    val logs = mutableStateListOf<DiaryLog>().apply { addAll(SampleData.starterLogs()) }
    val customFonts = mutableStateListOf<CustomFont>()
    var micSettings by mutableStateOf(MicSettings())
    var darkMode by mutableStateOf(false)
    var fullScreen by mutableStateOf(false)
    var savedPasscode by mutableStateOf<String?>(null)
    var secondaryPasscode by mutableStateOf<String?>(null)
    var biometricsEnabled by mutableStateOf(false)
    var savedKeyHash by mutableStateOf<String?>(null)
    var isLocked by mutableStateOf(false)

    var route by mutableStateOf(Route.HOME)
    var editingLog by mutableStateOf<DiaryLog?>(null)

    // ---------- home state ----------
    var isSearchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var openMenuLogId by mutableStateOf<String?>(null)
    var exportingLog by mutableStateOf<DiaryLog?>(null)
    var pendingDeleteLog by mutableStateOf<DiaryLog?>(null)
    var pendingUnlock by mutableStateOf<PendingUnlock?>(null)
    var exportProcessingBanner by mutableStateOf<String?>(null)
    var exportStatusBanner by mutableStateOf<String?>(null)
    var ringingAlert by mutableStateOf<RingingAlert?>(null)
    var availableUpdate by mutableStateOf<AppUpdateInfo?>(null)

    // ---------- passcode guard (same rule as cryptoVault.ts): 5th wrong = 30s, 6th+ = 30s * attempts ----------
    private var failedAttempts = 0
    private var cooldownUntilMs = 0L
    fun remainingCooldownSeconds(): Int {
        val d = cooldownUntilMs - System.currentTimeMillis()
        return if (d > 0) ((d + 999) / 1000).toInt() else 0
    }
    /** @return cooldown seconds triggered by this failure (0 = none) */
    fun recordPasscodeFailure(): Int {
        failedAttempts += 1
        val secs = when {
            failedAttempts == 5 -> 30
            failedAttempts > 5 -> 30 * failedAttempts
            else -> 0
        }
        cooldownUntilMs = if (secs > 0) System.currentTimeMillis() + secs * 1000L else 0L
        return secs
    }
    fun resetPasscodeFailures() { failedAttempts = 0; cooldownUntilMs = 0L }

    init {
        viewModelScope.launch {
            val r = hooks.checkForUpdate(false)
            if (r is UpdateCheck.Available) availableUpdate = r.info
        }
    }

    // ---------- navigation ----------
    fun navigateTo(r: Route, log: DiaryLog? = null) {
        openMenuLogId = null
        editingLog = if (r == Route.WORKSPACE) log else editingLog
        route = r
    }

    // ---------- derived ----------
    val sortedLogs: List<DiaryLog>
        get() {
            val q = searchQuery.trim().lowercase()
            val list = if (q.isEmpty()) logs.toList() else logs.filter {
                it.heading.lowercase().contains(q) || it.plainPreview.lowercase().contains(q) ||
                    it.contentHtml.lowercase().contains(q) || it.dateStamp.lowercase().contains(q)
            }
            return list.sortedWith(compareByDescending<DiaryLog> { it.pinned }.thenByDescending { it.createdAt })
        }

    val effectivePrimaryPin: String get() = savedPasscode ?: secondaryPasscode ?: ""

    // ---------- lock ----------
    fun handleInstantLock() {
        if (savedPasscode == null) { navigateTo(Route.SETTINGS); return }
        isLocked = true
    }

    fun onUnlocked(mode: VaultMode) {
        if (mode != vaultMode) {
            vaultMode = mode
            route = Route.HOME
            editingLog = null
            // TODO(StudioAI): load the other vault's data here (real_vault.db / decoy_vault.db). Demo = empty decoy.
            logs.clear()
            if (mode == VaultMode.PRIMARY) logs.addAll(SampleData.starterLogs())
        }
        isLocked = false
    }

    // ---------- logs ----------
    fun upsertLog(updated: DiaryLog, exitAfterSave: Boolean) {
        val i = logs.indexOfFirst { it.id == updated.id }
        if (i >= 0) logs[i] = updated else logs.add(0, updated)
        editingLog = updated
        hooks.persistLogs(logs.toList())
        if (exitAfterSave) navigateTo(Route.HOME)
    }

    fun togglePin(id: String) {
        val i = logs.indexOfFirst { it.id == id }
        if (i >= 0) logs[i] = logs[i].copy(pinned = !logs[i].pinned)
        openMenuLogId = null
        hooks.persistLogs(logs.toList())
    }

    fun requestDelete(id: String) {
        openMenuLogId = null
        val target = logs.find { it.id == id } ?: editingLog ?: return
        if (target.diaryLockPin != null) { pendingUnlock = PendingUnlock(target, UnlockAction.DELETE); return }
        pendingDeleteLog = target
    }

    fun confirmDelete() {
        val t = pendingDeleteLog ?: return
        hooks.scheduleReminder(t, null)
        logs.removeAll { it.id == t.id }
        pendingDeleteLog = null
        hooks.persistLogs(logs.toList())
        if (route != Route.HOME) navigateTo(Route.HOME)
    }

    fun openLog(log: DiaryLog) {
        if (log.diaryLockPin != null) pendingUnlock = PendingUnlock(log, UnlockAction.OPEN)
        else navigateTo(Route.WORKSPACE, log)
    }

    fun requestExport(log: DiaryLog) {
        openMenuLogId = null
        if (exportProcessingBanner != null) return
        if (log.diaryLockPin != null) pendingUnlock = PendingUnlock(log, UnlockAction.EXPORT) else exportingLog = log
    }

    fun shareLog(log: DiaryLog) {
        if (exportProcessingBanner != null) return
        openMenuLogId = null
        exportStatusBanner = null
        exportProcessingBanner = "Packing \"${log.heading}\" as .ramlal archive... Please wait."
        viewModelScope.launch {
            val msg = runCatching { hooks.shareDiaryAsArchive(log) }.getOrElse { "Share failed." }
            exportProcessingBanner = null
            showStatus(msg)
        }
    }

    fun exportLog(format: ExportFormat) {
        val log = exportingLog ?: return
        exportingLog = null
        exportStatusBanner = null
        exportProcessingBanner = "Exporting \"${log.heading}\" as ${format.label}... Please wait."
        viewModelScope.launch {
            val msg = runCatching { hooks.exportDiary(log, format) }.getOrElse { "Export failed." }
            exportProcessingBanner = null
            showStatus(msg)
        }
    }

    fun showStatus(msg: String, millis: Long = 3200) {
        exportStatusBanner = msg
        viewModelScope.launch { delay(millis); if (exportStatusBanner == msg) exportStatusBanner = null }
    }

    fun dismissRinging() { ringingAlert = null /* TODO(StudioAI): stop ringtone */ }

    // ---------- settings ----------
    /** @return false if new pin equals the other vault's pin. */
    fun updatePasscode(pin: String?): Boolean {
        if (vaultMode == VaultMode.DECOY) {
            if (pin != null && savedPasscode != null && pin == savedPasscode) return false
            secondaryPasscode = pin
            if (pin == null) vaultMode = VaultMode.PRIMARY
            return true
        }
        if (pin != null && secondaryPasscode != null && pin == secondaryPasscode) return false
        savedPasscode = pin
        if (pin == null) {
            secondaryPasscode = null
            vaultMode = VaultMode.PRIMARY
            isLocked = false
            biometricsEnabled = false
        }
        return true
    }

    fun updateSecondaryPasscode(pin: String?): Boolean {
        if (savedPasscode == null) return false
        if (pin != null && pin == savedPasscode) return false
        secondaryPasscode = pin
        if (pin == null && vaultMode == VaultMode.DECOY) vaultMode = VaultMode.PRIMARY
        return true
    }

    fun clearAllAppData() {
        viewModelScope.launch {
            hooks.clearAllData()
            logs.clear(); customFonts.clear()
            micSettings = MicSettings(); savedPasscode = null; secondaryPasscode = null
            biometricsEnabled = false; savedKeyHash = null; vaultMode = VaultMode.PRIMARY; isLocked = false
            showStatus("All application data has been deleted.")
        }
    }

    fun restoreSingleDiary(log: DiaryLog) {
        val i = logs.indexOfFirst { it.id == log.id }
        if (i >= 0) logs[i] = log else logs.add(0, log)
        showStatus("Restored \"${log.heading.ifBlank { "Diary" }}\" to app.", 3000)
    }

    fun onRestoreBundle(restored: List<DiaryLog>) { if (restored.isNotEmpty()) { logs.clear(); logs.addAll(restored) } }
}
