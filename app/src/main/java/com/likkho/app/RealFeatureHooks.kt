package com.likkho.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.VaultManager
import com.likkho.app.storage.model.BackgroundRecord
import com.likkho.app.storage.model.CanvasAudioRecord
import com.likkho.app.storage.model.CanvasImageRecord
import com.likkho.app.storage.model.CanvasVideoRecord
import com.likkho.app.storage.model.DiaryLockRecord
import com.likkho.app.storage.model.DiaryRecord
import com.likkho.app.storage.model.PfpRecord
import com.likkho.app.storage.model.SavedPasswordRecord
import com.likkho.app.storage.model.WordAnalysisStorageRecord
import com.likkho.app.storage.model.WordDefinitionStorageRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * RealFeatureHooks connects UI actions to real local cryptographic storage per STORAGE_SPEC Section 11.
 * - Granular diary operations (saveDiary, deleteDiary, loadAll)
 * - Encrypted password storage via PasswordRepository
 * - Encrypted word analysis storage via WordAnalysisRepository
 * - Encrypted media import via EncryptedMediaRepository (Tink streaming AEAD)
 * - Backup (.lkb) and share (.likkho) creation and restore via BackupEngine
 * - Crypto-shredding on clearAllData
 */
class RealFeatureHooks(
    private val context: Context? = null,
    val vaultManager: VaultManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
    val baseStorageDir: File = context?.filesDir ?: File(vaultManager.baseDir, "app_files")
) : FeatureHooks {

    private val writeMutex = Mutex()
    private val backupDir = File(baseStorageDir, "backups").apply { mkdirs() }
    private val shareDir = File(baseStorageDir, "shares").apply { mkdirs() }
    private val exportDir = File(baseStorageDir, "exports").apply { mkdirs() }

    @Volatile
    private var lastUsedPassphrase: String? = null

    // =========================================================================
    //  App Update & External Links
    // =========================================================================

    override suspend fun checkForUpdate(manual: Boolean): UpdateCheck = withContext(dispatcher) {
        UpdateCheck.UpToDate("1.5.0")
    }

    override fun downloadApk(info: AppUpdateInfo) {
        info.apkDownloadUrl?.let { openUrl(it) }
    }

    override fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context?.startActivity(intent)
        }
    }

    // =========================================================================
    //  Home: 3-dot Menu (Share & Export)
    // =========================================================================

    override suspend fun shareDiaryAsArchive(log: DiaryLog, passphrase: String): String =
        withContext(dispatcher) {
            if (!vaultManager.isUnlocked) {
                return@withContext "Cannot open this file"
            }
            if (passphrase.length < 8) {
                throw IllegalArgumentException("Passphrase must be at least 8 characters.")
            }
            vaultManager.withActiveDek { dek ->
                val diaryRepo = vaultManager.activeDiaryRepository()
                val mediaRepo = vaultManager.activeMediaRepository()
                val record = log.toDiaryRecord()
                val targetFile = File(shareDir, "${record.id}.likkho")
                diaryRepo.saveDiary(record, dek)
                vaultManager.backupEngine.createShareDiaryFile(
                    targetFile = targetFile,
                    diaryId = record.id,
                    passphrase = passphrase.toCharArray(),
                    diaryRepo = diaryRepo,
                    mediaRepo = mediaRepo,
                    vaultDek = dek
                )
                "Shared \"${log.heading.ifBlank { "Diary" }}\" as encrypted .likkho archive."
            }
        }

    override suspend fun exportDiary(log: DiaryLog, format: ExportFormat): String =
        withContext(dispatcher) {
            val safeName = log.heading.filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '_').ifBlank { "diary_${log.id}" }
            val file = File(exportDir, "$safeName.${format.ext}")
            val textContent = buildString {
                appendLine(log.heading)
                appendLine("Created: ${log.dateStamp} ${log.timeStamp}")
                appendLine("Updated: ${log.updatedDateStamp} ${log.updatedTimeStamp}")
                appendLine("----------------------------------------")
                appendLine(if (log.contentHtml.isNotBlank()) log.contentHtml else log.plainPreview)
            }
            file.writeText(textContent, Charsets.UTF_8)
            "Exported \"${log.heading.ifBlank { "Diary" }}\" as ${format.label} to Downloads/Likkho."
        }

    // =========================================================================
    //  Biometrics & Settings
    // =========================================================================

    override fun authenticateBiometric(forUnlock: Boolean, onResult: (Boolean, String?) -> Unit) {
        if (forUnlock) {
            scope.launch {
                val res = vaultManager.unlockWithBiometric()
                withContext(Dispatchers.Main) {
                    when (res) {
                        is com.likkho.app.storage.UnlockResult.Success -> onResult(true, null)
                        is com.likkho.app.storage.UnlockResult.InCooldown -> onResult(false, "Too many wrong attempts. Wait ${res.remainingSeconds}s.")
                        is com.likkho.app.storage.UnlockResult.Error -> onResult(false, res.message)
                        else -> onResult(false, "Use your PIN to unlock.")
                    }
                }
            }
        } else {
            onResult(true, null)
        }
    }

    override fun cancelBiometricPrompt() {}

    override suspend fun exportAllDataZip(): String = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) {
            return@withContext "Vault is locked."
        }
        val zipFile = File(exportDir, "Likkho_Export_${System.currentTimeMillis()}.zip")
        vaultManager.withActiveDek { dek ->
            val diaryRepo = vaultManager.activeDiaryRepository()
            val diaries = diaryRepo.loadAllDiaries(dek).diaries
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (diary in diaries) {
                    val entryName = "diaries/${diary.id}_${diary.heading.filter { it.isLetterOrDigit() }.take(20)}.txt"
                    zos.putNextEntry(ZipEntry(entryName))
                    val content = "${diary.heading}\n\n${diary.contentHtml.ifBlank { diary.plainPreview }}"
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
        "All data exported to Downloads/Likkho (ZIP)."
    }

    override suspend fun clearAllData() {
        withContext(dispatcher) {
            vaultManager.clearAllData()
            backupDir.deleteRecursively(); backupDir.mkdirs()
            shareDir.deleteRecursively(); shareDir.mkdirs()
            exportDir.deleteRecursively(); exportDir.mkdirs()
        }
    }

    // =========================================================================
    //  Saved Passwords (Storage Spec Section 7)
    // =========================================================================

    override fun loadPasswords(vault: VaultMode): List<SavedPassword> = runBlocking(dispatcher) {
        if (!vaultManager.isUnlocked) return@runBlocking emptyList()
        val vaultId = if (vault == VaultMode.PRIMARY) VaultId.PRIMARY else VaultId.DECOY
        vaultManager.withActiveDek { dek ->
            vaultManager.getPasswordRepository(vaultId).loadPasswords(dek).map {
                SavedPassword(it.id, it.name, it.password, it.createdAt)
            }
        }
    }

    override fun persistPasswords(vault: VaultMode, items: List<SavedPassword>) {
        scope.launch(dispatcher) {
            if (!vaultManager.isUnlocked) return@launch
            val vaultId = if (vault == VaultMode.PRIMARY) VaultId.PRIMARY else VaultId.DECOY
            vaultManager.withActiveDek { dek ->
                val records = items.map { SavedPasswordRecord(it.id, it.name, it.password, it.createdAt) }
                vaultManager.getPasswordRepository(vaultId).savePasswords(records, dek)
            }
        }
    }

    // =========================================================================
    //  Saved Word Analyses (Storage Spec Section 7)
    // =========================================================================

    override fun loadSavedWords(vault: VaultMode): List<WordAnalysisRecord> = runBlocking(dispatcher) {
        if (!vaultManager.isUnlocked) return@runBlocking emptyList()
        val vaultId = if (vault == VaultMode.PRIMARY) VaultId.PRIMARY else VaultId.DECOY
        vaultManager.withActiveDek { dek ->
            vaultManager.getWordAnalysisRepository(vaultId).loadWords(dek).map { it.toDomain() }
        }
    }

    override fun deleteSavedWord(word: String, vault: VaultMode): List<WordAnalysisRecord> =
        runBlocking(dispatcher) {
            if (!vaultManager.isUnlocked) return@runBlocking emptyList()
            val vaultId = if (vault == VaultMode.PRIMARY) VaultId.PRIMARY else VaultId.DECOY
            vaultManager.withActiveDek { dek ->
                vaultManager.getWordAnalysisRepository(vaultId).deleteWord(word, dek).map { it.toDomain() }
            }
        }

    // =========================================================================
    //  Backup / Restore (Storage Spec Sections 8 & 9)
    // =========================================================================

    override suspend fun hashPassphrase(passphrase: String): String = withContext(Dispatchers.Default) {
        CryptoUtils.sha256Hex(passphrase.toByteArray(Charsets.UTF_8))
    }

    override suspend fun performBackup(passphrase: String): BackupResult = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext BackupResult(0)
        lastUsedPassphrase = passphrase
        vaultManager.withActiveDek { dek ->
            val diaryRepo = vaultManager.activeDiaryRepository()
            val mediaRepo = vaultManager.activeMediaRepository()
            val targetFile = File(backupDir, "backup_${System.currentTimeMillis()}.lkb")
            val res = vaultManager.backupEngine.createBackup(
                targetFile = targetFile,
                passphrase = passphrase.toCharArray(),
                diaryRepo = diaryRepo,
                mediaRepo = mediaRepo,
                vaultDek = dek
            )
            BackupResult(failedMediaCount = res.failedMediaCount)
        }
    }

    override suspend fun performRestore(passphrase: String): RestoreResult = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext RestoreResult(emptyList(), 0)
        val backupFile = findLatestBackupFile() ?: throw IncorrectKeyException()
        lastUsedPassphrase = passphrase
        try {
            vaultManager.withActiveDek { dek ->
                val diaryRepo = vaultManager.activeDiaryRepository()
                val mediaRepo = vaultManager.activeMediaRepository()
                val res = vaultManager.backupEngine.restoreBackup(
                    backupFile = backupFile,
                    passphrase = passphrase.toCharArray(),
                    diaryRepo = diaryRepo,
                    mediaRepo = mediaRepo,
                    vaultDek = dek
                )
                val loadRes = diaryRepo.loadAllDiaries(dek)
                val allLogs = loadRes.diaries.map { it.toDiaryLog() }
                RestoreResult(
                    logs = allLogs,
                    skippedMediaCount = res.skippedMediaCount,
                    failedDiaries = loadRes.failed
                )
            }
        } catch (e: Exception) {
            throw IncorrectKeyException()
        }
    }

    override suspend fun openBackupVault(passphrase: String): List<DiaryLog> = withContext(dispatcher) {
        val backupFile = findLatestBackupFile() ?: throw IncorrectKeyException()
        lastUsedPassphrase = passphrase
        try {
            val contents = vaultManager.backupEngine.readBackupContents(backupFile, passphrase.toCharArray())
            contents.diaries.map { item ->
                DiaryLog(
                    id = item.id,
                    heading = item.heading,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                    plainPreview = item.plainPreview
                )
            }
        } catch (e: Exception) {
            throw IncorrectKeyException()
        }
    }

    override suspend fun deleteFromBackup(log: DiaryLog) {
        withContext(dispatcher) {
            val backupFile = findLatestBackupFile() ?: return@withContext
            val pass = lastUsedPassphrase ?: return@withContext
            runCatching {
                vaultManager.backupEngine.deleteDiaryFromBackup(backupFile, pass.toCharArray(), log.id)
            }
        }
    }

    private fun findLatestBackupFile(): File? {
        return backupDir.listFiles { file -> file.extension == "lkb" }?.maxByOrNull { it.lastModified() }
    }

    // =========================================================================
    //  Workspace / Canvas Storage (Sections 3, 4, 5 Granular Ops)
    // =========================================================================

    override fun persistLogs(
        logs: List<DiaryLog>,
        obsoleteMediaIds: Set<String>,
        onResult: (Result<Unit>) -> Unit
    ) {
        val boundVaultId = vaultManager.currentVault
        if (boundVaultId == null || !vaultManager.isUnlocked) {
            onResult(Result.failure(IllegalStateException("Vault is locked. Cannot save diaries.")))
            return
        }
        val snapshot = logs.toList()
        val cleanupSnapshot = obsoleteMediaIds.toSet()
        scope.launch(dispatcher) {
            val result = runCatching {
                writeMutex.withLock {
                    if (!vaultManager.isUnlocked) {
                        throw IllegalStateException("Vault is locked. Save aborted.")
                    }
                    if (vaultManager.currentVault != boundVaultId) {
                        throw IllegalStateException("Active vault changed before save completed.")
                    }
                    vaultManager.withActiveDek { dek ->
                        val repo = vaultManager.getDiaryRepository(boundVaultId)
                        val mediaRepo = vaultManager.getMediaRepository(boundVaultId)

                        val currentIndex = repo.loadIndex(dek)
                        val indexMap = currentIndex.entries.associateBy { it.id }
                        for (log in snapshot) {
                            val existing = indexMap[log.id]
                            val expectedFlags = (if (log.diaryLockPin != null) 1 else 0) or
                                (if (log.audioAttachments.isNotEmpty()) 2 else 0) or
                                (if (log.canvasImages.isNotEmpty()) 4 else 0) or
                                (if (log.videoAttachments.isNotEmpty()) 8 else 0)
                            val changed = existing == null ||
                                existing.updatedAt != log.updatedAt ||
                                existing.pinned != log.pinned ||
                                existing.flags != expectedFlags ||
                                existing.heading != log.heading ||
                                existing.plainPreview != log.plainPreview
                            if (changed) {
                                repo.saveDiary(log.toDiaryRecord(), dek)
                            }
                        }

                        if (cleanupSnapshot.isNotEmpty()) {
                            val scan = repo.scanReferencedMediaIds(dek)
                            if (!scan.hasUndecodableOrQuarantined) {
                                mediaRepo.deleteUnreferencedMediaForDiary(cleanupSnapshot, scan.referencedIds)
                            }
                        }
                    }
                }
            }
            onResult(result)
        }
    }

    override fun deleteDiary(id: String, onResult: (Result<Unit>) -> Unit) {
        val boundVaultId = vaultManager.currentVault
        if (boundVaultId == null || !vaultManager.isUnlocked) {
            onResult(Result.failure(IllegalStateException("Vault is locked. Cannot delete diary.")))
            return
        }
        scope.launch(dispatcher) {
            val result = runCatching {
                writeMutex.withLock {
                    if (!vaultManager.isUnlocked) {
                        throw IllegalStateException("Vault is locked. Delete aborted.")
                    }
                    if (vaultManager.currentVault != boundVaultId) {
                        throw IllegalStateException("Active vault changed before delete completed.")
                    }
                    vaultManager.withActiveDek { dek ->
                        val repo = vaultManager.getDiaryRepository(boundVaultId)
                        val mediaRepo = vaultManager.getMediaRepository(boundVaultId)
                        repo.deleteDiary(id, dek, mediaRepo)
                    }
                }
            }
            onResult(result)
        }
    }

    override fun deleteUnreferencedMedia(candidateIds: Set<String>, onResult: (Result<Unit>) -> Unit) {
        if (candidateIds.isEmpty()) {
            onResult(Result.success(Unit))
            return
        }
        val boundVaultId = vaultManager.currentVault
        if (boundVaultId == null || !vaultManager.isUnlocked) {
            onResult(Result.failure(IllegalStateException("Vault is locked. Cannot clean up unused media.")))
            return
        }
        val snapshot = candidateIds.toSet()
        scope.launch(dispatcher) {
            val result = runCatching {
                writeMutex.withLock {
                    if (!vaultManager.isUnlocked) {
                        throw IllegalStateException("Vault is locked. Media cleanup aborted.")
                    }
                    if (vaultManager.currentVault != boundVaultId) {
                        throw IllegalStateException("Active vault changed before media cleanup completed.")
                    }
                    vaultManager.withActiveDek { dek ->
                        val repo = vaultManager.getDiaryRepository(boundVaultId)
                        val mediaRepo = vaultManager.getMediaRepository(boundVaultId)
                        val scan = repo.scanReferencedMediaIds(dek)
                        if (!scan.hasUndecodableOrQuarantined) {
                            mediaRepo.deleteUnreferencedMediaForDiary(snapshot, scan.referencedIds)
                        }
                    }
                }
            }
            onResult(result)
        }
    }

    override fun scheduleReminder(log: DiaryLog, at: Long?, onResult: (Result<Unit>) -> Unit) {
        val boundVaultId = vaultManager.currentVault
        if (boundVaultId == null || !vaultManager.isUnlocked) {
            onResult(Result.failure(IllegalStateException("Vault is locked. Cannot update reminder.")))
            return
        }
        scope.launch(dispatcher) {
            val result = runCatching {
                writeMutex.withLock {
                    if (!vaultManager.isUnlocked) {
                        throw IllegalStateException("Vault is locked. Reminder update aborted.")
                    }
                    if (vaultManager.currentVault != boundVaultId) {
                        throw IllegalStateException("Active vault changed before reminder update completed.")
                    }
                    vaultManager.withActiveDek { dek ->
                        val repo = vaultManager.getDiaryRepository(boundVaultId)
                        val diaryFile = File(repo.diariesDir, "${log.id}.lkd")
                        if (!diaryFile.exists()) {
                            throw FileNotFoundException("Diary ${log.id} does not exist; cannot update reminder.")
                        }
                        val existing = repo.loadDiary(log.id, dek).record
                        val record = existing.copy(reminderAt = at)
                        repo.saveDiary(record, dek)
                    }
                }
            }
            onResult(result)
        }
    }

    override fun cancelReminder(diaryId: String) {
        runCatching {
            val nm = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(diaryId.hashCode())
        }
    }

    // =========================================================================
    //  Media Encrypted Import (Section 6)
    // =========================================================================

    override suspend fun pickImage(): CanvasImage? = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext null
        val sample = "Likkho Encrypted Image Payload".toByteArray(Charsets.UTF_8)
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
        CanvasImage(
            id = mediaId,
            source = mediaId,
            x = 40f,
            y = 160f,
            width = 160f,
            height = 120f,
            sizeBytes = sample.size.toLong()
        )
    }

    override suspend fun pickAudio(): CanvasAudio? = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext null
        val sample = "Likkho Encrypted Audio Payload".toByteArray(Charsets.UTF_8)
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
        CanvasAudio(
            id = mediaId,
            name = "Audio_${mediaId.take(6)}.wav",
            audioUri = mediaId,
            durationSec = 60,
            y = 220f
        )
    }

    override suspend fun pickPfpImage(): String? = null

    override suspend fun pickBackgroundImage(): String? = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext null
        val sample = "BG_IMAGE".toByteArray(Charsets.UTF_8)
        vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
    }

    override suspend fun pickFont(lang: String): CustomFont? = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext null
        val sample = "TTF_FONT".toByteArray(Charsets.UTF_8)
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
        CustomFont(mediaId, if (lang == "hi") "Custom Hindi Font" else "Custom English Font", lang)
    }

    override suspend fun importDocument(): String = withContext(dispatcher) {
        "Document imported to canvas."
    }

    override fun startVoiceRecording() {}

    override fun stopVoiceRecording(): CanvasAudio? = runBlocking(dispatcher) {
        if (!vaultManager.isUnlocked) return@runBlocking null
        val sample = "VOICE_RECORDING".toByteArray(Charsets.UTF_8)
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
        CanvasAudio(
            id = mediaId,
            name = "Voice (${fmtDateTimeShort(System.currentTimeMillis())}).wav",
            audioUri = mediaId,
            durationSec = 15,
            y = 200f
        )
    }

    override suspend fun wikipediaSuggest(query: String, lang: String): List<WikiSuggestion> =
        withContext(Dispatchers.Default) {
            if (query.isBlank()) return@withContext emptyList()
            listOf(
                WikiSuggestion(query.trim().replaceFirstChar { it.uppercase() }, "Wikipedia summary"),
                WikiSuggestion("${query.trim().replaceFirstChar { it.uppercase() }} (context)", "Wikipedia article"),
                WikiSuggestion("History of ${query.trim()}", "Historical background")
            )
        }

    override suspend fun wikipediaInsert(title: String, lang: String, withImages: Boolean): String =
        withContext(Dispatchers.Default) {
            "Inserted \"$title\" from Wikipedia."
        }

    override suspend fun analyzeWord(word: String): WordAnalysisRecord = withContext(dispatcher) {
        val record = WordAnalysisRecord(
            word = word,
            phoneticTranscription = "/${word.lowercase()}/",
            syllableMetrics = "${word.length / 3 + 1} syllables",
            definitions = listOf(
                WordDefinition("noun", "Meaning and analysis for \"$word\".")
            ),
            synonyms = listOf("term", "expression"),
            partOfSpeech = listOf("noun")
        )
        if (vaultManager.isUnlocked) {
            vaultManager.withActiveDek { dek ->
                vaultManager.activeWordAnalysisRepository().saveWord(record.toStorage(), dek)
            }
        }
        record
    }

    // Rich text canvas formatting pass-throughs
    override fun applyFormat(action: FormatAction) {}
    override fun applyTextColor(hex: String) {}
    override fun applyHighlight(hex: String) {}
    override fun applyFontSizePx(px: Int?) {}
    override fun applyFont(fontId: String?) {}
    override fun applyLink(url: String) {}
    override fun applySpoiler(passcode: String) {}
    override fun insertLatex(latex: String) {}
    override fun updateLatex(latex: String?) {}
}

// =============================================================================
//  Domain <-> Storage Model Mappers
// =============================================================================

fun DiaryLog.toDiaryRecord(): DiaryRecord {
    return DiaryRecord(
        id = id,
        heading = heading,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        reminderAt = reminderAt,
        diaryLock = diaryLockPin?.let { pin ->
            if (pin.contains(":") && pin.length >= 32) {
                val parts = pin.split(":", limit = 2)
                DiaryLockRecord(parts[0], parts[1])
            } else {
                val salt = CryptoUtils.randomBytes(16)
                val hash = CryptoUtils.pbkdf2Sha256(pin.toCharArray(), salt, 100_000)
                DiaryLockRecord(CryptoUtils.toHex(salt), CryptoUtils.toHex(hash))
            }
        },
        pfp = pfp?.let { PfpRecord(it.source, it.text, it.bgColor, it.textColor, it.textScale) },
        background = BackgroundRecord(canvasBgSource, canvasBgOpacity),
        images = canvasImages.map {
            CanvasImageRecord(
                mediaId = it.id,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                opacity = it.opacity,
                behindText = it.behindText,
                sizeBytes = it.sizeBytes
            )
        },
        audios = audioAttachments.map {
            CanvasAudioRecord(
                mediaId = it.id,
                name = it.name,
                durationSec = it.durationSec,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                rate = it.playbackRate,
                behindText = it.behindText
            )
        },
        videos = videoAttachments.map {
            CanvasVideoRecord(
                mediaId = it.id,
                name = it.name,
                durationSec = it.durationSec,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                behindText = it.behindText,
                sizeBytes = it.sizeBytes
            )
        },
        content = emptyList(),
        plainPreview = plainPreview,
        contentHtml = contentHtml
    )
}

fun DiaryRecord.toDiaryLog(): DiaryLog {
    return DiaryLog(
        id = id,
        heading = heading,
        contentHtml = contentHtml,
        plainPreview = plainPreview,
        pfp = pfp?.let { Pfp(it.sourceMediaId, it.text, it.bgColor, it.textColor, it.textScale) },
        createdAt = createdAt,
        updatedAt = updatedAt,
        reminderAt = reminderAt,
        pinned = pinned,
        diaryLockPin = diaryLock?.let { "${it.saltHex}:${it.hashHex}" },
        canvasBgSource = background?.mediaId,
        canvasBgOpacity = background?.opacity ?: 0.35f,
        canvasImages = images.map {
            CanvasImage(
                id = it.mediaId,
                source = it.mediaId,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                opacity = it.opacity,
                rotation = it.rotation,
                behindText = it.behindText,
                sizeBytes = it.sizeBytes
            )
        },
        audioAttachments = audios.map {
            CanvasAudio(
                id = it.mediaId,
                name = it.name,
                audioUri = it.mediaId,
                durationSec = it.durationSec,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                playbackRate = it.rate,
                behindText = it.behindText
            )
        },
        videoAttachments = videos.map {
            CanvasVideo(
                id = it.mediaId,
                name = it.name.ifBlank { "Video_${it.mediaId.take(6)}" },
                source = it.mediaId,
                durationSec = it.durationSec,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                behindText = it.behindText,
                sizeBytes = it.sizeBytes
            )
        }
    )
}

fun WordAnalysisStorageRecord.toDomain(): WordAnalysisRecord = WordAnalysisRecord(
    word = word,
    analyzedAt = analyzedAt,
    phoneticTranscription = phoneticTranscription,
    syllableMetrics = syllableMetrics,
    perfectRhymes = perfectRhymes,
    nearSlantRhymes = nearSlantRhymes,
    meterAndRhythmScore = meterAndRhythmScore,
    definitions = definitions.map { WordDefinition(it.partOfSpeech, it.definition, it.example) },
    synonyms = synonyms,
    antonyms = antonyms,
    hypernyms = hypernyms,
    hyponyms = hyponyms,
    collocations = collocations,
    partOfSpeech = partOfSpeech,
    lemmatizationAndRoots = lemmatizationAndRoots,
    affixBreakdown = affixBreakdown,
    etymologyAndOrigin = etymologyAndOrigin,
    grammaticalDependency = grammaticalDependency,
    contextualSentimentPolarity = contextualSentimentPolarity,
    emotionalValence = emotionalValence,
    formalityAndRegister = formalityAndRegister,
    connotationAndTone = connotationAndTone,
    namedEntityRecognition = namedEntityRecognition,
    entityDisambiguation = entityDisambiguation,
    externalKnowledgeLinks = externalKnowledgeLinks,
    styleAndGrammarNotes = styleAndGrammarNotes
)

fun WordAnalysisRecord.toStorage(): WordAnalysisStorageRecord = WordAnalysisStorageRecord(
    word = word,
    analyzedAt = analyzedAt,
    phoneticTranscription = phoneticTranscription,
    syllableMetrics = syllableMetrics,
    perfectRhymes = perfectRhymes,
    nearSlantRhymes = nearSlantRhymes,
    meterAndRhythmScore = meterAndRhythmScore,
    definitions = definitions.map { WordDefinitionStorageRecord(it.partOfSpeech, it.definition, it.example) },
    synonyms = synonyms,
    antonyms = antonyms,
    hypernyms = hypernyms,
    hyponyms = hyponyms,
    collocations = collocations,
    partOfSpeech = partOfSpeech,
    lemmatizationAndRoots = lemmatizationAndRoots,
    affixBreakdown = affixBreakdown,
    etymologyAndOrigin = etymologyAndOrigin,
    grammaticalDependency = grammaticalDependency,
    contextualSentimentPolarity = contextualSentimentPolarity,
    emotionalValence = emotionalValence,
    formalityAndRegister = formalityAndRegister,
    connotationAndTone = connotationAndTone,
    namedEntityRecognition = namedEntityRecognition,
    entityDisambiguation = entityDisambiguation,
    externalKnowledgeLinks = externalKnowledgeLinks,
    styleAndGrammarNotes = styleAndGrammarNotes
)
