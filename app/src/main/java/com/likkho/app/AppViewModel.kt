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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.likkho.app.editor.FontRegistry
import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.UnlockResult
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
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

    private val imageBitmapCache = LruCache<String, ImageBitmap>(24)

    suspend fun importImageUri(uri: Uri, x: Float = 24f, y: Float = 120f): CanvasImage? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked) return@withContext null
        val app = getApplication<Application>()
        val rawBytes = runCatching {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        if (rawBytes.isEmpty()) return@withContext null

        val orientation = readExifOrientation(rawBytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
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
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMediaBytes(
                CryptoUtils.randomId("med_"),
                rawBytes,
                dek
            )
        }
        decodeSampledBitmap(rawBytes)?.let { imageBitmapCache.put(mediaId, it) }
        CanvasImage(
            id = mediaId,
            source = mediaId,
            x = x.coerceAtLeast(0f),
            y = y.coerceAtLeast(0f),
            width = initW,
            height = initH,
            sizeBytes = rawBytes.size.toLong()
        )
    }

    suspend fun importVideoUri(uri: Uri, x: Float = 24f, y: Float = 140f): CanvasVideo? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked) return@withContext null
        val app = getApplication<Application>()
        var displayName: String? = null
        var reportedSize: Long = 0L
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) displayName = c.getString(nameIdx)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) reportedSize = c.getLong(sizeIdx)
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
        val stream = app.contentResolver.openInputStream(uri) ?: return@withContext null
        val mediaId = stream.use { s ->
            vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMedia(CryptoUtils.randomId("med_"), s, dek, reportedSize)
            }
        }
        val storedSize = if (reportedSize > 0L) reportedSize else vaultManager.activeMediaRepository().getMediaFile(mediaId).length()
        thumbBmp?.let { imageBitmapCache.put("vthumb_$mediaId", it) }
        val cleanName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "Video_${mediaId.take(6)}"
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
    }

    suspend fun importAudioUri(uri: Uri, x: Float = 24f, y: Float = 160f): CanvasAudio? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked) return@withContext null
        val app = getApplication<Application>()
        var displayName: String? = null
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) displayName = c.getString(idx)
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
        val stream = app.contentResolver.openInputStream(uri) ?: return@withContext null
        val mediaId = stream.use { s ->
            vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMedia(s, dek)
            }
        }
        val cleanName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: "Audio_${mediaId.take(6)}"
        val ext = cleanName.substringAfterLast('.', "").lowercase().ifBlank { "audio" }
        CanvasAudio(
            id = mediaId,
            name = cleanName,
            format = ext,
            audioUri = mediaId,
            durationSec = durationSec,
            x = x.coerceAtLeast(0f),
            y = y.coerceAtLeast(0f)
        )
    }

    suspend fun importMediaUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (!vaultManager.isUnlocked) return@withContext null
        val app = getApplication<Application>()
        val rawBytes = runCatching {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null
        if (rawBytes.isEmpty()) return@withContext null
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMediaBytes(
                CryptoUtils.randomId("med_"),
                rawBytes,
                dek
            )
        }
        decodeSampledBitmap(rawBytes)?.let { imageBitmapCache.put(mediaId, it) }
        mediaId
    }

    suspend fun loadDecryptedMediaBytes(mediaId: String): ByteArray? = withContext(Dispatchers.IO) {
        if (mediaId.isBlank() || !vaultManager.isUnlocked) return@withContext null
        runCatching {
            vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().readDecryptedBytes(mediaId, dek)
            }
        }.getOrNull()
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

    suspend fun loadDecryptedImageBitmap(mediaId: String): ImageBitmap? {
        if (mediaId.isBlank()) return null
        imageBitmapCache.get(mediaId)?.let { return it }
        val bytes = loadDecryptedMediaBytes(mediaId) ?: return null
        val bmp = withContext(Dispatchers.Default) { decodeSampledBitmap(bytes) } ?: return null
        imageBitmapCache.put(mediaId, bmp)
        return bmp
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int = 1600): ImageBitmap? {
        if (bytes.isEmpty()) return null
        val orientation = readExifOrientation(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val rawBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return applyExifOrientation(rawBitmap, orientation).asImageBitmap()
    }

    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

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
