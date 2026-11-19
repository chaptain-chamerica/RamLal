package com.likkho.app.storage.backup

import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.DiaryFileCodec
import com.likkho.app.storage.DiaryRepository
import com.likkho.app.storage.EncryptedMediaRepository
import com.likkho.app.storage.SafeStorageWriter
import com.likkho.app.storage.model.BackupContents
import com.likkho.app.storage.model.BackupItemHash
import com.likkho.app.storage.model.BackupManifest
import com.likkho.app.storage.model.BackupResult
import com.likkho.app.storage.model.DiaryRecord
import com.likkho.app.storage.model.RestoreResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Backup and Share engine for custom binary .lkb and .likkho containers per user specification:
 * - Custom format: magic bytes ("LKB1" backup, "LKS1" share), version. No standard zip/json/txt.
 * - Streaming v2 uses Tink AesGcmHkdfStreaming (64 KB chunks) + disk staging so 200 MB+ vaults
 *   stream in constant ~few MB RAM without holding ByteArray lists in heap.
 * - Always prompt passphrase (min 8 chars) -> PBKDF2 (>=600,000 iterations, random salt) -> AES-256.
 * - Header bound strictly into AEAD AAD.
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

    private fun computeStreamSha256AndSize(stream: java.io.InputStream): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var totalBytes = 0L
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
            totalBytes += read
        }
        return CryptoUtils.toHex(md.digest()) to totalBytes
    }

    private fun newStageDir(prefix: String): File {
        return File(tempDir, "${prefix}_${System.currentTimeMillis()}_${(1..10000).random()}").apply {
            mkdirs()
        }
    }

    private fun extractRecordMediaIds(record: DiaryRecord): Set<String> {
        val ids = mutableSetOf<String>()
        record.pfp?.sourceMediaId?.let { ids.add(it) }
        record.background?.mediaId?.let { ids.add(it) }
        record.images.forEach { ids.add(it.mediaId) }
        record.audios.forEach { ids.add(it.mediaId) }
        record.videos.forEach { ids.add(it.mediaId) }
        return ids
    }

    // =========================================================================
    // BACKUP (.lkb) OPERATIONS
    // =========================================================================

    /**
     * Creates an encrypted .lkb backup container by streaming diaries one-by-one and
     * streaming media file-to-file (constant memory regardless of vault size).
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

        val tmpStale = File(parentDir, "${targetFile.name}.tmp")
        if (tmpStale.exists()) tmpStale.delete()

        var diaryCount = 0
        var mediaCount = 0
        var failedMediaCount = 0
        var estimatedBytes = 4096L

        // Pass 1: Compute per-item hashes & sizes by streaming each item individually
        val summaries = diaryRepo.listDiaries(vaultDek)
        val validDiaryIds = mutableListOf<String>()
        val itemHashes = mutableListOf<BackupItemHash>()

        for (sum in summaries) {
            try {
                val decoded = diaryRepo.loadDiary(sum.id, vaultDek)
                val diaryJson = SecureContainerCodec.encodeDiaryRecordBytes(decoded.record)
                itemHashes.add(BackupItemHash("diaries/${decoded.record.id}", CryptoUtils.sha256Hex(diaryJson), diaryJson.size.toLong()))
                validDiaryIds.add(sum.id)
                estimatedBytes += diaryJson.size + 64
                diaryCount++
            } catch (_: Exception) {}
        }

        val referencedMediaIds = diaryRepo.collectAllReferencedMediaIds(vaultDek)
        val validMediaMeta = mutableListOf<Pair<String, Long>>()

        for (mId in referencedMediaIds) {
            if (mediaRepo.exists(mId)) {
                try {
                    val (sha256, byteSize) = mediaRepo.openDecryptedStream(mId, vaultDek).use { stream ->
                        computeStreamSha256AndSize(stream)
                    }
                    validMediaMeta.add(mId to byteSize)
                    itemHashes.add(BackupItemHash("media/$mId", sha256, byteSize))
                    estimatedBytes += byteSize + 1024
                    mediaCount++
                } catch (_: Exception) {
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

        // Pass 2: Stream encrypted container directly to .tmp + streaming read-back verification + atomic rename
        SafeStorageWriter.safeWriteStreaming(
            targetFile = targetFile,
            estimatedRequiredBytes = estimatedBytes,
            verifier = { writtenFile ->
                SecureContainerCodec.verifyBackupContainerStreaming(writtenFile, passphrase)
            }
        ) { fos ->
            SecureContainerCodec.encodeBackupContainerStreaming(
                outputStream = fos,
                manifest = manifest,
                diaryIds = validDiaryIds,
                diaryLoader = { id -> diaryRepo.loadDiary(id, vaultDek).record },
                mediaItems = validMediaMeta,
                mediaOpener = { id -> mediaRepo.openDecryptedStream(id, vaultDek) },
                passphrase = passphrase,
                iterations = iterations
            )
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
     * Inspects backup contents by streaming decryption without holding media bytes in RAM.
     */
    fun readBackupContents(backupFile: File, passphrase: CharArray): BackupContents {
        if (!backupFile.exists()) throw CannotOpenFileException()
        return SecureContainerCodec.inspectBackupManifestStreaming(backupFile, passphrase)
    }

    /**
     * Restores an .lkb backup into the active vault by decrypting into a disk staging folder,
     * verifying all AEAD tags and SHA-256 hashes during streaming, then importing with conflict merge & snapshot rollback.
     */
    fun restoreBackup(
        backupFile: File,
        passphrase: CharArray,
        diaryRepo: DiaryRepository,
        mediaRepo: EncryptedMediaRepository,
        vaultDek: ByteArray
    ): RestoreResult {
        if (!backupFile.exists()) throw CannotOpenFileException()
        val stagingDir = newStageDir("restore_stage")
        val snapshotDir = newStageDir("vault_snapshot")

        var restoredDiaries = 0
        var conflictCopies = 0
        var importedMedia = 0
        var skippedMediaCount = 0

        try {
            // 1. Decrypt and verify hashes into disk staging folder before touching vault
            val staged = SecureContainerCodec.decodeBackupContainerToStaging(
                backupFile = backupFile,
                passphrase = passphrase,
                stagingDir = stagingDir
            )

            // 2. Snapshot active vault for rollback on error
            val vaultDir = diaryRepo.vaultDir
            if (vaultDir.exists()) {
                vaultDir.copyRecursively(snapshotDir, overwrite = true)
            }

            // 3. Apply merge rule for diaries one-by-one from staged files
            for (stagedDiary in staged.diaries) {
                val restoredRecord = SecureContainerCodec.parseDiaryRecordJson(stagedDiary.file)
                val existingFile = File(diaryRepo.diariesDir, "${restoredRecord.id}.lkd")
                if (existingFile.exists()) {
                    try {
                        val existingDecoded = diaryRepo.loadDiary(restoredRecord.id, vaultDek)
                        val existingRecord = existingDecoded.record

                        if (restoredRecord == existingRecord) {
                            // Identical record already present locally
                        } else if (restoredRecord.updatedAt > existingRecord.updatedAt) {
                            val copyId = "${existingRecord.id}_copy_${existingRecord.updatedAt}"
                            val localCopy = existingRecord.copy(
                                id = copyId,
                                heading = "${existingRecord.heading} (Local Copy)"
                            )
                            diaryRepo.saveDiary(localCopy, vaultDek)
                            diaryRepo.saveDiary(restoredRecord, vaultDek)
                            conflictCopies++
                        } else {
                            val copyId = "${restoredRecord.id}_copy_${restoredRecord.updatedAt}"
                            val restoredCopy = restoredRecord.copy(
                                id = copyId,
                                heading = "${restoredRecord.heading} (Restored Copy)"
                            )
                            diaryRepo.saveDiary(restoredCopy, vaultDek)
                            conflictCopies++
                        }
                    } catch (_: Exception) {
                        diaryRepo.saveDiary(restoredRecord, vaultDek)
                        restoredDiaries++
                    }
                } else {
                    diaryRepo.saveDiary(restoredRecord, vaultDek)
                    restoredDiaries++
                }
                stagedDiary.file.delete()
            }

            // 4. Import media attachments file-to-file via streaming AEAD
            for ((mId, stagedFile) in staged.mediaFiles) {
                if (mediaRepo.exists(mId)) {
                    skippedMediaCount++
                } else {
                    val fileLen = stagedFile.length()
                    FileInputStream(stagedFile).use { fis ->
                        mediaRepo.importMedia(mId, fis, vaultDek, estimatedSizeBytes = fileLen)
                    }
                    importedMedia++
                }
                stagedFile.delete()
            }

            // 5. Self-heal index
            diaryRepo.indexManager.rebuildFromDiariesVerified(diaryRepo.diariesDir, vaultDek)
            snapshotDir.deleteRecursively()

            return RestoreResult(
                restoredDiaries = restoredDiaries,
                conflictCopies = conflictCopies,
                importedMedia = importedMedia,
                skippedMediaCount = skippedMediaCount
            )
        } catch (e: Exception) {
            if (snapshotDir.exists() && snapshotDir.listFiles()?.isNotEmpty() == true) {
                diaryRepo.vaultDir.deleteRecursively()
                diaryRepo.vaultDir.mkdirs()
                snapshotDir.copyRecursively(diaryRepo.vaultDir, overwrite = true)
            }
            throw e
        } finally {
            if (snapshotDir.exists()) snapshotDir.deleteRecursively()
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }

    /**
     * Deletes a diary from the .lkb backup container by streaming via disk staging and rewriting atomically.
     */
    fun deleteDiaryFromBackup(
        backupFile: File,
        passphrase: CharArray,
        diaryIdToDelete: String
    ): BackupResult {
        if (!backupFile.exists()) throw CannotOpenFileException()
        val stagingDir = newStageDir("backup_edit_stage")

        try {
            val staged = SecureContainerCodec.decodeBackupContainerToStaging(
                backupFile = backupFile,
                passphrase = passphrase,
                stagingDir = stagingDir
            )

            var foundTarget = false
            val remainingDiaryFiles = mutableListOf<StagedDiaryEntry>()
            val remainingMediaIds = mutableSetOf<String>()

            for (entry in staged.diaries) {
                if (entry.id == diaryIdToDelete) {
                    foundTarget = true
                    entry.file.delete()
                } else {
                    remainingDiaryFiles.add(entry)
                    val rec = SecureContainerCodec.parseDiaryRecordJson(entry.file)
                    remainingMediaIds.addAll(extractRecordMediaIds(rec))
                }
            }

            if (!foundTarget) {
                throw IOException("Diary $diaryIdToDelete not found in backup")
            }

            val remainingMediaFiles = mutableListOf<Pair<String, File>>()
            var estimatedBytes = 4096L
            for ((mId, mFile) in staged.mediaFiles) {
                if (remainingMediaIds.contains(mId)) {
                    remainingMediaFiles.add(mId to mFile)
                    estimatedBytes += mFile.length() + 1024
                } else {
                    mFile.delete()
                }
            }

            val updatedManifest = staged.manifest.copy(
                diaryCount = remainingDiaryFiles.size,
                mediaCount = remainingMediaFiles.size,
                items = staged.manifest.items.filterNot {
                    it.path == "diaries/$diaryIdToDelete" ||
                        (it.path.startsWith("media/") && !remainingMediaIds.contains(it.path.removePrefix("media/")))
                }
            )

            SafeStorageWriter.safeWriteStreaming(
                targetFile = backupFile,
                estimatedRequiredBytes = estimatedBytes,
                verifier = { writtenFile ->
                    SecureContainerCodec.verifyBackupContainerStreaming(writtenFile, passphrase)
                }
            ) { fos ->
                SecureContainerCodec.encodeStagedBackupStreaming(
                    outputStream = fos,
                    manifest = updatedManifest,
                    diaryFiles = remainingDiaryFiles,
                    mediaFiles = remainingMediaFiles,
                    passphrase = passphrase,
                    iterations = defaultIterations
                )
            }

            return BackupResult(
                success = true,
                diaryCount = remainingDiaryFiles.size,
                mediaCount = remainingMediaFiles.size,
                failedMediaCount = 0,
                backupFile = backupFile
            )
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }

    // =========================================================================
    // SHARE SINGLE DIARY (.likkho) OPERATIONS
    // =========================================================================

    /**
     * Creates an encrypted .likkho file containing a single diary and its media attachments streamed file-to-file.
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

        val mediaIds = extractRecordMediaIds(record)
        val validMediaMeta = mutableListOf<Pair<String, Long>>()
        var estimatedBytes = 4096L

        for (mId in mediaIds) {
            if (mediaRepo.exists(mId)) {
                try {
                    val (_, byteSize) = mediaRepo.openDecryptedStream(mId, vaultDek).use { stream ->
                        computeStreamSha256AndSize(stream)
                    }
                    validMediaMeta.add(mId to byteSize)
                    estimatedBytes += byteSize + 1024
                } catch (_: Exception) {}
            }
        }

        SafeStorageWriter.safeWriteStreaming(
            targetFile = targetFile,
            estimatedRequiredBytes = estimatedBytes,
            verifier = { writtenFile ->
                SecureContainerCodec.verifyShareContainerStreaming(writtenFile, passphrase)
            }
        ) { fos ->
            SecureContainerCodec.encodeShareContainerStreaming(
                outputStream = fos,
                diary = record,
                mediaItems = validMediaMeta,
                mediaOpener = { id -> mediaRepo.openDecryptedStream(id, vaultDek) },
                passphrase = passphrase,
                iterations = iterations
            )
        }

        return targetFile
    }

    /**
     * Restores an encrypted .likkho file into the active vault via disk staging and streaming import.
     */
    fun restoreShareDiaryFile(
        shareFile: File,
        passphrase: CharArray,
        diaryRepo: DiaryRepository,
        mediaRepo: EncryptedMediaRepository,
        vaultDek: ByteArray
    ): RestoreResult {
        if (!shareFile.exists()) throw CannotOpenFileException()
        val stagingDir = newStageDir("share_stage")
        val snapshotDir = newStageDir("share_snapshot")

        var restoredDiaries = 0
        var conflictCopies = 0
        var importedMedia = 0
        var skippedMediaCount = 0

        try {
            val staged = SecureContainerCodec.decodeShareContainerToStaging(
                shareFile = shareFile,
                passphrase = passphrase,
                stagingDir = stagingDir
            )

            val vaultDir = diaryRepo.vaultDir
            if (vaultDir.exists()) {
                vaultDir.copyRecursively(snapshotDir, overwrite = true)
            }

            val restoredRecord = staged.diary
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
                } catch (_: Exception) {
                    diaryRepo.saveDiary(restoredRecord, vaultDek)
                    restoredDiaries++
                }
            } else {
                diaryRepo.saveDiary(restoredRecord, vaultDek)
                restoredDiaries++
            }

            for ((mId, stagedFile) in staged.mediaFiles) {
                if (mediaRepo.exists(mId)) {
                    skippedMediaCount++
                } else {
                    val fileLen = stagedFile.length()
                    FileInputStream(stagedFile).use { fis ->
                        mediaRepo.importMedia(mId, fis, vaultDek, estimatedSizeBytes = fileLen)
                    }
                    importedMedia++
                }
                stagedFile.delete()
            }

            diaryRepo.indexManager.rebuildFromDiariesVerified(diaryRepo.diariesDir, vaultDek)
            snapshotDir.deleteRecursively()

            return RestoreResult(
                restoredDiaries = restoredDiaries,
                conflictCopies = conflictCopies,
                importedMedia = importedMedia,
                skippedMediaCount = skippedMediaCount
            )
        } catch (e: Exception) {
            if (snapshotDir.exists() && snapshotDir.listFiles()?.isNotEmpty() == true) {
                diaryRepo.vaultDir.deleteRecursively()
                diaryRepo.vaultDir.mkdirs()
                snapshotDir.copyRecursively(diaryRepo.vaultDir, overwrite = true)
            }
            throw e
        } finally {
            if (snapshotDir.exists()) snapshotDir.deleteRecursively()
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }
}
