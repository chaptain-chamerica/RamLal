package com.likkho.app.storage.backup

import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.DiaryFileCodec
import com.likkho.app.storage.DiaryRepository
import com.likkho.app.storage.EncryptedMediaRepository
import com.likkho.app.storage.SafeStorageWriter
import com.likkho.app.storage.model.BackupContents
import com.likkho.app.storage.model.BackupDiarySummary
import com.likkho.app.storage.model.BackupItemHash
import com.likkho.app.storage.model.BackupManifest
import com.likkho.app.storage.model.BackupResult
import com.likkho.app.storage.model.DiaryRecord
import com.likkho.app.storage.model.RestoreResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Backup and Share engine for custom binary .lkb and .likkho containers per user specification:
 * - Custom format: magic bytes ("LKB1" backup, "LKS1" share), version. No standard zip/json/txt.
 * - Always prompt passphrase (min 8 chars) -> PBKDF2 (>=600,000 iterations, random salt) -> AES-256-GCM.
 * - Header (40B) bound strictly into GCM AAD.
 * - Generic "Cannot open this file" error on invalid passphrase, tampering, or foreign app.
 * - Zero hard-coded keys in app. Zeroizing of key and passphrase memory.
 * - Pre-restore snapshot for atomic rollback, conflict merge rules (no data lost).
 */
class BackupEngine(
    val tempDir: File,
    val defaultIterations: Int = SecureContainerCodec.DEFAULT_ITERATIONS,
    val appVersion: String = "1.0.0"
) {
    init {
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
    }

    // =========================================================================
    // BACKUP (.lkb) OPERATIONS
    // =========================================================================

    /**
     * Creates an encrypted .lkb backup container using custom binary format.
     */
    fun createBackup(
        targetFile: File,
        passphrase: CharArray,
        diaryRepo: DiaryRepository,
        mediaRepo: EncryptedMediaRepository,
        vaultDek: ByteArray,
        iterations: Int = defaultIterations
    ): BackupResult {
        SecureContainerCodec.validatePassphrase(passphrase)
        val parentDir = targetFile.parentFile ?: tempDir
        if (!parentDir.exists()) parentDir.mkdirs()

        val tmpFile = File(parentDir, "${targetFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        var diaryCount = 0
        var mediaCount = 0
        var failedMediaCount = 0

        // 1. Collect diaries and media from vault
        val summaries = diaryRepo.listDiaries(vaultDek)
        val diaryRecords = mutableListOf<DiaryRecord>()
        for (sum in summaries) {
            try {
                val decoded = diaryRepo.loadDiary(sum.id, vaultDek)
                diaryRecords.add(decoded.record)
                diaryCount++
            } catch (ignored: Exception) {}
        }

        val referencedMediaIds = diaryRepo.collectAllReferencedMediaIds(vaultDek)
        val mediaPayloads = mutableListOf<Pair<String, ByteArray>>()
        val itemHashes = mutableListOf<BackupItemHash>()

        for (diary in diaryRecords) {
            val diaryJson = Json.encodeToString(diary).toByteArray(Charsets.UTF_8)
            itemHashes.add(BackupItemHash("diaries/${diary.id}", CryptoUtils.sha256Hex(diaryJson), diaryJson.size.toLong()))
        }

        for (mId in referencedMediaIds) {
            if (mediaRepo.exists(mId)) {
                try {
                    val bytes = mediaRepo.readDecryptedBytes(mId, vaultDek)
                    mediaPayloads.add(mId to bytes)
                    itemHashes.add(BackupItemHash("media/$mId", CryptoUtils.sha256Hex(bytes), bytes.size.toLong()))
                    mediaCount++
                } catch (e: Exception) {
                    failedMediaCount++
                }
            } else {
                failedMediaCount++
            }
        }

        val manifest = BackupManifest(
            schemaVersion = DiaryFileCodec.CURRENT_SCHEMA_VERSION,
            appVersion = appVersion,
            createdAt = System.currentTimeMillis(),
            diaryCount = diaryCount,
            mediaCount = mediaCount,
            items = itemHashes
        )

        // 2. Encode to custom binary container
        val containerBytes = SecureContainerCodec.encodeBackupContainer(
            manifest = manifest,
            diaries = diaryRecords,
            mediaList = mediaPayloads,
            passphrase = passphrase,
            iterations = iterations
        )

        // 3. Safe write with read-back verification
        SafeStorageWriter.safeWrite(targetFile, containerBytes) { writtenFile ->
            SecureContainerCodec.decodeBackupContainer(writtenFile.readBytes(), passphrase)
        }

        return BackupResult(
            success = true,
            diaryCount = diaryCount,
            mediaCount = mediaCount,
            failedMediaCount = failedMediaCount,
            backupFile = targetFile
        )
    }

    /**
     * Inspects backup contents in memory without modifying the vault.
     */
    fun readBackupContents(backupFile: File, passphrase: CharArray): BackupContents {
        if (!backupFile.exists()) throw CannotOpenFileException()
        val bundle = SecureContainerCodec.decodeBackupContainer(backupFile.readBytes(), passphrase)

        val summaries = bundle.diaries.map {
            BackupDiarySummary(
                id = it.id,
                heading = it.heading,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
                plainPreview = it.plainPreview
            )
        }.sortedByDescending { it.createdAt }

        return BackupContents(
            manifest = bundle.manifest,
            diaries = summaries,
            mediaIds = bundle.mediaList.map { it.first }
        )
    }

    /**
     * Restores an .lkb backup into the active vault with conflict merge rule and snapshot rollback.
     */
    fun restoreBackup(
        backupFile: File,
        passphrase: CharArray,
        diaryRepo: DiaryRepository,
        mediaRepo: EncryptedMediaRepository,
        vaultDek: ByteArray
    ): RestoreResult {
        if (!backupFile.exists()) throw CannotOpenFileException()
        // Authenticate and decode backup before touching vault
        val bundle = SecureContainerCodec.decodeBackupContainer(backupFile.readBytes(), passphrase)

        val snapshotDir = File(tempDir, "vault_snapshot_${System.currentTimeMillis()}_${(1..1000).random()}")
        snapshotDir.mkdirs()

        var restoredDiaries = 0
        var conflictCopies = 0
        var importedMedia = 0
        var skippedMediaCount = 0

        try {
            // Snapshot active vault for instant rollback on error
            val vaultDir = diaryRepo.vaultDir
            if (vaultDir.exists()) {
                vaultDir.copyRecursively(snapshotDir, overwrite = true)
            }

            // Apply merge rule for diaries: same ID -> newer updatedAt wins, older kept as copy
            for (restoredRecord in bundle.diaries) {
                val existingFile = File(diaryRepo.diariesDir, "${restoredRecord.id}.lkd")
                if (existingFile.exists()) {
                    try {
                        val existingDecoded = diaryRepo.loadDiary(restoredRecord.id, vaultDek)
                        val existingRecord = existingDecoded.record

                        if (restoredRecord.updatedAt > existingRecord.updatedAt) {
                            val copyId = "${existingRecord.id}_copy_${existingRecord.updatedAt}"
                            val localCopy = existingRecord.copy(
                                id = copyId,
                                heading = "${existingRecord.heading} (Local Copy)"
                            )
                            diaryRepo.saveDiary(localCopy, vaultDek)
                            diaryRepo.saveDiary(restoredRecord, vaultDek)
                        } else {
                            val copyId = "${restoredRecord.id}_copy_${restoredRecord.updatedAt}"
                            val restoredCopy = restoredRecord.copy(
                                id = copyId,
                                heading = "${restoredRecord.heading} (Restored Copy)"
                            )
                            diaryRepo.saveDiary(restoredCopy, vaultDek)
                        }
                        conflictCopies++
                    } catch (e: Exception) {
                        diaryRepo.saveDiary(restoredRecord, vaultDek)
                        restoredDiaries++
                    }
                } else {
                    diaryRepo.saveDiary(restoredRecord, vaultDek)
                    restoredDiaries++
                }
            }

            // Import media attachments
            for ((mId, mData) in bundle.mediaList) {
                if (mediaRepo.exists(mId)) {
                    skippedMediaCount++
                } else {
                    mediaRepo.importMediaBytes(mId, mData, vaultDek)
                    importedMedia++
                }
            }

            // Self-heal index
            diaryRepo.indexManager.rebuildFromDiaries(diaryRepo.diariesDir, vaultDek)
            snapshotDir.deleteRecursively()

            return RestoreResult(
                restoredDiaries = restoredDiaries,
                conflictCopies = conflictCopies,
                importedMedia = importedMedia,
                skippedMediaCount = skippedMediaCount
            )
        } catch (e: Exception) {
            if (snapshotDir.exists() && snapshotDir.listFiles()?.isNotEmpty() == true) {
                snapshotDir.copyRecursively(diaryRepo.vaultDir, overwrite = true)
            }
            throw e
        } finally {
            if (snapshotDir.exists()) snapshotDir.deleteRecursively()
        }
    }

    /**
     * Deletes a diary from the .lkb backup container and replaces the file atomically.
     */
    fun deleteDiaryFromBackup(
        backupFile: File,
        passphrase: CharArray,
        diaryIdToDelete: String
    ): BackupResult {
        if (!backupFile.exists()) throw CannotOpenFileException()
        val bundle = SecureContainerCodec.decodeBackupContainer(backupFile.readBytes(), passphrase)

        val targetDiary = bundle.diaries.find { it.id == diaryIdToDelete }
            ?: throw IOException("Diary $diaryIdToDelete not found in backup")

        val deletedMediaIds = mutableSetOf<String>()
        targetDiary.pfp?.sourceMediaId?.let { deletedMediaIds.add(it) }
        targetDiary.background?.mediaId?.let { deletedMediaIds.add(it) }
        targetDiary.images.forEach { deletedMediaIds.add(it.mediaId) }
        targetDiary.audios.forEach { deletedMediaIds.add(it.mediaId) }

        val remainingDiaries = bundle.diaries.filter { it.id != diaryIdToDelete }
        val remainingMediaIds = mutableSetOf<String>()
        for (d in remainingDiaries) {
            d.pfp?.sourceMediaId?.let { remainingMediaIds.add(it) }
            d.background?.mediaId?.let { remainingMediaIds.add(it) }
            d.images.forEach { remainingMediaIds.add(it.mediaId) }
            d.audios.forEach { remainingMediaIds.add(it.mediaId) }
        }

        val remainingMediaList = bundle.mediaList.filter { (mId, _) ->
            remainingMediaIds.contains(mId)
        }

        val updatedManifest = bundle.manifest.copy(
            diaryCount = remainingDiaries.size,
            mediaCount = remainingMediaList.size,
            items = bundle.manifest.items.filterNot {
                it.path == "diaries/$diaryIdToDelete" || (it.path.startsWith("media/") && !remainingMediaIds.contains(it.path.removePrefix("media/")))
            }
        )

        val newBytes = SecureContainerCodec.encodeBackupContainer(
            manifest = updatedManifest,
            diaries = remainingDiaries,
            mediaList = remainingMediaList,
            passphrase = passphrase,
            iterations = defaultIterations
        )

        SafeStorageWriter.safeWrite(backupFile, newBytes) { writtenFile ->
            SecureContainerCodec.decodeBackupContainer(writtenFile.readBytes(), passphrase)
        }

        return BackupResult(
            success = true,
            diaryCount = remainingDiaries.size,
            mediaCount = remainingMediaList.size,
            failedMediaCount = 0,
            backupFile = backupFile
        )
    }

    // =========================================================================
    // SHARE SINGLE DIARY (.likkho) OPERATIONS
    // =========================================================================

    /**
     * Creates an encrypted .likkho file containing a single diary and all its media attachments.
     */
    fun createShareDiaryFile(
        targetFile: File,
        diaryId: String,
        passphrase: CharArray,
        diaryRepo: DiaryRepository,
        mediaRepo: EncryptedMediaRepository,
        vaultDek: ByteArray,
        iterations: Int = defaultIterations
    ): File {
        SecureContainerCodec.validatePassphrase(passphrase)
        val decoded = diaryRepo.loadDiary(diaryId, vaultDek)
        val record = decoded.record

        val mediaPayloads = mutableListOf<Pair<String, ByteArray>>()
        val mediaIds = mutableSetOf<String>()
        record.pfp?.sourceMediaId?.let { mediaIds.add(it) }
        record.background?.mediaId?.let { mediaIds.add(it) }
        record.images.forEach { mediaIds.add(it.mediaId) }
        record.audios.forEach { mediaIds.add(it.mediaId) }

        for (mId in mediaIds) {
            if (mediaRepo.exists(mId)) {
                try {
                    val bytes = mediaRepo.readDecryptedBytes(mId, vaultDek)
                    mediaPayloads.add(mId to bytes)
                } catch (ignored: Exception) {}
            }
        }

        val containerBytes = SecureContainerCodec.encodeShareContainer(
            diary = record,
            mediaList = mediaPayloads,
            passphrase = passphrase,
            iterations = iterations
        )

        SafeStorageWriter.safeWrite(targetFile, containerBytes) { writtenFile ->
            SecureContainerCodec.decodeShareContainer(writtenFile.readBytes(), passphrase)
        }

        return targetFile
    }

    /**
     * Restores an encrypted .likkho file into the active vault.
     */
    fun restoreShareDiaryFile(
        shareFile: File,
        passphrase: CharArray,
        diaryRepo: DiaryRepository,
        mediaRepo: EncryptedMediaRepository,
        vaultDek: ByteArray
    ): RestoreResult {
        if (!shareFile.exists()) throw CannotOpenFileException()
        val bundle = SecureContainerCodec.decodeShareContainer(shareFile.readBytes(), passphrase)

        val snapshotDir = File(tempDir, "share_snapshot_${System.currentTimeMillis()}_${(1..1000).random()}")
        snapshotDir.mkdirs()

        var restoredDiaries = 0
        var conflictCopies = 0
        var importedMedia = 0
        var skippedMediaCount = 0

        try {
            val vaultDir = diaryRepo.vaultDir
            if (vaultDir.exists()) {
                vaultDir.copyRecursively(snapshotDir, overwrite = true)
            }

            val restoredRecord = bundle.diary
            val existingFile = File(diaryRepo.diariesDir, "${restoredRecord.id}.lkd")
            if (existingFile.exists()) {
                try {
                    val existingDecoded = diaryRepo.loadDiary(restoredRecord.id, vaultDek)
                    val existingRecord = existingDecoded.record

                    if (restoredRecord.updatedAt > existingRecord.updatedAt) {
                        val copyId = "${existingRecord.id}_copy_${existingRecord.updatedAt}"
                        val localCopy = existingRecord.copy(
                            id = copyId,
                            heading = "${existingRecord.heading} (Local Copy)"
                        )
                        diaryRepo.saveDiary(localCopy, vaultDek)
                        diaryRepo.saveDiary(restoredRecord, vaultDek)
                    } else {
                        val copyId = "${restoredRecord.id}_copy_${restoredRecord.updatedAt}"
                        val restoredCopy = restoredRecord.copy(
                            id = copyId,
                            heading = "${restoredRecord.heading} (Restored Copy)"
                        )
                        diaryRepo.saveDiary(restoredCopy, vaultDek)
                    }
                    conflictCopies++
                } catch (e: Exception) {
                    diaryRepo.saveDiary(restoredRecord, vaultDek)
                    restoredDiaries++
                }
            } else {
                diaryRepo.saveDiary(restoredRecord, vaultDek)
                restoredDiaries++
            }

            for ((mId, mData) in bundle.mediaAttachments) {
                if (mediaRepo.exists(mId)) {
                    skippedMediaCount++
                } else {
                    mediaRepo.importMediaBytes(mId, mData, vaultDek)
                    importedMedia++
                }
            }

            diaryRepo.indexManager.rebuildFromDiaries(diaryRepo.diariesDir, vaultDek)
            snapshotDir.deleteRecursively()

            return RestoreResult(
                restoredDiaries = restoredDiaries,
                conflictCopies = conflictCopies,
                importedMedia = importedMedia,
                skippedMediaCount = skippedMediaCount
            )
        } catch (e: Exception) {
            if (snapshotDir.exists() && snapshotDir.listFiles()?.isNotEmpty() == true) {
                snapshotDir.copyRecursively(diaryRepo.vaultDir, overwrite = true)
            }
            throw e
        } finally {
            if (snapshotDir.exists()) snapshotDir.deleteRecursively()
        }
    }
}
