package com.likkho.app.storage

import com.likkho.app.storage.model.DiaryRecord
import com.likkho.app.storage.model.IndexEntryRecord
import java.io.File
import java.io.IOException
import javax.crypto.AEADBadTagException

data class RecoveryReport(
    val restoredFromBackup: List<String> = emptyList(),
    val quarantinedFiles: List<String> = emptyList(),
    val cleanedTempFiles: Int = 0,
    val indexRebuilt: Boolean = false
)

class CorruptedDiaryException(
    val diaryId: String,
    val quarantinedPath: String?,
    cause: Throwable
) : IOException("Diary $diaryId is corrupted and was moved to quarantine", cause)

class ReadOnlyDiaryException(val diaryId: String, val version: Int) :
    IOException("Diary $diaryId has schemaVersion $version which is newer than supported version (read-only)")

/**
 * Manages diary file lifecycle, safe writes, index synchronization, and startup recovery
 * per STORAGE_SPEC Sections 3, 4, and 5.
 */
class DiaryRepository(
    val vaultId: VaultId,
    val vaultDir: File
) {
    val diariesDir = File(vaultDir, "diaries")
    val quarantineDir = File(vaultDir, "quarantine")
    val indexManager = VaultIndexManager(vaultId, vaultDir)

    fun ensureDirs() {
        diariesDir.mkdirs()
        quarantineDir.mkdirs()
    }

    /**
     * Executes recovery scan on vault unlock per STORAGE_SPEC Section 5:
     * - Cleans up dangling .tmp files from crashed writes.
     * - Recovers from .bak if main .lkd is missing or corrupt.
     * - Moves tampered / corrupt .lkd files to quarantine/ (never deletes).
     * - Self-heals the encrypted index if missing or corrupt.
     */
    @Synchronized
    fun scanAndRecover(dek: ByteArray): RecoveryReport {
        diariesDir.mkdirs()
        quarantineDir.mkdirs()

        val files = diariesDir.listFiles() ?: emptyArray()
        var cleanedTmp = 0
        val restoredBaks = mutableListOf<String>()
        val quarantined = mutableListOf<String>()

        // 1. Clean dangling .tmp files
        for (f in files) {
            if (f.name.endsWith(".tmp")) {
                if (f.delete()) cleanedTmp++
            }
        }

        // 2. Check and handle .bak files
        val bakFiles = diariesDir.listFiles { f -> f.name.endsWith(".bak") } ?: emptyArray()
        for (bak in bakFiles) {
            val mainFileName = bak.name.removeSuffix(".bak")
            val mainFile = File(diariesDir, mainFileName)
            val diaryId = mainFileName.removeSuffix(".lkd")

            var mainHealthy = false
            if (mainFile.exists() && mainFile.length() > DiaryFileCodec.HEADER_SIZE) {
                try {
                    DiaryFileCodec.decode(mainFile.readBytes(), dek, diaryId)
                    mainHealthy = true
                } catch (e: Exception) {
                    mainHealthy = false
                }
            }

            if (!mainHealthy) {
                // Restore from .bak
                if (mainFile.exists()) mainFile.delete()
                bak.renameTo(mainFile)
                restoredBaks.add(diaryId)
            } else {
                // Main file is healthy, discard .bak
                bak.delete()
            }
        }

        // 3. Scan .lkd files for corruption / tampering
        val lkdFiles = diariesDir.listFiles { f -> f.name.endsWith(".lkd") } ?: emptyArray()
        for (lkd in lkdFiles) {
            val diaryId = lkd.name.removeSuffix(".lkd")
            try {
                DiaryFileCodec.decode(lkd.readBytes(), dek, diaryId)
            } catch (e: AEADBadTagException) {
                // GCM authentication failed (tampered / wrong key / swapped) -> Quarantine
                val qFile = File(quarantineDir, "${diaryId}_${System.currentTimeMillis()}.lkd")
                lkd.renameTo(qFile)
                quarantined.add(qFile.absolutePath)
            } catch (e: Exception) {
                // Corrupted structure -> Quarantine
                val qFile = File(quarantineDir, "${diaryId}_${System.currentTimeMillis()}.lkd")
                lkd.renameTo(qFile)
                quarantined.add(qFile.absolutePath)
            }
        }

        // 4. Verify index or self-heal
        var indexRebuilt = false
        val index = indexManager.readIndex(dek)
        if (index == null) {
            indexManager.rebuildFromDiaries(diariesDir, dek)
            indexRebuilt = true
        }

        return RecoveryReport(
            restoredFromBackup = restoredBaks,
            quarantinedFiles = quarantined,
            cleanedTempFiles = cleanedTmp,
            indexRebuilt = indexRebuilt
        )
    }

    /**
     * Safely saves a diary record to disk with read-back verification and updates the index.
     */
    @Synchronized
    fun saveDiary(record: DiaryRecord, dek: ByteArray) {
        val targetFile = File(diariesDir, "${record.id}.lkd")

        // Check if existing file has a newer schemaVersion than supported (read-only rule)
        if (targetFile.exists()) {
            try {
                val existing = DiaryFileCodec.decode(targetFile.readBytes(), dek, record.id)
                if (existing.isReadOnly) {
                    throw ReadOnlyDiaryException(record.id, existing.schemaVersion)
                }
            } catch (e: ReadOnlyDiaryException) {
                throw e
            } catch (e: Exception) {
                // Corrupt file will be replaced by safe write
            }
        }

        val encodedBytes = DiaryFileCodec.encode(record, dek)

        // Safe write: free space check -> .tmp -> fsync -> verify read-back -> .bak -> atomic rename
        SafeStorageWriter.safeWrite(targetFile, encodedBytes) { tmpFile ->
            // Verification lambda: guarantee file can be decrypted before committing
            val tmpBytes = tmpFile.readBytes()
            DiaryFileCodec.decode(tmpBytes, dek, record.id)
        }

        // Update encrypted index
        val entry = IndexEntryRecord(
            id = record.id,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
            pinned = record.pinned,
            flags = (if (record.diaryLock != null) 1 else 0) or
                    (if (record.audios.isNotEmpty()) 2 else 0) or
                    (if (record.images.isNotEmpty()) 4 else 0),
            plainPreview = record.plainPreview,
            heading = record.heading
        )
        indexManager.upsertEntry(entry, dek)
    }

    /**
     * Loads and decrypts a diary file.
     * If corrupted / tampered, moves to quarantine/ and throws [CorruptedDiaryException].
     */
    @Synchronized
    fun loadDiary(id: String, dek: ByteArray): DecodedDiary {
        val file = File(diariesDir, "$id.lkd")
        if (!file.exists()) {
            throw IOException("Diary file not found: $id.lkd")
        }

        val rawBytes = file.readBytes()
        return try {
            DiaryFileCodec.decode(rawBytes, dek, id)
        } catch (e: Exception) {
            // Quarantine corrupted file
            quarantineDir.mkdirs()
            val qFile = File(quarantineDir, "${id}_${System.currentTimeMillis()}.lkd")
            file.renameTo(qFile)
            indexManager.removeEntry(id, dek)
            throw CorruptedDiaryException(id, qFile.absolutePath, e)
        }
    }

    /**
     * Collects all media IDs referenced across healthy diaries in this vault,
     * optionally excluding a specific diary ID (useful during diary deletion).
     */
    fun collectAllReferencedMediaIds(dek: ByteArray, excludeDiaryId: String? = null): Set<String> {
        val mediaIds = mutableSetOf<String>()
        val lkdFiles = diariesDir.listFiles { f -> f.name.endsWith(".lkd") } ?: emptyArray()
        for (f in lkdFiles) {
            val diaryId = f.name.removeSuffix(".lkd")
            if (diaryId == excludeDiaryId) continue
            try {
                val decoded = DiaryFileCodec.decode(f.readBytes(), dek, diaryId)
                decoded.record.pfp?.sourceMediaId?.let { mediaIds.add(it) }
                decoded.record.background?.mediaId?.let { mediaIds.add(it) }
                decoded.record.images.forEach { mediaIds.add(it.mediaId) }
                decoded.record.audios.forEach { mediaIds.add(it.mediaId) }
            } catch (e: Exception) {
                // Ignore unreadable/corrupt files during collection
            }
        }
        return mediaIds
    }

    /**
     * Deletes a diary file, cleans up its unreferenced media, and removes it from the index.
     * Per STORAGE_SPEC Section 5 & 6: "Delete: diary file + uski unreferenced media hatao + index update, ek transaction ki tarah."
     */
    @Synchronized
    fun deleteDiary(id: String, dek: ByteArray, mediaRepo: EncryptedMediaRepository? = null) {
        val file = File(diariesDir, "$id.lkd")
        val bakFile = File(diariesDir, "$id.lkd.bak")
        val tmpFile = File(diariesDir, "$id.lkd.tmp")

        if (mediaRepo != null && file.exists()) {
            try {
                val decoded = DiaryFileCodec.decode(file.readBytes(), dek, id)
                val diaryMediaIds = mutableSetOf<String>()
                decoded.record.pfp?.sourceMediaId?.let { diaryMediaIds.add(it) }
                decoded.record.background?.mediaId?.let { diaryMediaIds.add(it) }
                decoded.record.images.forEach { diaryMediaIds.add(it.mediaId) }
                decoded.record.audios.forEach { diaryMediaIds.add(it.mediaId) }

                if (diaryMediaIds.isNotEmpty()) {
                    val remainingReferenced = collectAllReferencedMediaIds(dek, excludeDiaryId = id)
                    mediaRepo.deleteUnreferencedMediaForDiary(diaryMediaIds, remainingReferenced)
                }
            } catch (e: Exception) {
                // Proceed with file deletion even if record decode fails
            }
        }

        if (file.exists()) file.delete()
        if (bakFile.exists()) bakFile.delete()
        if (tmpFile.exists()) tmpFile.delete()

        indexManager.removeEntry(id, dek)
    }

    /**
     * Lists all diary summaries from the encrypted index.
     * Self-heals if the index is missing or corrupted.
     */
    @Synchronized
    fun listDiaries(dek: ByteArray): List<IndexEntryRecord> {
        val index = indexManager.readIndex(dek) ?: indexManager.rebuildFromDiaries(diariesDir, dek)
        return index.entries.sortedWith(
            compareByDescending<IndexEntryRecord> { it.pinned }.thenByDescending { it.createdAt }
        )
    }

    @Synchronized
    fun loadIndex(dek: ByteArray) = indexManager.readIndex(dek) ?: indexManager.rebuildFromDiaries(diariesDir, dek)

    @Synchronized
    fun loadAllDiaries(dek: ByteArray): List<DiaryRecord> {
        val entries = listDiaries(dek)
        return entries.mapNotNull { entry ->
            try {
                loadDiary(entry.id, dek).record
            } catch (e: Exception) {
                null
            }
        }
    }
}
