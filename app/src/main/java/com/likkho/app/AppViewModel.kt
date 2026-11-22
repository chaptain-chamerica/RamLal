package com.likkho.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.likkho.app.editor.FontRegistry
import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.DiaryRepository
import com.likkho.app.storage.FailedDiaryEntry
import com.likkho.app.storage.UnlockResult
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

data class PendingUnlock(val log: DiaryLog, val action: UnlockAction)
enum class UnlockAction { OPEN, EXPORT, DELETE }
data class RingingAlert(val heading: String, val preview: String)
data class StatusBannerState(val message: String, val kind: BannerKind = BannerKind.SUCCESS)

internal fun storageFailureBanner(prefix: String, err: Throwable): StatusBannerState =
    StatusBannerState("$prefix: ${err.message ?: "Storage error"}", BannerKind.ERROR)

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
    var exportStatusBanner by mutableStateOf<StatusBannerState?>(null)
    var ringingAlert by mutableStateOf<RingingAlert?>(null)
    var availableUpdate by mutableStateOf<AppUpdateInfo?>(null)
    var failedDiaries by mutableStateOf<List<FailedDiaryEntry>>(emptyList())
    var isVaultChecking by mutableStateOf(false)
    var lockBiometricMessage by mutableStateOf<String?>(null)
    private var postUnlockMaintenanceJob: Job? = null

    // ---------- passcode guard & wrong-PIN protection (Storage Spec Section 2) ----------
    fun remainingCooldownSeconds(): Int = vaultManager.wrongPinCounter.remainingCooldownSeconds()
    fun recordPasscodeFailure(): Int = vaultManager.wrongPinCounter.recordFailure()
    fun resetPasscodeFailures() { vaultManager.wrongPinCounter.recordSuccess() }

    val minPinLength: Int get() = vaultManager.pinLengthsRegistry.applicableLengths.minOrNull() ?: 4
    val maxPinLength: Int get() = vaultManager.pinLengthsRegistry.applicableLengths.maxOrNull() ?: 6

    fun isPinLengthApplicable(len: Int): Boolean = vaultManager.pinLengthsRegistry.isRegisteredLength(len)
    fun hasLargerApplicableLength(len: Int): Boolean = vaultManager.pinLengthsRegistry.applicableLengths.any { it > len }

    init {
        val tCold0 = System.nanoTime()
        val initialFlags = runCatching { vaultManager.envelopeV1.readHeaderFlags() }.getOrNull()
        val hasPrimaryFast = initialFlags?.hasPasscode == true
        if (hasPrimaryFast) {
            isLocked = true
            savedPasscode = "••••"
            isVaultChecking = true
            isStartupReady = true
            CryptoUtils.logPerf("cold_start_lock_immediate ms=${"%.2f".format((System.nanoTime() - tCold0) / 1_000_000.0)}")
        } else {
            isLocked = false
            isVaultChecking = true
            isStartupReady = true
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!hasPrimaryFast) {
                    if (!vaultManager.envelopeV1.exists() || !vaultManager.hasPasscode(VaultId.PRIMARY)) {
                        if (!vaultManager.isUnlocked) {
                            vaultManager.ensurePrimaryVaultInitialized()
                        }
                    }
                }

                val hasPrimary = vaultManager.hasPasscode(VaultId.PRIMARY)
                val hasDecoy = vaultManager.decoyExists() && vaultManager.hasPasscode(VaultId.DECOY)
                val bio = vaultManager.hasBiometric(VaultId.PRIMARY)
                // Warm registered lengths off the UI thread
                vaultManager.pinLengthsRegistry.applicableLengths

                val homeResult = if (!hasPrimary && vaultManager.isUnlocked) {
                    loadHomeEntriesSnapshot()?.also { preloadHomePfps(it.items) }
                } else null

                val savedFonts = FontRegistry.loadSavedFonts(fontStorageDir())

                withContext(Dispatchers.Main) {
                    customFonts.clear()
                    customFonts.addAll(savedFonts)
                    biometricsEnabled = bio
                    if (hasPrimary) {
                        isLocked = true
                        savedPasscode = "••••"
                        if (hasDecoy) secondaryPasscode = "••••"
                    } else {
                        savedPasscode = null
                        secondaryPasscode = null
                        isLocked = false
                        if (homeResult != null) {
                            logs.clear()
                            logs.addAll(homeResult.items)
                            failedDiaries = homeResult.failed
                            if (homeResult.failed.isNotEmpty()) {
                                showStatus(
                                    "${homeResult.failed.size} diary ${if (homeResult.failed.size == 1) "file" else "files"} failed to open (${homeResult.failed.joinToString { it.diaryId }}).",
                                    BannerKind.ERROR,
                                    8000
                                )
                            }
                        }
                        schedulePostUnlockMaintenance(VaultId.PRIMARY)
                        CryptoUtils.logPerf("cold_start_no_passcode_ready ms=${"%.2f".format((System.nanoTime() - tCold0) / 1_000_000.0)}")
                    }
                    isVaultChecking = false
                    isStartupReady = true
                }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    isVaultChecking = false
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
        val leavingWorkspace = route == Route.WORKSPACE && r != Route.WORKSPACE
        openMenuLogId = null
        editingLog = if (r == Route.WORKSPACE) log else null
        route = r
        if (leavingWorkspace) {
            pruneInactiveBitmaps()
        }
    }

    // ---------- derived ----------
    val sortedLogs: List<DiaryLog>
        get() {
            val q = searchQuery.trim().lowercase()
            val list = if (q.isEmpty()) logs.toList() else logs.filter {
                it.heading.lowercase().contains(q) || it.plainPreview.lowercase().contains(q) ||
                    it.dateStamp.lowercase().contains(q) ||
                    (it.isContentLoaded && it.contentHtml.lowercase().contains(q))
            }
            return list.sortedWith(compareByDescending<DiaryLog> { it.pinned }.thenByDescending { it.createdAt })
        }

    val effectivePrimaryPin: String get() = savedPasscode ?: secondaryPasscode ?: ""

    // ---------- lock & unlock ----------
    fun handleInstantLock() {
        if (effectivePrimaryPin.isEmpty()) { navigateTo(Route.SETTINGS); return }
        postUnlockMaintenanceJob?.cancel()
        postUnlockMaintenanceJob = null
        hooks.cancelBiometricPrompt()
        vaultManager.lock()
        logs.clear()
        screenBitmapTracker.clear()
        imageBitmapCache.evictAll()
        isLocked = true
    }

    suspend fun attemptUnlock(pin: String): UnlockResult = withContext(Dispatchers.IO) {
        val chars = pin.toCharArray()
        try {
            val res = vaultManager.unlockWithPin(chars, zeroizeInput = true)
            if (res is UnlockResult.Success) {
                val mode = if (res.vaultId == VaultId.PRIMARY) VaultMode.PRIMARY else VaultMode.DECOY
                val homeResult = loadHomeEntriesSnapshot()
                if (homeResult != null) {
                    preloadHomePfps(homeResult.items)
                }
                withContext(Dispatchers.Main) {
                    onUnlocked(mode, homeResult)
                }
            }
            res
        } finally {
            CryptoUtils.zeroize(chars)
        }
    }

    fun startBiometricUnlock(onComplete: (UnlockResult) -> Unit) {
        hooks.unlockWithBiometricPrompt { res ->
            when (res) {
                is UnlockResult.Success -> {
                    viewModelScope.launch(Dispatchers.IO) {
                        val homeResult = loadHomeEntriesSnapshot()
                        if (homeResult != null) {
                            preloadHomePfps(homeResult.items)
                        }
                        withContext(Dispatchers.Main) {
                            onUnlocked(VaultMode.PRIMARY, homeResult)
                            onComplete(res)
                        }
                    }
                }
                is UnlockResult.BiometricInvalidated -> {
                    biometricsEnabled = vaultManager.hasBiometric(VaultId.PRIMARY)
                    lockBiometricMessage = res.message
                    onComplete(res)
                }
                else -> {
                    onComplete(res)
                }
            }
        }
    }

    fun refreshBiometricState() {
        biometricsEnabled = vaultManager.hasBiometric(VaultId.PRIMARY)
    }

    private fun schedulePostUnlockMaintenance(vaultId: VaultId) {
        postUnlockMaintenanceJob?.cancel()
        postUnlockMaintenanceJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1500)
            if (!vaultManager.isUnlocked || vaultManager.currentVault != vaultId) return@launch
            runCatching { hooks.runStartupStorageCleanup() }
            if (!vaultManager.isUnlocked || vaultManager.currentVault != vaultId) return@launch
            runCatching { hooks.runPostUnlockMaintenance(vaultId) }
        }
    }

    fun onUnlocked(mode: VaultMode, preloadedHome: DiaryRepository.HomeIndexResult<DiaryLog>? = null) {
        vaultMode = mode
        route = Route.HOME
        editingLog = null
        if (preloadedHome != null) {
            logs.clear()
            logs.addAll(preloadedHome.items)
            failedDiaries = preloadedHome.failed
            if (preloadedHome.failed.isNotEmpty()) {
                showStatus(
                    "${preloadedHome.failed.size} diary ${if (preloadedHome.failed.size == 1) "file" else "files"} failed to open (${preloadedHome.failed.joinToString { it.diaryId }}).",
                    BannerKind.ERROR,
                    8000
                )
            }
        }
        isLocked = false
        lockBiometricMessage = null
        if (preloadedHome == null) {
            loadLogsFromActiveVault()
        }
        val vaultId = if (mode == VaultMode.PRIMARY) VaultId.PRIMARY else VaultId.DECOY
        schedulePostUnlockMaintenance(vaultId)
    }

    private fun loadHomeEntriesSnapshot(): DiaryRepository.HomeIndexResult<DiaryLog>? {
        if (!vaultManager.isUnlocked) return null
        return runCatching {
            vaultManager.withActiveDek { dek ->
                vaultManager.activeDiaryRepository().loadHomeEntries(
                    dek = dek,
                    fromIndexEntry = { it.toSummaryDiaryLog() },
                    fromFullRecord = { it.toSummaryDiaryLog() }
                )
            }
        }.getOrNull()
    }

    internal fun collectDistinctPfpMediaIds(items: List<DiaryLog>): List<String> {
        val seen = LinkedHashSet<String>()
        for (item in items) {
            val src = item.pfp?.source
            if (!src.isNullOrBlank()) {
                seen.add(src)
            }
        }
        return seen.toList()
    }

    private suspend fun preloadHomePfps(items: List<DiaryLog>, maxImmediate: Int = 24) {
        if (!vaultManager.isUnlocked) return
        val ids = collectDistinctPfpMediaIds(items).filter { imageBitmapCache.get(it) == null }
        if (ids.isEmpty()) return
        val immediate = ids.take(maxImmediate)
        val remaining = ids.drop(maxImmediate)
        coroutineScope {
            immediate.map { mediaId ->
                async(Dispatchers.IO) {
                    decodeSampledBitmapFromEncryptedMedia(mediaId, maxDim = PFP_DECODE_MAX_DIM)?.let { bmp ->
                        imageBitmapCache.put(mediaId, bmp)
                    }
                }
            }.awaitAll()
        }
        if (remaining.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                for (mediaId in remaining) {
                    if (!vaultManager.isUnlocked) break
                    if (imageBitmapCache.get(mediaId) != null) continue
                    decodeSampledBitmapFromEncryptedMedia(mediaId, maxDim = PFP_DECODE_MAX_DIM)?.let { bmp ->
                        imageBitmapCache.put(mediaId, bmp)
                    }
                }
            }
        }
    }

    fun loadLogsFromActiveVault() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!vaultManager.isUnlocked) return@launch
            val homeResult = vaultManager.withActiveDek { dek ->
                val repo = vaultManager.activeDiaryRepository()
                repo.loadHomeEntries(
                    dek = dek,
                    fromIndexEntry = { it.toSummaryDiaryLog() },
                    fromFullRecord = { it.toSummaryDiaryLog() }
                )
            }
            preloadHomePfps(homeResult.items)
            withContext(Dispatchers.Main) {
                logs.clear()
                logs.addAll(homeResult.items)
                failedDiaries = homeResult.failed
                if (homeResult.failed.isNotEmpty()) {
                    showStatus(
                        "${homeResult.failed.size} diary ${if (homeResult.failed.size == 1) "file" else "files"} failed to open (${homeResult.failed.joinToString { it.diaryId }}).",
                        BannerKind.ERROR,
                        8000
                    )
                }
            }
        }
    }

    suspend fun loadFullDiaryLog(id: String): DiaryLog? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked) return@withContext null
        runCatching {
            vaultManager.withActiveDek { dek ->
                vaultManager.activeDiaryRepository().loadDiary(id, dek).record.toDiaryLog()
            }
        }.getOrNull()
    }

    private fun resolveFullLogAndRun(log: DiaryLog, onLoaded: (DiaryLog) -> Unit) {
        if (log.isContentLoaded) {
            onLoaded(log)
            return
        }
        viewModelScope.launch {
            val full = loadFullDiaryLog(log.id)
            if (full != null) {
                onLoaded(full)
            } else {
                showStatus("Failed to load diary content.", BannerKind.ERROR)
            }
        }
    }

    fun openUnlockedLog(log: DiaryLog) {
        resolveFullLogAndRun(log) { full -> navigateTo(Route.WORKSPACE, full) }
    }

    fun openUnlockedExport(log: DiaryLog) {
        resolveFullLogAndRun(log) { full -> exportingLog = full }
    }

    // ---------- logs ----------
    fun upsertLog(
        updated: DiaryLog,
        exitAfterSave: Boolean,
        obsoleteMediaIds: Set<String> = emptySet(),
        onPersistResult: (Result<Unit>) -> Unit = {}
    ) {
        val summary = updated.toSummary()
        val i = logs.indexOfFirst { it.id == updated.id }
        if (i >= 0) logs[i] = summary else logs.add(0, summary)
        val persistPayload = logs.map { if (it.id == updated.id) updated else it }
        editingLog = if (exitAfterSave) null else updated
        val savedMediaIds = collectCanvasMediaIds(
            updated.pfp,
            updated.canvasBgSource,
            updated.canvasImages,
            updated.audioAttachments,
            updated.videoAttachments
        )
        hooks.persistLogs(persistPayload, obsoleteMediaIds) { result ->
            viewModelScope.launch {
                result.onSuccess {
                    vaultManager.clearSavedSessionMedia(savedMediaIds + obsoleteMediaIds)
                }.onFailure { err ->
                    val st = storageFailureBanner("Save failed", err)
                    showStatus(st.message, st.kind, 5000)
                }
                onPersistResult(result)
            }
        }
        if (exitAfterSave) navigateTo(Route.HOME)
    }

    fun cleanupUnusedSessionMedia(candidateIds: Set<String>) {
        if (candidateIds.isNotEmpty()) {
            vaultManager.unprotectSessionMedia(candidateIds)
            hooks.deleteUnreferencedMedia(candidateIds) { result ->
                result.onFailure { err ->
                    viewModelScope.launch {
                        val st = storageFailureBanner("Media cleanup failed", err)
                        showStatus(st.message, st.kind, 4000)
                    }
                }
            }
        }
    }

    fun togglePin(id: String) {
        val i = logs.indexOfFirst { it.id == id }
        if (i >= 0) logs[i] = logs[i].copy(pinned = !logs[i].pinned)
        openMenuLogId = null
        hooks.persistLogs(logs.toList()) { result ->
            result.onFailure { err ->
                viewModelScope.launch {
                    val st = storageFailureBanner("Pin update failed", err)
                    showStatus(st.message, st.kind, 4000)
                }
            }
        }
    }

    fun requestDelete(id: String) {
        openMenuLogId = null
        val target = logs.find { it.id == id } ?: editingLog ?: return
        if (target.diaryLockPin != null) { pendingUnlock = PendingUnlock(target, UnlockAction.DELETE); return }
        pendingDeleteLog = target
    }

    fun confirmDelete() {
        val t = pendingDeleteLog ?: return
        if (t.reminderAt != null) {
            hooks.cancelReminder(t.id)
        }
        logs.removeAll { it.id == t.id }
        pendingDeleteLog = null
        hooks.deleteDiary(t.id) { result ->
            result.onFailure { err ->
                viewModelScope.launch {
                    val st = storageFailureBanner("Delete failed", err)
                    showStatus(st.message, st.kind, 5000)
                }
            }
        }
        if (route != Route.HOME) navigateTo(Route.HOME)
    }

    fun openLog(log: DiaryLog) {
        if (log.diaryLockPin != null) pendingUnlock = PendingUnlock(log, UnlockAction.OPEN)
        else openUnlockedLog(log)
    }

    fun requestExport(log: DiaryLog) {
        openMenuLogId = null
        if (exportProcessingBanner != null) return
        if (log.diaryLockPin != null) pendingUnlock = PendingUnlock(log, UnlockAction.EXPORT) else openUnlockedExport(log)
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
            showStatus("Passphrase must be at least 8 characters.", BannerKind.ERROR)
            return
        }
        exportStatusBanner = null
        exportProcessingBanner = "Encrypting \"${log.heading}\" as .likkho archive... Please wait."
        viewModelScope.launch {
            val res = runCatching { hooks.shareDiaryAsArchive(log, passphrase) }
            exportProcessingBanner = null
            res.onSuccess { showStatus(it, BannerKind.SUCCESS) }
                .onFailure { showStatus("Cannot open this file", BannerKind.ERROR) }
        }
    }

    fun exportLog(format: ExportFormat) {
        val log = exportingLog ?: return
        exportingLog = null
        exportStatusBanner = null
        exportProcessingBanner = "Exporting \"${log.heading}\" as ${format.label}... Please wait."
        viewModelScope.launch {
            val res = runCatching { hooks.exportDiary(log, format) }
            exportProcessingBanner = null
            res.onSuccess { showStatus(it, BannerKind.SUCCESS) }
                .onFailure { showStatus("Export failed.", BannerKind.ERROR) }
        }
    }

    fun showStatus(msg: String, kind: BannerKind = BannerKind.SUCCESS, millis: Long = 3200) {
        val state = StatusBannerState(msg, kind)
        exportStatusBanner = state
        viewModelScope.launch { delay(millis); if (exportStatusBanner == state) exportStatusBanner = null }
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
                val decoyChars = pin.toCharArray()
                val primChars = (savedPasscode ?: "0000").toCharArray()
                try {
                    vaultManager.setDecoyPasscode(decoyChars, primChars)
                } finally {
                    CryptoUtils.zeroize(decoyChars)
                    CryptoUtils.zeroize(primChars)
                }
            }
            secondaryPasscode = "••••"
            return true
        }

        if (pin != null && secondaryPasscode != null && pin == secondaryPasscode) return false
        if (pin == null) {
            viewModelScope.launch(Dispatchers.IO) {
                vaultManager.removePrimaryPasscode()
                val hasBio = vaultManager.hasBiometric(VaultId.PRIMARY)
                withContext(Dispatchers.Main) {
                    biometricsEnabled = hasBio
                }
            }
            savedPasscode = null
            secondaryPasscode = null
            vaultMode = VaultMode.PRIMARY
            isLocked = false
            biometricsEnabled = false
            return true
        }
        viewModelScope.launch(Dispatchers.IO) {
            val chars = pin.toCharArray()
            try {
                vaultManager.setPrimaryPasscode(chars)
                val hasBio = vaultManager.hasBiometric(VaultId.PRIMARY)
                withContext(Dispatchers.Main) {
                    biometricsEnabled = hasBio
                }
            } finally {
                CryptoUtils.zeroize(chars)
            }
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
            val decoyChars = pin.toCharArray()
            val primChars = (savedPasscode ?: "0000").toCharArray()
            try {
                vaultManager.setDecoyPasscode(decoyChars, primChars)
            } finally {
                CryptoUtils.zeroize(decoyChars)
                CryptoUtils.zeroize(primChars)
            }
        }
        secondaryPasscode = "••••"
        return true
    }

    suspend fun verifyDiaryLockPin(log: DiaryLog, pin: String): Boolean = withContext(Dispatchers.IO) {
        val resolved = if (log.diaryLockPin == INDEX_LOCKED_MARKER) {
            loadFullDiaryLog(log.id) ?: return@withContext false
        } else {
            log
        }
        checkDiaryLockPin(resolved, pin)
    }

    fun checkDiaryLockPin(log: DiaryLog, pin: String): Boolean {
        val lock = log.diaryLockPin ?: return true
        if (lock == INDEX_LOCKED_MARKER) return false
        if (lock == pin) return true
        if (lock.contains(":")) {
            val parts = lock.split(":", limit = 2)
            if (parts.size == 2) {
                val salt = CryptoUtils.fromHex(parts[0])
                val expectedHash = CryptoUtils.fromHex(parts[1])
                val pinChars = pin.toCharArray()
                val computedHash = try {
                    CryptoUtils.pbkdf2Sha256(pinChars, salt, 100_000)
                } finally {
                    CryptoUtils.zeroize(pinChars)
                }
                return CryptoUtils.constantTimeEquals(expectedHash, computedHash)
            }
        }
        return false
    }

    private val bitmapCacheMaxBytes: Int = BitmapCachePolicy.computeMaxSizeBytes()
    private val screenBitmapTracker = ScreenBitmapTracker()
    private val imageBitmapCache = object : LruCache<String, ImageBitmap>(bitmapCacheMaxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return value.asAndroidBitmap().byteCount.coerceAtLeast(1)
        }
    }

    private fun isHomePfpKey(key: String): Boolean =
        logs.any { it.pfp?.source == key }

    val bitmapKeyLifecycle: BitmapKeyLifecycle = object : BitmapKeyLifecycle {
        override fun acquire(key: String) {
            screenBitmapTracker.acquire(key)
        }

        override fun release(key: String) {
            if (screenBitmapTracker.release(key) && !isHomePfpKey(key)) {
                imageBitmapCache.remove(key)
            }
        }
    }

    fun pruneInactiveBitmaps() {
        val active = screenBitmapTracker.activeKeys()
        val homePfpKeys = logs.mapNotNull { it.pfp?.source }.filter { it.isNotBlank() }.toSet()
        for (key in imageBitmapCache.snapshot().keys) {
            if (!active.contains(key) && !homePfpKeys.contains(key)) {
                imageBitmapCache.remove(key)
            }
        }
    }

    fun onTrimMemory(level: Int) {
        when (BitmapCachePolicy.classifyTrimMemory(level)) {
            TrimMemoryAction.CLEAR_ALL -> imageBitmapCache.evictAll()
            TrimMemoryAction.TRIM_HALF -> imageBitmapCache.trimToSize(bitmapCacheMaxBytes / 2)
            TrimMemoryAction.KEEP -> Unit
        }
    }

    private fun queryUriSize(uri: Uri): Long {
        val app = getApplication<Application>()
        var reportedSize = 0L
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) reportedSize = c.getLong(sizeIdx)
                }
            }
        }
        if (reportedSize <= 0L) {
            runCatching {
                app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0L) reportedSize = afd.length
                }
            }
        }
        return reportedSize.coerceAtLeast(0L)
    }

    suspend fun importImageUri(
        uri: Uri,
        x: Float = 24f,
        y: Float = 120f,
        isCancelled: (() -> Boolean)? = null,
        onPhase: ((ImportPhase) -> Unit)? = null,
        onProgress: ((bytesDone: Long, bytesTotal: Long) -> Unit)? = null,
    ): CanvasImage? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked) return@withContext null
        onPhase?.invoke(ImportPhase.PREPARING)
        if (isCancelled?.invoke() == true) throw ImportCancelledException()

        val app = getApplication<Application>()
        val reportedSize = queryUriSize(uri)
        val orientation = runCatching {
            app.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            app.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        }
        if (isCancelled?.invoke() == true) throw ImportCancelledException()

        val swapDims = isExifDimensionSwapped(orientation)
        val rawW = if (swapDims) bounds.outHeight else bounds.outWidth
        val rawH = if (swapDims) bounds.outWidth else bounds.outHeight
        val aspect = if (rawW > 0 && rawH > 0) {
            rawW.toFloat() / rawH.toFloat()
        } else {
            4f / 3f
        }
        val initW: Float
        val initH: Float
        if (aspect >= 1f) {
            initW = 200f
            initH = (initW / aspect).coerceIn(72f, 320f)
        } else {
            initH = 240f
            initW = (initH * aspect).coerceIn(72f, 320f)
        }

        val mediaId = CryptoUtils.randomId("med_")
        try {
            val rawStream = app.contentResolver.openInputStream(uri) ?: return@withContext null
            val importedBytes = CountingInputStream(
                delegate = rawStream,
                totalBytes = reportedSize,
                isCancelled = isCancelled,
                onFirstRead = { onPhase?.invoke(ImportPhase.ENCRYPTING) },
                onProgress = onProgress,
                onEof = { onPhase?.invoke(ImportPhase.VERIFYING) }
            ).use { counting ->
                vaultManager.withActiveDek { dek ->
                    vaultManager.activeMediaRepository().importMedia(mediaId, counting, dek, reportedSize)
                }
                counting.bytesRead
            }
            if (importedBytes <= 0L) {
                vaultManager.activeMediaRepository().deleteMedia(mediaId)
                vaultManager.unprotectSessionMedia(setOf(mediaId))
                return@withContext null
            }
            if (isCancelled?.invoke() == true) {
                throw ImportCancelledException()
            }

            decodeSampledBitmapFromEncryptedMedia(mediaId)?.let { imageBitmapCache.put(mediaId, it) }
            if (isCancelled?.invoke() == true) {
                throw ImportCancelledException()
            }
            vaultManager.recordSessionImportedMedia(mediaId)
            CanvasImage(
                id = mediaId,
                source = mediaId,
                x = x.coerceAtLeast(0f),
                y = y.coerceAtLeast(0f),
                width = initW,
                height = initH,
                sizeBytes = if (reportedSize > 0L) reportedSize else importedBytes
            )
        } catch (t: Throwable) {
            runCatching {
                vaultManager.activeMediaRepository().deleteMedia(mediaId)
                vaultManager.unprotectSessionMedia(setOf(mediaId))
            }
            imageBitmapCache.remove(mediaId)
            throw t
        }
    }

    suspend fun importVideoUri(
        uri: Uri,
        x: Float = 24f,
        y: Float = 140f,
        isCancelled: (() -> Boolean)? = null,
        onPhase: ((ImportPhase) -> Unit)? = null,
        onProgress: ((bytesDone: Long, bytesTotal: Long) -> Unit)? = null,
    ): CanvasVideo? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked) return@withContext null
        onPhase?.invoke(ImportPhase.PREPARING)
        if (isCancelled?.invoke() == true) throw ImportCancelledException()

        val app = getApplication<Application>()
        var displayName: String? = null
        var reportedSize: Long = queryUriSize(uri)
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) displayName = c.getString(nameIdx)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx) && c.getLong(sizeIdx) > 0L) reportedSize = c.getLong(sizeIdx)
                }
            }
        }
        var durationSec = 1
        var initW = 220f
        var initH = 150f
        var thumbBmp: ImageBitmap? = null
        runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(app, uri)
                val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                durationSec = ((ms + 500L) / 1000L).toInt().coerceAtLeast(1)
                val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val swapped = rot == 90 || rot == 270
                val dispW = if (swapped) vh else vw
                val dispH = if (swapped) vw else vh
                if (dispW > 0 && dispH > 0) {
                    val aspect = dispW.toFloat() / dispH.toFloat()
                    if (aspect >= 1f) {
                        initW = 240f
                        initH = (initW / aspect).coerceIn(110f, 320f)
                    } else {
                        initH = 260f
                        initW = (initH * aspect).coerceIn(110f, 320f)
                    }
                }
                thumbBmp = mmr.getFrameAtTime(0)?.let { scaleBitmapMax(it, 720).asImageBitmap() }
            } finally {
                mmr.release()
            }
        }
        if (isCancelled?.invoke() == true) throw ImportCancelledException()

        val mediaId = CryptoUtils.randomId("med_")
        try {
            val stream = app.contentResolver.openInputStream(uri) ?: return@withContext null
            CountingInputStream(
                delegate = stream,
                totalBytes = reportedSize,
                isCancelled = isCancelled,
                onFirstRead = { onPhase?.invoke(ImportPhase.ENCRYPTING) },
                onProgress = onProgress,
                onEof = { onPhase?.invoke(ImportPhase.VERIFYING) }
            ).use { s ->
                vaultManager.withActiveDek { dek ->
                    vaultManager.activeMediaRepository().importMedia(mediaId, s, dek, reportedSize)
                }
            }
            if (isCancelled?.invoke() == true) {
                throw ImportCancelledException()
            }
            val storedSize = if (reportedSize > 0L) reportedSize else vaultManager.activeMediaRepository().getMediaFile(mediaId).length()
            thumbBmp?.let { imageBitmapCache.put("vthumb_$mediaId", it) }
            if (isCancelled?.invoke() == true) {
                throw ImportCancelledException()
            }
            val cleanName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "Video_${mediaId.take(6)}"
            vaultManager.recordSessionImportedMedia(mediaId)
            CanvasVideo(
                id = mediaId,
                name = cleanName,
                source = mediaId,
                durationSec = durationSec,
                x = x.coerceAtLeast(0f),
                y = y.coerceAtLeast(0f),
                width = initW,
                height = initH,
                sizeBytes = storedSize
            )
        } catch (t: Throwable) {
            runCatching {
                vaultManager.activeMediaRepository().deleteMedia(mediaId)
                vaultManager.unprotectSessionMedia(setOf(mediaId))
            }
            imageBitmapCache.remove("vthumb_$mediaId")
            throw t
        }
    }

    suspend fun importAudioUri(
        uri: Uri,
        x: Float = 24f,
        y: Float = 160f,
        isCancelled: (() -> Boolean)? = null,
        onPhase: ((ImportPhase) -> Unit)? = null,
        onProgress: ((bytesDone: Long, bytesTotal: Long) -> Unit)? = null,
    ): CanvasAudio? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked) return@withContext null
        onPhase?.invoke(ImportPhase.PREPARING)
        if (isCancelled?.invoke() == true) throw ImportCancelledException()

        val app = getApplication<Application>()
        var displayName: String? = null
        var reportedSize: Long = queryUriSize(uri)
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) displayName = c.getString(idx)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx) && c.getLong(sizeIdx) > 0L) reportedSize = c.getLong(sizeIdx)
                }
            }
        }
        val durationSec = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(app, uri)
                val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                ((ms + 500L) / 1000L).toInt().coerceAtLeast(1)
            } finally {
                mmr.release()
            }
        }.getOrDefault(1)
        if (isCancelled?.invoke() == true) throw ImportCancelledException()

        val mediaId = CryptoUtils.randomId("med_")
        try {
            val stream = app.contentResolver.openInputStream(uri) ?: return@withContext null
            CountingInputStream(
                delegate = stream,
                totalBytes = reportedSize,
                isCancelled = isCancelled,
                onFirstRead = { onPhase?.invoke(ImportPhase.ENCRYPTING) },
                onProgress = onProgress,
                onEof = { onPhase?.invoke(ImportPhase.VERIFYING) }
            ).use { s ->
                vaultManager.withActiveDek { dek ->
                    vaultManager.activeMediaRepository().importMedia(mediaId, s, dek, reportedSize)
                }
            }
            if (isCancelled?.invoke() == true) {
                throw ImportCancelledException()
            }
            val cleanName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "Audio_${mediaId.take(6)}"
            val ext = cleanName.substringAfterLast('.', "").lowercase().ifBlank { "audio" }
            vaultManager.recordSessionImportedMedia(mediaId)
            CanvasAudio(
                id = mediaId,
                name = cleanName,
                format = ext,
                audioUri = mediaId,
                durationSec = durationSec,
                x = x.coerceAtLeast(0f),
                y = y.coerceAtLeast(0f)
            )
        } catch (t: Throwable) {
            runCatching {
                vaultManager.activeMediaRepository().deleteMedia(mediaId)
                vaultManager.unprotectSessionMedia(setOf(mediaId))
            }
            throw t
        }
    }

    suspend fun importMediaUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked) return@withContext null
        val app = getApplication<Application>()
        val reportedSize = queryUriSize(uri)
        val mediaId = CryptoUtils.randomId("med_")
        try {
            val rawStream = app.contentResolver.openInputStream(uri) ?: return@withContext null
            val importedBytes = CountingInputStream(rawStream).use { counting ->
                vaultManager.withActiveDek { dek ->
                    vaultManager.activeMediaRepository().importMedia(mediaId, counting, dek, reportedSize)
                }
                counting.bytesRead
            }
            if (importedBytes <= 0L) {
                vaultManager.activeMediaRepository().deleteMedia(mediaId)
                vaultManager.unprotectSessionMedia(setOf(mediaId))
                return@withContext null
            }
            decodeSampledBitmapFromEncryptedMedia(mediaId)?.let { imageBitmapCache.put(mediaId, it) }
            vaultManager.recordSessionImportedMedia(mediaId)
            mediaId
        } catch (t: Throwable) {
            runCatching {
                vaultManager.activeMediaRepository().deleteMedia(mediaId)
                vaultManager.unprotectSessionMedia(setOf(mediaId))
            }
            imageBitmapCache.remove(mediaId)
            throw t
        }
    }

    suspend fun openDecryptedMediaDataSource(mediaId: String): MediaDataSource? = withContext(Dispatchers.IO) {
        if (mediaId.isBlank() || !vaultManager.isUnlocked) return@withContext null
        runCatching {
            vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().openMediaDataSource(mediaId, dek)
            }
        }.getOrNull()
    }

    suspend fun loadVideoThumbnailBitmap(mediaId: String): ImageBitmap? {
        if (mediaId.isBlank()) return null
        val cacheKey = "vthumb_$mediaId"
        imageBitmapCache.get(cacheKey)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            val ds = openDecryptedMediaDataSource(mediaId) ?: return@withContext null
            runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(ds)
                    mmr.getFrameAtTime(0)?.let { scaleBitmapMax(it, 720).asImageBitmap() }
                } finally {
                    mmr.release()
                    runCatching { ds.close() }
                }
            }.getOrNull()
        } ?: return null
        imageBitmapCache.put(cacheKey, bmp)
        return bmp
    }

    suspend fun getMediaSizeBytes(mediaId: String): Long = withContext(Dispatchers.IO) {
        if (mediaId.isBlank() || !vaultManager.isUnlocked) return@withContext 0L
        runCatching { vaultManager.activeMediaRepository().getMediaFile(mediaId).length() }.getOrDefault(0L)
    }

    suspend fun loadEditableImageBitmap(mediaId: String, maxDim: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        if (mediaId.isBlank() || !vaultManager.isUnlocked) return@withContext null
        decodeRawBitmapFromEncryptedMedia(mediaId, maxDim = maxDim)
    }

    suspend fun importEditedImageBytes(
        bytes: ByteArray,
        previewBitmap: ImageBitmap? = null
    ): String? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked || bytes.isEmpty()) return@withContext null
        val mediaId = CryptoUtils.randomId("med_")
        try {
            vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMediaBytes(mediaId, bytes, dek)
            }
            val cacheBmp = previewBitmap ?: decodeSampledBitmapFromEncryptedMedia(mediaId)
            if (cacheBmp != null) {
                imageBitmapCache.put(mediaId, cacheBmp)
            }
            vaultManager.recordSessionImportedMedia(mediaId)
            mediaId
        } catch (t: Throwable) {
            runCatching {
                vaultManager.activeMediaRepository().deleteMedia(mediaId)
                vaultManager.unprotectSessionMedia(setOf(mediaId))
            }
            imageBitmapCache.remove(mediaId)
            null
        }
    }

    fun getCachedImageBitmap(mediaId: String): ImageBitmap? {
        if (mediaId.isBlank()) return null
        return imageBitmapCache.get(mediaId)
    }

    suspend fun loadDecryptedImageBitmap(mediaId: String): ImageBitmap? {
        if (mediaId.isBlank()) return null
        imageBitmapCache.get(mediaId)?.let { return it }
        val bmp = withContext(Dispatchers.IO) { decodeSampledBitmapFromEncryptedMedia(mediaId) } ?: return null
        imageBitmapCache.put(mediaId, bmp)
        return bmp
    }

    private fun decodeSampledBitmapFromEncryptedMedia(mediaId: String, maxDim: Int = 1600): ImageBitmap? {
        return decodeRawBitmapFromEncryptedMedia(mediaId, maxDim)?.asImageBitmap()
    }

    private fun decodeRawBitmapFromEncryptedMedia(mediaId: String, maxDim: Int = 1600): Bitmap? {
        if (!vaultManager.isUnlocked) return null
        return runCatching {
            vaultManager.withActiveDek { dek ->
                val repo = vaultManager.activeMediaRepository()
                val fileSize = repo.getMediaFile(mediaId).length()
                if (fileSize in 1L..SINGLE_READ_DECODE_MAX_BYTES) {
                    val decryptedBytes = repo.readDecryptedBytes(mediaId, dek)
                    val orientation = runCatching {
                        ByteArrayInputStream(decryptedBytes).use { s ->
                            ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        }
                    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withActiveDek null

                    var sample = 1
                    while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                        sample *= 2
                    }
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    val rawBitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, opts)
                        ?: return@withActiveDek null
                    return@withActiveDek applyExifOrientation(rawBitmap, orientation)
                }

                val orientation = runCatching {
                    repo.openDecryptedStream(mediaId, dek).use { s ->
                        ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    }
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                repo.openDecryptedStream(mediaId, dek).use { s ->
                    BitmapFactory.decodeStream(s, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withActiveDek null

                var sample = 1
                while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val rawBitmap = repo.openDecryptedStream(mediaId, dek).use { s ->
                    BitmapFactory.decodeStream(s, null, opts)
                } ?: return@withActiveDek null
                applyExifOrientation(rawBitmap, orientation)
            }
        }.getOrNull()
    }

    private companion object {
        private const val PFP_DECODE_MAX_DIM = 512
        private const val SINGLE_READ_DECODE_MAX_BYTES = 16L * 1024L * 1024L
    }

    private fun isExifDimensionSwapped(orientation: Int): Boolean =
        orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented != bitmap) bitmap.recycle()
            oriented
        }.getOrDefault(bitmap)
    }

    private fun scaleBitmapMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val scale = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
            if (scaled != bitmap) bitmap.recycle()
            scaled
        }.getOrDefault(bitmap)
    }

    private fun fontStorageDir(): File =
        (hooks as? RealFeatureHooks)?.baseStorageDir ?: getApplication<Application>().filesDir

    suspend fun importCustomFont(uri: Uri): CustomFont? = withContext(Dispatchers.IO) {
        val imported = FontRegistry.importFontFromUri(getApplication(), uri, fontStorageDir())
        if (imported != null) {
            withContext(Dispatchers.Main) {
                if (customFonts.none { it.id == imported.id }) {
                    customFonts.add(imported)
                }
            }
        }
        imported
    }

    fun clearAllAppData() {
        viewModelScope.launch(Dispatchers.IO) {
            hooks.clearAllData()
            FontRegistry.clearAll(fontStorageDir())
            imageBitmapCache.evictAll()
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
        val summary = log.toSummary()
        val i = logs.indexOfFirst { it.id == log.id }
        if (i >= 0) logs[i] = summary else logs.add(0, summary)
        val persistPayload = logs.map { if (it.id == log.id) log else it }
        hooks.persistLogs(persistPayload) { result ->
            viewModelScope.launch {
                result.onSuccess {
                    showStatus("Restored \"${log.heading.ifBlank { "Diary" }}\" to app.", BannerKind.SUCCESS, 3000)
                }.onFailure { err ->
                    val st = storageFailureBanner("Restore save failed", err)
                    showStatus(st.message, st.kind, 5000)
                }
            }
        }
    }

    fun onRestoreBundle(restored: List<DiaryLog>) {
        if (restored.isEmpty()) return
        val restoredSummaries = restored.map { it.toSummary() }
        val merged = mergeRestoredLogs(logs.toList(), restoredSummaries)
        logs.clear()
        logs.addAll(merged)
        val byId = restored.associateBy { it.id }
        val persistPayload = merged.map { byId[it.id] ?: it }
        hooks.persistLogs(persistPayload) { result ->
            result.onFailure { err ->
                viewModelScope.launch {
                    val st = storageFailureBanner("Restore persist failed", err)
                    showStatus(st.message, st.kind, 5000)
                }
            }
        }
    }
}

internal fun mergeRestoredLogs(existing: List<DiaryLog>, restored: List<DiaryLog>): List<DiaryLog> {
    if (restored.isEmpty()) return existing
    val existingById = existing.associateBy { it.id }.toMutableMap()
    for (incoming in restored) {
        val current = existingById[incoming.id]
        if (current == null) {
            existingById[incoming.id] = incoming
        } else if (current == incoming) {
            // Identical record already present
        } else if (incoming.updatedAt > current.updatedAt) {
            val copyId = "${current.id}_copy_${current.updatedAt}"
            if (!existingById.containsKey(copyId)) {
                existingById[copyId] = current.copy(
                    id = copyId,
                    heading = "${current.heading} (Local Copy)"
                )
            }
            existingById[incoming.id] = incoming
        } else {
            val copyId = "${incoming.id}_copy_${incoming.updatedAt}"
            if (!existingById.containsKey(copyId)) {
                existingById[copyId] = incoming.copy(
                    id = copyId,
                    heading = "${incoming.heading} (Restored Copy)"
                )
            }
        }
    }
    return existingById.values.toList()
}
