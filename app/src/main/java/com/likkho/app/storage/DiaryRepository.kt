package com.likkho.app.storage

import com.likkho.app.storage.model.DiaryRecord
import com.likkho.app.storage.model.IndexEntryRecord
import com.likkho.app.storage.model.VaultIndexRecord
import kotlinx.serialization.SerializationException
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException

data class RecoveryReport(
    val restoredFromBackup: List<String> = emptyList(),
    val quarantinedFiles: List<String> = emptyList(),
    val cleanedTempFiles: Int = 0,
    val indexRebuilt: Boolean = false,
    val ioErrors: List<String> = emptyList()
)

data class FailedDiaryEntry(
    val diaryId: String,
    val error: String,
    val quarantinedPath: String? = null
)

data class LoadDiariesResult(
    val diaries: List<DiaryRecord>,
    val failed: List<FailedDiaryEntry> = emptyList()
)

data class MediaReferenceScan(
    val referencedIds: Set<String>,
    val hasUndecodableOrQuarantined: Boolean
)

class CorruptedDiaryException(
    val diaryId: String,
    val quarantinedPath: String?,
    cause: Throwable
) : IOException("Diary $diaryId is corrupted and was copied to quarantine", cause)

class ReadOnlyDiaryException(val diaryId: String, val version: Int) :
    IOException("Diary $diaryId has schemaVersion $version which is newer than supported version (read-only)")

/**
 * Manages diary file lifecycle, safe writes, index synchronization, and startup recovery
 * per STORAGE_SPEC Sections 3, 4, and 5.
 */
class DiaryRepository(
    val vaultId: VaultId,
    val vaultDir: File,
    private val fileReader: (File) -> ByteArray = { it.readBytes() }
) {
    val diariesDir = File(vaultDir, "diaries")
    val quarantineDir = File(vaultDir, "quarantine")
    val indexManager = VaultIndexManager(vaultId, vaultDir)

    companion object {
        const val MAX_IO_RETRIES = 3

        fun extractRecordMediaIds(record: DiaryRecord): Set<String> {
            val ids = mutableSetOf<String>()
            record.pfp?.sourceMediaId?.let { ids.add(it) }
            record.background?.mediaId?.let { ids.add(it) }
            record.images.forEach { ids.add(it.mediaId) }
            record.audios.forEach { ids.add(it.mediaId) }
            record.videos.forEach { ids.add(it.mediaId) }
            return ids
        }
    }

    fun ensureDirs() {
        diariesDir.mkdirs()
        quarantineDir.mkdirs()
    }

    private fun isTransientIoError(t: Throwable): Boolean {
        return t is IOException &&
            t !is EOFException &&
            t !is CorruptedDiaryException &&
            t !is ReadOnlyDiaryException
    }

    private fun isCryptoOrFormatError(t: Throwable): Boolean {
        return t is AEADBadTagException ||
            t is SerializationException ||
            t is IllegalArgumentException ||
            t is EOFException
    }

    private fun readBytesWithRetry(file: File, maxAttempts: Int = MAX_IO_RETRIES): ByteArray {
        var lastError: IOException? = null
        for (attempt in 1..maxAttempts) {
            try {
                return fileReader(file)
            } catch (e: IOException) {
                lastError = e
                if (e is EOFException || attempt == maxAttempts) throw e
            }
        }
        throw lastError ?: IOException("Failed to read ${file.name}")
    }

    /**
     * Verifies whether [dek] matches this vault before quarantining any file.
     * Prevents mass-quarantining healthy files when a wrong DEK is supplied.
     */
    private fun isDekValidForVault(
        dek: ByteArray,
        excludeFile: File?,
        dekVerified: Boolean?
    ): Boolean {
        if (dekVerified == false) return false
        if (dek.size != CryptoUtils.AES_KEY_SIZE_BYTES) return false
        if (dekVerified == true) return true

        if (indexManager.indexFile.exists()) {
            when (indexManager.readIndexStatus(dek)) {
                is IndexReadResult.Loaded -> return true
                is IndexReadResult.Corrupt, is IndexReadResult.Missing -> {}
            }
        }

        val otherLkdFiles = diariesDir.listFiles { f ->
            f.isFile && f.name.endsWith(".lkd") && f.absolutePath != excludeFile?.absolutePath
        } ?: emptyArray()

        var anyOtherDecoded = false
        var anyOtherAuthFailed = false
        for (other in otherLkdFiles) {
            val otherId = other.name.removeSuffix(".lkd")
            try {
                val bytes = readBytesWithRetry(other, maxAttempts = 1)
                DiaryFileCodec.decode(bytes, dek, otherId)
                anyOtherDecoded = true
                break
            } catch (e: AEADBadTagException) {
                anyOtherAuthFailed = true
            } catch (_: Exception) {
            }
        }
        if (anyOtherDecoded) return true

        // If encrypted index OR another diary failed auth with this DEK and zero succeeded,
        // treat DEK as potentially wrong and do not quarantine.
        if (indexManager.indexFile.exists() || anyOtherAuthFailed) {
            return false
        }
        return true
    }

    /**
     * Copies a corrupt diary file to quarantine/ via 64 KB streaming (never moves or deletes original).
     * Idempotent when an identical copy for [diaryId] already exists in quarantine/.
     */
    private fun quarantineCopy(sourceFile: File, diaryId: String): File {
        quarantineDir.mkdirs()
        val tmpQFile = File(quarantineDir, "${diaryId}_${System.currentTimeMillis()}.lkd.tmp")
        if (tmpQFile.exists()) tmpQFile.delete()

        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        try {
            FileInputStream(sourceFile).use { fis ->
                FileOutputStream(tmpQFile).use { fos ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        md.update(buffer, 0, read)
                        fos.write(buffer, 0, read)
                    }
                    fos.flush()
                    runCatching { if (fos.fd.valid()) fos.fd.sync() }
                }
            }
        } catch (e: Exception) {
            tmpQFile.delete()
            throw e
        }

        val hash = md.digest().joinToString("") { "%02x".format(it) }
        val existing = quarantineDir.listFiles { f ->
            f.isFile && f.name.startsWith("${diaryId}_") && f.name.endsWith(".lkd")
        }?.firstOrNull { candidate ->
            runCatching { CryptoUtils.sha256Hex(candidate) == hash }.getOrDefault(false)
        }
        if (existing != null) {
            tmpQFile.delete()
            return existing
        }

        val qFile = File(quarantineDir, "${diaryId}_${System.currentTimeMillis()}.lkd")
        if (!tmpQFile.renameTo(qFile)) {
            tmpQFile.delete()
            throw IOException("Failed to finalize quarantine file for $diaryId")
        }
        SafeStorageWriter.fsyncDirectory(quarantineDir)
        return qFile
    }

    private fun listDiskDiaryIds(): Set<String> {
        if (!diariesDir.exists()) return emptySet()
        return (diariesDir.listFiles { f -> f.isFile && f.name.endsWith(".lkd") } ?: emptyArray())
            .map { it.name.removeSuffix(".lkd") }
            .toSet()
    }

    private fun canDecodeDiary(id: String, dek: ByteArray): Boolean {
        val file = File(diariesDir, "$id.lkd")
        if (!file.exists()) return false
        return try {
            val bytes = readBytesWithRetry(file)
            DiaryFileCodec.decode(bytes, dek, id)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun indexMatchesDisk(index: VaultIndexRecord, dek: ByteArray): Boolean {
        val diskIds = listDiskDiaryIds()
        val indexIds = index.entries.map { it.id }.toSet()
        if ((indexIds - diskIds).isNotEmpty()) return false
        val missingFromIndex = diskIds - indexIds
        if (missingFromIndex.any { canDecodeDiary(it, dek) }) return false
        return true
    }

    /**
     * Executes recovery scan on app start / vault unlock per STORAGE_SPEC Section 5:
     * - Cleans up dangling .tmp files from crashed writes.
     * - Recovers from .bak if main .lkd is missing or corrupt.
     * - Copies tampered / corrupt .lkd files to quarantine/ ONLY when DEK is valid and error is AEADBadTag/format.
     * - Retries transient IO errors without moving/quarantining files.
     * - Self-heals & reconciles the encrypted index against disk .lkd files.
     */
    @Synchronized
    fun scanAndRecover(dek: ByteArray, dekVerified: Boolean? = null): RecoveryReport {
        diariesDir.mkdirs()
        quarantineDir.mkdirs()

        val files = diariesDir.listFiles() ?: emptyArray()
        var cleanedTmp = 0
        val restoredBaks = mutableListOf<String>()
        val quarantined = mutableListOf<String>()
        val ioErrors = mutableListOf<String>()

        // 1. Clean dangling .tmp files in diaries/ and quarantine/
        for (f in files) {
            if (f.name.endsWith(".tmp")) {
                if (f.delete()) cleanedTmp++
            }
        }
        val quarantineFiles = quarantineDir.listFiles() ?: emptyArray()
        for (qf in quarantineFiles) {
            if (qf.name.endsWith(".tmp")) {
                if (qf.delete()) cleanedTmp++
            }
        }

        // 2. Check and handle .bak files
        val bakFiles = diariesDir.listFiles { f -> f.name.endsWith(".bak") } ?: emptyArray()
        for (bak in bakFiles) {
            val mainFileName = bak.name.removeSuffix(".bak")
            val mainFile = File(diariesDir, mainFileName)
            val diaryId = mainFileName.removeSuffix(".lkd")

            var mainHealthy = false
            var mainIoError = false
            if (mainFile.exists() && mainFile.length() > DiaryFileCodec.HEADER_SIZE) {
                try {
                    val bytes = readBytesWithRetry(mainFile)
                    DiaryFileCodec.decode(bytes, dek, diaryId)
                    mainHealthy = true
                } catch (e: Exception) {
                    if (isTransientIoError(e)) {
                        mainIoError = true
                        ioErrors.add("$diaryId: ${e.message ?: "IO error"}")
                    } else {
                        mainHealthy = false
                    }
                }
            }

            if (mainIoError) {
                continue
            }

            if (!mainHealthy) {
                // Verify .bak before restoring
                val bakHealthy = try {
                    val bakBytes = readBytesWithRetry(bak)
                    DiaryFileCodec.decode(bakBytes, dek, diaryId)
                    true
                } catch (_: Exception) {
                    false
                }
                if (bakHealthy) {
                    if (mainFile.exists()) mainFile.delete()
                    bak.renameTo(mainFile)
                    restoredBaks.add(diaryId)
                }
            } else {
                // Main file is healthy, discard .bak
                bak.delete()
            }
        }

        // 3. Scan .lkd files for crypto/format corruption (copy to quarantine, never move; retry IO errors)
        val lkdFiles = diariesDir.listFiles { f -> f.isFile && f.name.endsWith(".lkd") } ?: emptyArray()
        for (lkd in lkdFiles) {
            val diaryId = lkd.name.removeSuffix(".lkd")
            try {
                val bytes = readBytesWithRetry(lkd)
                DiaryFileCodec.decode(bytes, dek, diaryId)
            } catch (e: Exception) {
                if (isTransientIoError(e)) {
                    // Retry already attempted; never move/quarantine on transient IO failure
                    ioErrors.add("$diaryId: ${e.message ?: "IO error"}")
                } else if (isCryptoOrFormatError(e) && isDekValidForVault(dek, lkd, dekVerified)) {
                    val qFile = quarantineCopy(lkd, diaryId)
                    quarantined.add(qFile.absolutePath)
                }
            }
        }

        // 4. Verify index exists, decrypts, and matches disk diary IDs; otherwise rebuild
        var indexRebuilt = false
        val indexStatus = indexManager.readIndexStatus(dek)
        val needsRebuild = when (indexStatus) {
            is IndexReadResult.Loaded -> !indexMatchesDisk(indexStatus.index, dek)
            is IndexReadResult.Missing, is IndexReadResult.Corrupt -> true
        }
        if (needsRebuild) {
            runCatching {
                indexManager.rebuildFromDiariesVerified(diariesDir, dek)
                indexRebuilt = true
            }
        }

        return RecoveryReport(
            restoredFromBackup = restoredBaks,
            quarantinedFiles = quarantined,
            cleanedTempFiles = cleanedTmp,
            indexRebuilt = indexRebuilt,
            ioErrors = ioErrors
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
                val existing = DiaryFileCodec.decode(readBytesWithRetry(targetFile), dek, record.id)
                if (existing.isReadOnly) {
                    throw ReadOnlyDiaryException(record.id, existing.schemaVersion)
                }
            } catch (e: ReadOnlyDiaryException) {
                throw e
            } catch (_: Exception) {
                // Corrupt file will be replaced by safe write
            }
        }

        val encodedBytes = DiaryFileCodec.encode(record, dek)

        // Safe write: free space check -> .tmp -> fsync -> verify read-back -> .bak -> atomic rename
        SafeStorageWriter.safeWrite(targetFile, encodedBytes) { tmpFile ->
            val tmpBytes = tmpFile.readBytes()
            DiaryFileCodec.decode(tmpBytes, dek, record.id)
        }

        // Update encrypted index (rebuilds if index is missing/corrupt)
        val entry = IndexEntryRecord(
            id = record.id,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
            pinned = record.pinned,
            flags = (if (record.diaryLock != null) 1 else 0) or
                    (if (record.audios.isNotEmpty()) 2 else 0) or
                    (if (record.images.isNotEmpty()) 4 else 0) or
                    (if (record.videos.isNotEmpty()) 8 else 0),
            plainPreview = record.plainPreview,
            heading = record.heading,
            mediaIds = extractRecordMediaIds(record).sorted(),
            hasTrackedMediaIds = true
        )
        indexManager.upsertEntry(entry, dek, diariesDir)
    }

    /**
     * Loads and decrypts a diary file.
     * Retries transient IO errors without moving the file.
     * Copies corrupt files to quarantine/ ONLY when DEK is valid and error is AEADBadTag/format, then throws [CorruptedDiaryException].
     */
    @Synchronized
    fun loadDiary(id: String, dek: ByteArray, dekVerified: Boolean? = null): DecodedDiary {
        val file = File(diariesDir, "$id.lkd")
        if (!file.exists()) {
            throw IOException("Diary file not found: $id.lkd")
        }

        val rawBytes = readBytesWithRetry(file)
        return try {
            DiaryFileCodec.decode(rawBytes, dek, id)
        } catch (e: Exception) {
            if (isCryptoOrFormatError(e) && isDekValidForVault(dek, file, dekVerified)) {
                quarantineDir.mkdirs()
                val qFile = quarantineCopy(file, id)
                throw CorruptedDiaryException(id, qFile.absolutePath, e)
            }
            throw e
        }
    }

    private fun extractRecordMediaIds(record: DiaryRecord): Set<String> =
        Companion.extractRecordMediaIds(record)

    private fun tryRecoverMediaIdsForDiary(
        diaryId: String,
        dek: ByteArray,
        loadedIndex: VaultIndexRecord?
    ): Set<String>? {
        val mainFile = File(diariesDir, "$diaryId.lkd")
        if (mainFile.exists()) {
            try {
                val decoded = DiaryFileCodec.decode(readBytesWithRetry(mainFile), dek, diaryId)
                return extractRecordMediaIds(decoded.record)
            } catch (_: Exception) {}
        }
        val bakFile = File(diariesDir, "$diaryId.lkd.bak")
        if (bakFile.exists()) {
            try {
                val decoded = DiaryFileCodec.decode(readBytesWithRetry(bakFile), dek, diaryId)
                return extractRecordMediaIds(decoded.record)
            } catch (_: Exception) {}
        }
        val qFiles = quarantineDir.listFiles { f ->
            f.isFile && f.name.startsWith("${diaryId}_") && f.name.endsWith(".lkd")
        } ?: emptyArray()
        for (qf in qFiles) {
            try {
                val decoded = DiaryFileCodec.decode(readBytesWithRetry(qf), dek, diaryId)
                return extractRecordMediaIds(decoded.record)
            } catch (_: Exception) {}
        }
        val idxEntry = loadedIndex?.entries?.find { it.id == diaryId }
        if (idxEntry != null && idxEntry.hasTrackedMediaIds) {
            return idxEntry.mediaIds.toSet()
        }
        return null
    }

    /**
     * Scans all referenced media IDs across readable diaries AND recoverable undecodable/quarantined diaries
     * (via .bak / decodable quarantine / tracked index.lki entries).
     * Sets [MediaReferenceScan.hasUndecodableOrQuarantined] ONLY if an undecodable/quarantined diary exists
     * whose media IDs could not be recovered from any source.
     */
    fun scanReferencedMediaIds(dek: ByteArray, excludeDiaryId: String? = null): MediaReferenceScan {
        val mediaIds = mutableSetOf<String>()
        var hasUnknownUndecodable = false
        val loadedIndex = (indexManager.readIndexStatus(dek) as? IndexReadResult.Loaded)?.index

        val lkdFiles = diariesDir.listFiles { f -> f.isFile && f.name.endsWith(".lkd") } ?: emptyArray()
        for (f in lkdFiles) {
            val diaryId = f.name.removeSuffix(".lkd")
            if (diaryId == excludeDiaryId) continue
            try {
                val decoded = DiaryFileCodec.decode(readBytesWithRetry(f), dek, diaryId)
                mediaIds.addAll(extractRecordMediaIds(decoded.record))
            } catch (_: Exception) {
                val recovered = tryRecoverMediaIdsForDiary(diaryId, dek, loadedIndex)
                if (recovered != null) {
                    mediaIds.addAll(recovered)
                } else {
                    hasUnknownUndecodable = true
                }
            }
        }

        val quarantinedFiles = quarantineDir.listFiles { f -> f.isFile && f.name.endsWith(".lkd") } ?: emptyArray()
        for (qf in quarantinedFiles) {
            val baseId = qf.name.substringBeforeLast('_')
            if (baseId == excludeDiaryId) continue
            try {
                val decoded = DiaryFileCodec.decode(readBytesWithRetry(qf), dek, baseId)
                mediaIds.addAll(extractRecordMediaIds(decoded.record))
            } catch (_: Exception) {
                val recovered = tryRecoverMediaIdsForDiary(baseId, dek, loadedIndex)
                if (recovered != null) {
                    mediaIds.addAll(recovered)
                } else {
                    hasUnknownUndecodable = true
                }
            }
        }

        return MediaReferenceScan(
            referencedIds = mediaIds,
            hasUndecodableOrQuarantined = hasUnknownUndecodable
        )
    }

    /**
     * Collects all media IDs referenced across readable diaries in this vault,
     * optionally excluding a specific diary ID.
     */
    fun collectAllReferencedMediaIds(dek: ByteArray, excludeDiaryId: String? = null): Set<String> {
        return scanReferencedMediaIds(dek, excludeDiaryId).referencedIds
    }

    /**
     * Runs orphan media cleanup while protecting any media that belongs (or could belong) to undecodable/quarantined diaries.
     */
    @Synchronized
    fun cleanupOrphanMedia(
        dek: ByteArray,
        mediaRepo: EncryptedMediaRepository,
        olderThanMs: Long = EncryptedMediaRepository.ORPHAN_AGE_THRESHOLD_MS,
        processStartTimeMs: Long = 0L,
        protectedMediaIds: Set<String> = emptySet(),
        forcedOrphanIds: Set<String> = emptySet()
    ): Int {
        val scan = scanReferencedMediaIds(dek)
        return mediaRepo.cleanupOrphans(
            referencedMediaIds = scan.referencedIds,
            hasUndecodableOrQuarantined = scan.hasUndecodableOrQuarantined,
            olderThanMs = olderThanMs,
            processStartTimeMs = processStartTimeMs,
            protectedMediaIds = protectedMediaIds,
            forcedOrphanIds = forcedOrphanIds
        )
    }

    /**
     * Deletes a diary in strict order:
     * 1. Remove diary files (.lkd / .bak / .tmp / quarantine copies)
     * 2. Update encrypted index
     * 3. Delete unreferenced media (propagating failures after retry; if diary was unreadable with unknown IDs,
     *    marks unreferenced media on disk as orphan candidates)
     */
    @Synchronized
    fun deleteDiary(
        id: String,
        dek: ByteArray,
        mediaRepo: EncryptedMediaRepository? = null,
        onDeleteStep: ((String) -> Unit)? = null
    ) {
        val file = File(diariesDir, "$id.lkd")
        val bakFile = File(diariesDir, "$id.lkd.bak")
        val tmpFile = File(diariesDir, "$id.lkd.tmp")
        val qFiles = quarantineDir.listFiles { f ->
            f.isFile && f.name.startsWith("${id}_") && f.name.endsWith(".lkd")
        } ?: emptyArray()

        val loadedIndex = (indexManager.readIndexStatus(dek) as? IndexReadResult.Loaded)?.index
        val existedOnDisk = file.exists() || bakFile.exists() || qFiles.isNotEmpty()
        val recoveredMediaIds = if (mediaRepo != null && existedOnDisk) {
            tryRecoverMediaIdsForDiary(id, dek, loadedIndex)
        } else null
        val hadUnreadableUnknownIds = mediaRepo != null && existedOnDisk && recoveredMediaIds == null

        // Step 1: Delete diary files (+ any quarantine copies for this diary)
        if (file.exists() && !file.delete()) {
            throw IOException("Failed to delete diary file: ${file.name}")
        }
        if (bakFile.exists()) bakFile.delete()
        if (tmpFile.exists()) tmpFile.delete()
        for (qf in qFiles) qf.delete()
        onDeleteStep?.invoke("diary_file_deleted")

        // Step 2: Update encrypted index
        indexManager.removeEntry(id, dek, diariesDir)
        onDeleteStep?.invoke("index_updated")

        // Step 3: Delete unreferenced media or mark orphan candidates
        if (mediaRepo != null) {
            val remainingScan = scanReferencedMediaIds(dek, excludeDiaryId = id)
            if (recoveredMediaIds != null && recoveredMediaIds.isNotEmpty() && !remainingScan.hasUndecodableOrQuarantined) {
                val res = mediaRepo.deleteUnreferencedMediaForDiaryChecked(
                    recoveredMediaIds,
                    remainingScan.referencedIds
                )
                if (res.failedIds.isNotEmpty()) {
                    throw IOException("Failed to delete media file(s): ${res.failedIds.joinToString()}")
                }
            } else if (hadUnreadableUnknownIds) {
                val diskIds = mediaRepo.listMediaIdsOnDisk()
                val candidates = diskIds - remainingScan.referencedIds
                mediaRepo.markOrphanCandidates(candidates)
            }
        }
        onDeleteStep?.invoke("media_deleted")
    }

    /**
     * Lists all diary summaries from the encrypted index.
     * Self-heals if the index is missing, corrupted, or out of sync with disk .lkd IDs.
     */
    @Synchronized
    fun listDiaries(dek: ByteArray): List<IndexEntryRecord> {
        val index = loadIndex(dek)
        return index.entries.sortedWith(
            compareByDescending<IndexEntryRecord> { it.pinned }.thenByDescending { it.createdAt }
        )
    }

    @Synchronized
    fun loadIndex(dek: ByteArray): VaultIndexRecord {
        val status = indexManager.readIndexStatus(dek)
        if (status is IndexReadResult.Loaded && indexMatchesDisk(status.index, dek)) {
            return status.index
        }
        return indexManager.rebuildFromDiariesVerified(diariesDir, dek)
    }

    /**
     * Loads diaries one-by-one and transforms each immediately via [mapper] (e.g., to summary-only
     * entries without contentHtml) so full records are never accumulated in RAM together.
     */
    @Synchronized
    fun <T> loadAllDiariesMapped(
        dek: ByteArray,
        mapper: (DiaryRecord) -> T
    ): Pair<List<T>, List<FailedDiaryEntry>> {
        val diskIds = listDiskDiaryIds()
        val entries = listDiaries(dek)
        val orderedIds = LinkedHashSet<String>()
        entries.forEach { orderedIds.add(it.id) }
        diskIds.sorted().forEach { orderedIds.add(it) }

        val mapped = mutableListOf<T>()
        val failed = mutableListOf<FailedDiaryEntry>()
        for (id in orderedIds) {
            try {
                val record = loadDiary(id, dek).record
                mapped.add(mapper(record))
            } catch (e: CorruptedDiaryException) {
                failed.add(
                    FailedDiaryEntry(
                        diaryId = id,
                        error = e.message ?: "Corrupted diary",
                        quarantinedPath = e.quarantinedPath
                    )
                )
            } catch (e: Exception) {
                failed.add(
                    FailedDiaryEntry(
                        diaryId = id,
                        error = e.message ?: "Failed to load diary"
                    )
                )
            }
        }
        return mapped to failed
    }

    /**
     * Loads all diaries and returns both successfully decoded records and any failed/corrupt entries.
     */
    @Synchronized
    fun loadAllDiaries(dek: ByteArray): LoadDiariesResult {
        val (loaded, failed) = loadAllDiariesMapped(dek) { it }
        return LoadDiariesResult(diaries = loaded, failed = failed)
    }
}
