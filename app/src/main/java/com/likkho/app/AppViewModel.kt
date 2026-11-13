package com.likkho.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.UnlockResult
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PendingUnlock(val log: DiaryLog, val action: UnlockAction)
enum class UnlockAction { OPEN, EXPORT, DELETE }
data class RingingAlert(val heading: String, val preview: String)

/**
 * All UI state + navigation. UI-level behaviour (what opens/closes when a button is pressed) is implemented here.
 * Backed by real cryptographic storage engines via [vaultManager] and [hooks].
 */
class AppViewModel @JvmOverloads constructor(
    application: Application,
    val vaultManager: VaultManager = VaultManager(File(application.filesDir, "vaults")),
    hooksFactory: ((Context, VaultManager) -> FeatureHooks)? = null
) : AndroidViewModel(application) {

    val hooks: FeatureHooks = hooksFactory?.invoke(application, vaultManager) ?: RealFeatureHooks(application, vaultManager)

    // ---------- core state ----------
    var vaultMode by mutableStateOf(VaultMode.PRIMARY)
    val logs = mutableStateListOf<DiaryLog>()
    val customFonts = mutableStateListOf<CustomFont>()
    var micSettings by mutableStateOf(MicSettings())
    var darkMode by mutableStateOf(false)
    var fullScreen by mutableStateOf(false)
    var savedPasscode by mutableStateOf<String?>(null)
    var secondaryPasscode by mutableStateOf<String?>(null)
    var biometricsEnabled by mutableStateOf(false)
    var savedKeyHash by mutableStateOf<String?>(null)
    var isLocked by mutableStateOf(false)
    var isStartupReady by mutableStateOf(false)

    var route by mutableStateOf(Route.HOME)
    var editingLog by mutableStateOf<DiaryLog?>(null)

    // ---------- home state ----------
    var isSearchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var openMenuLogId by mutableStateOf<String?>(null)
    var exportingLog by mutableStateOf<DiaryLog?>(null)
    var sharingLog by mutableStateOf<DiaryLog?>(null)
    var pendingDeleteLog by mutableStateOf<DiaryLog?>(null)
    var pendingUnlock by mutableStateOf<PendingUnlock?>(null)
    var exportProcessingBanner by mutableStateOf<String?>(null)
    var exportStatusBanner by mutableStateOf<String?>(null)
    var ringingAlert by mutableStateOf<RingingAlert?>(null)
    var availableUpdate by mutableStateOf<AppUpdateInfo?>(null)

    // ---------- passcode guard & wrong-PIN protection (Storage Spec Section 2) ----------
    fun remainingCooldownSeconds(): Int = vaultManager.wrongPinCounter.remainingCooldownSeconds()
    fun recordPasscodeFailure(): Int = vaultManager.wrongPinCounter.recordFailure()
    fun resetPasscodeFailures() { vaultManager.wrongPinCounter.recordSuccess() }

    val minPinLength: Int get() = vaultManager.pinLengthsRegistry.applicableLengths.minOrNull() ?: 4
    val maxPinLength: Int get() = vaultManager.pinLengthsRegistry.applicableLengths.maxOrNull() ?: 6

    fun isPinLengthApplicable(len: Int): Boolean = vaultManager.pinLengthsRegistry.isRegisteredLength(len)
    fun hasLargerApplicableLength(len: Int): Boolean = vaultManager.pinLengthsRegistry.applicableLengths.any { it > len }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Initialize primary vault envelope on first launch
                if (!vaultManager.envelopeV1.exists()) {
                    vaultManager.ensurePrimaryVaultInitialized()
                }

                val hasPrimary = vaultManager.hasPasscode(VaultId.PRIMARY)
                val hasDecoy = vaultManager.decoyExists() && vaultManager.hasPasscode(VaultId.DECOY)
                val bio = vaultManager.hasBiometric(VaultId.PRIMARY)

                val initialLogs = if (!hasPrimary) {
                    vaultManager.unlockNoPasscode()
                    if (vaultManager.isUnlocked) {
                        vaultManager.withActiveDek { dek ->
                            vaultManager.activeDiaryRepository().loadAllDiaries(dek).map { it.toDiaryLog() }
                        }
                    } else emptyList()
                } else emptyList()

                withContext(Dispatchers.Main) {
                    biometricsEnabled = bio
                    if (hasPrimary) {
                        isLocked = true
                        savedPasscode = "••••"
                        if (hasDecoy) secondaryPasscode = "••••"
                    } else {
                        savedPasscode = null
                        secondaryPasscode = null
                        isLocked = false
                        logs.clear()
                        logs.addAll(initialLogs)
                    }
                    isStartupReady = true
                }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    isStartupReady = true
                }
            }
        }

        // Check updates asynchronously without blocking cold launch splash
        viewModelScope.launch(Dispatchers.IO) {
            val r = hooks.checkForUpdate(false)
            if (r is UpdateCheck.Available) {
                withContext(Dispatchers.Main) { availableUpdate = r.info }
            }
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

    // ---------- lock & unlock ----------
    fun handleInstantLock() {
        if (savedPasscode == null) { navigateTo(Route.SETTINGS); return }
        vaultManager.lock()
        logs.clear()
        isLocked = true
    }

    suspend fun attemptUnlock(pin: String): UnlockResult = withContext(Dispatchers.IO) {
        val res = vaultManager.unlockWithPin(pin.toCharArray())
        if (res is UnlockResult.Success) {
            val mode = if (res.vaultId == VaultId.PRIMARY) VaultMode.PRIMARY else VaultMode.DECOY
            withContext(Dispatchers.Main) {
                onUnlocked(mode)
            }
        }
        res
    }

    suspend fun attemptBiometricUnlock(): UnlockResult = withContext(Dispatchers.IO) {
        val res = vaultManager.unlockWithBiometric()
        if (res is UnlockResult.Success) {
            withContext(Dispatchers.Main) {
                onUnlocked(VaultMode.PRIMARY)
            }
        }
        res
    }

    fun onUnlocked(mode: VaultMode) {
        vaultMode = mode
        route = Route.HOME
        editingLog = null
        isLocked = false
        loadLogsFromActiveVault()
    }

    fun loadLogsFromActiveVault() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!vaultManager.isUnlocked) return@launch
            val list = vaultManager.withActiveDek { dek ->
                val repo = vaultManager.activeDiaryRepository()
                repo.loadAllDiaries(dek).map { it.toDiaryLog() }
            }
            withContext(Dispatchers.Main) {
                logs.clear()
                logs.addAll(list)
            }
        }
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
        sharingLog = log
    }

    fun confirmShareLog(passphrase: String) {
        val log = sharingLog ?: return
        sharingLog = null
        if (passphrase.length < 8) {
            showStatus("Passphrase must be at least 8 characters.")
            return
        }
        exportStatusBanner = null
        exportProcessingBanner = "Encrypting \"${log.heading}\" as .likkho archive... Please wait."
        viewModelScope.launch {
            val msg = runCatching { hooks.shareDiaryAsArchive(log, passphrase) }.getOrElse { "Cannot open this file" }
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

    fun dismissRinging() { ringingAlert = null }

    // ---------- settings ----------
    /** @return false if new pin equals the other vault's pin. */
    fun updatePasscode(pin: String?): Boolean {
        if (vaultMode == VaultMode.DECOY) {
            if (pin != null && savedPasscode != null && pin == savedPasscode) return false
            if (pin == null) {
                viewModelScope.launch(Dispatchers.IO) {
                    vaultManager.removeDecoyPasscode()
                }
                secondaryPasscode = null
                vaultMode = VaultMode.PRIMARY
                loadLogsFromActiveVault()
                return true
            }
            viewModelScope.launch(Dispatchers.IO) {
                vaultManager.setDecoyPasscode(pin.toCharArray(), (savedPasscode ?: "0000").toCharArray())
            }
            secondaryPasscode = "••••"
            return true
        }

        if (pin != null && secondaryPasscode != null && pin == secondaryPasscode) return false
        if (pin == null) {
            viewModelScope.launch(Dispatchers.IO) {
                vaultManager.removePrimaryPasscode()
            }
            savedPasscode = null
            secondaryPasscode = null
            vaultMode = VaultMode.PRIMARY
            isLocked = false
            biometricsEnabled = false
            return true
        }
        viewModelScope.launch(Dispatchers.IO) {
            vaultManager.setPrimaryPasscode(pin.toCharArray())
        }
        savedPasscode = "••••"
        return true
    }

    fun updateSecondaryPasscode(pin: String?): Boolean {
        if (savedPasscode == null) return false
        if (pin != null && pin == savedPasscode) return false
        if (pin == null) {
            viewModelScope.launch(Dispatchers.IO) {
                vaultManager.removeDecoyPasscode()
            }
            secondaryPasscode = null
            if (vaultMode == VaultMode.DECOY) {
                vaultMode = VaultMode.PRIMARY
                loadLogsFromActiveVault()
            }
            return true
        }
        viewModelScope.launch(Dispatchers.IO) {
            vaultManager.setDecoyPasscode(pin.toCharArray(), savedPasscode?.toCharArray() ?: "0000".toCharArray())
        }
        secondaryPasscode = "••••"
        return true
    }

    fun checkDiaryLockPin(log: DiaryLog, pin: String): Boolean {
        val lock = log.diaryLockPin ?: return true
        if (lock == pin) return true
        if (lock.contains(":")) {
            val parts = lock.split(":", limit = 2)
            if (parts.size == 2) {
                val salt = CryptoUtils.fromHex(parts[0])
                val expectedHash = CryptoUtils.fromHex(parts[1])
                val computedHash = CryptoUtils.pbkdf2Sha256(pin.toCharArray(), salt, 100_000)
                return CryptoUtils.constantTimeEquals(expectedHash, computedHash)
            }
        }
        return false
    }

    fun clearAllAppData() {
        viewModelScope.launch(Dispatchers.IO) {
            hooks.clearAllData()
            withContext(Dispatchers.Main) {
                logs.clear(); customFonts.clear()
                micSettings = MicSettings(); savedPasscode = null; secondaryPasscode = null
                biometricsEnabled = false; savedKeyHash = null; vaultMode = VaultMode.PRIMARY; isLocked = false
                showStatus("All application data has been deleted.")
            }
            vaultManager.ensurePrimaryVaultInitialized()
        }
    }

    fun restoreSingleDiary(log: DiaryLog) {
        val i = logs.indexOfFirst { it.id == log.id }
        if (i >= 0) logs[i] = log else logs.add(0, log)
        hooks.persistLogs(logs.toList())
        showStatus("Restored \"${log.heading.ifBlank { "Diary" }}\" to app.", 3000)
    }

    fun onRestoreBundle(restored: List<DiaryLog>) {
        if (restored.isNotEmpty()) {
            logs.clear()
            logs.addAll(restored)
            hooks.persistLogs(logs.toList())
        }
    }
}
