package com.likkho.app.storage

import android.media.MediaDataSource
import android.util.Log
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages encrypted media storage (images, audio, fonts, pfp, background)
 * per STORAGE_SPEC Section 6:
 * - Encrypted file per media in vault's media folder. Diary contains only random media ID.
 * - Streaming AEAD (Tink AesGcmHkdfStreaming) so large audio files stream without full memory loading.
 * - Plaintext never persisted to disk. Decode in memory.
 * - Atomic import: temp write -> verify read-back -> atomic rename.
 * - Orphan cleanup: removes unreferenced media older than 24 hours (prevents race conditions with new imports).
 */
data class MediaDeleteResult(
    val deletedCount: Int,
    val failedIds: List<String> = emptyList()
)

class EncryptedMediaRepository @JvmOverloads constructor(
    val vaultId: VaultId,
    val vaultDir: File,
    val clockProvider: ClockProvider = DefaultClockProvider,
    private val fileDeleter: (File) -> Boolean = { it.delete() }
) {
    val mediaDir = File(vaultDir, "media")
    val orphanCandidatesFile = File(vaultDir, "orphan_candidates.json")
    val pendingSessionImportsFile = File(vaultDir, "pending_session_imports.json")
    private val activeImportIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val metadataLock = Any()

    companion object {
        const val MEDIA_EXTENSION = "lkm"
        const val CIPHERTEXT_SEGMENT_SIZE_BYTES = 64 * 1024 // 64 KB segments
        const val DERIVED_KEY_SIZE_BYTES = 32 // AES-256
        const val ORPHAN_AGE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val TAG = "EncryptedMediaRepo"
        private val json = Json { ignoreUnknownKeys = true }
        private val candidatesSerializer = MapSerializer(String.serializer(), Long.serializer())

        fun buildAad(mediaId: String, vaultId: VaultId): ByteArray {
            return "LKM1_${vaultId.dirName}_$mediaId".toByteArray(Charsets.UTF_8)
        }

        fun createStreamingAead(dek: ByteArray): StreamingAead {
            return AesGcmHkdfStreaming(
                dek,
                "HmacSha256",
                DERIVED_KEY_SIZE_BYTES,
                CIPHERTEXT_SEGMENT_SIZE_BYTES,
                0
            )
        }
    }

    fun ensureDirs() {
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
    }

    fun getMediaFile(mediaId: String): File = File(mediaDir, "$mediaId.$MEDIA_EXTENSION")

    fun isImportActive(mediaId: String): Boolean = activeImportIds.contains(mediaId)

    internal fun runWithActiveImportForTest(mediaId: String, block: () -> Unit) {
        activeImportIds.add(mediaId)
        try {
            block()
        } finally {
            activeImportIds.remove(mediaId)
        }
    }

    /**
     * Atomically imports an input stream into an encrypted media file.
     * Enforces free space check, writes to .tmp, fsyncs, verifies decryption, and atomically renames.
     */
    fun importMedia(inputStream: InputStream, dek: ByteArray): String {
        val mediaId = CryptoUtils.randomId("med_")
        return importMedia(mediaId, inputStream, dek)
    }

    fun importMedia(
        mediaId: String,
        inputStream: InputStream,
        dek: ByteArray,
        estimatedSizeBytes: Long = 0L
    ): String = importMediaInternal(mediaId, inputStream, dek, estimatedSizeBytes, afterEncryptHook = null)

    internal fun importMediaInternal(
        mediaId: String,
        inputStream: InputStream,
        dek: ByteArray,
        estimatedSizeBytes: Long = 0L,
        afterEncryptHook: ((File) -> Unit)? = null
    ): String {
        activeImportIds.add(mediaId)
        val targetFile = getMediaFile(mediaId)
        val tmpFile = File(mediaDir, "$mediaId.$MEDIA_EXTENSION.tmp")
        var completed = false
        try {
            ensureDirs()

            // 1. Free space check
            val requiredBytes = estimatedSizeBytes + SafeStorageWriter.MIN_FREE_SPACE_BYTES
            val usable = mediaDir.usableSpace
            if (usable in 1 until requiredBytes) {
                throw InsufficientStorageException(
                    "Insufficient storage for media import: required $requiredBytes bytes, available $usable bytes"
                )
            }

            if (tmpFile.exists()) {
                tmpFile.delete()
            }

            val streamingAead = createStreamingAead(dek)
            val aad = buildAad(mediaId, vaultId)

            // 2. Encrypt stream directly to .tmp file; on failure delete .tmp and do NOT finalize stream
            try {
                FileOutputStream(tmpFile).use { fos ->
                    val nonClosingOut = object : FilterOutputStream(fos) {
                        override fun write(b: Int) = fos.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) = fos.write(b, off, len)
                        override fun flush() = fos.flush()
                        override fun close() = fos.flush()
                    }
                    val encryptingStream: OutputStream = streamingAead.newEncryptingStream(nonClosingOut, aad)
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        encryptingStream.write(buffer, 0, bytesRead)
                    }
                    // Fsync open FileChannel before closing encrypting stream, then finalize & fsync FD before fos closes
                    fos.flush()
                    if (fos.channel.isOpen) {
                        fos.channel.force(true)
                    }
                    encryptingStream.close()
                    fos.flush()
                    if (fos.channel.isOpen) {
                        fos.channel.force(true)
                    }
                    if (fos.fd.valid()) {
                        fos.fd.sync()
                    }
                }
            } catch (e: Throwable) {
                tmpFile.delete()
                throw e
            }

            afterEncryptHook?.invoke(tmpFile)

            // 3. Verify read-back by draining the ENTIRE decrypting stream to EOF
            try {
                FileInputStream(tmpFile).use { fis ->
                    streamingAead.newDecryptingStream(fis, aad).use { decryptingStream ->
                        val verifyBuffer = ByteArray(8192)
                        while (decryptingStream.read(verifyBuffer) != -1) {
                            // Drain all segments and verify every GCM tag to EOF
                        }
                    }
                }
            } catch (e: Throwable) {
                tmpFile.delete()
                throw StorageVerificationException("Media verification failed after encrypting: ${e.message}", e)
            }

            // 4. Atomic rename to target file
            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tmpFile.renameTo(targetFile)
            if (!renamed) {
                tmpFile.delete()
                throw IOException("Failed to rename temporary media file to ${targetFile.name}")
            }
            SafeStorageWriter.fsyncDirectory(mediaDir)
            completed = true
            return mediaId
        } finally {
            if (!completed && tmpFile.exists()) {
                tmpFile.delete()
            }
            activeImportIds.remove(mediaId)
        }
    }

    /**
     * Convenience method to import in-memory byte array (e.g. avatar, font, image preview).
     */
    fun importMediaBytes(mediaId: String, data: ByteArray, dek: ByteArray): String {
        return importMedia(mediaId, data.inputStream(), dek, data.size.toLong())
    }

    /**
     * Opens a streaming decrypted InputStream for large audio files or media playback.
     * Decrypts on-the-fly chunk-by-chunk without loading whole file into memory.
     */
    fun openDecryptedStream(mediaId: String, dek: ByteArray): InputStream {
        val file = getMediaFile(mediaId)
        if (!file.exists()) {
            throw IOException("Media file not found: $mediaId.$MEDIA_EXTENSION")
        }

        val streamingAead = createStreamingAead(dek)
        val aad = buildAad(mediaId, vaultId)
        val fis = FileInputStream(file)
        return streamingAead.newDecryptingStream(fis, aad)
    }

    /**
     * Reads decrypted media directly into memory (for images, fonts, avatars).
     * Plaintext is never stored to disk.
     */
    fun readDecryptedBytes(mediaId: String, dek: ByteArray): ByteArray {
        openDecryptedStream(mediaId, dek).use { stream ->
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                baos.write(buffer, 0, read)
            }
            return baos.toByteArray()
        }
    }

    /**
     * Opens a seekable in-memory MediaDataSource backed by Tink StreamingAEAD for random-access
     * video/audio playback without persisting plaintext to disk or buffering entire large videos/audio in RAM.
     */
    fun openMediaDataSource(mediaId: String, dek: ByteArray): MediaDataSource {
        val reader = openSeekableMediaReader(mediaId, dek)
        return StreamingEncryptedMediaDataSource(reader)
    }

    internal fun openSeekableMediaReader(mediaId: String, dek: ByteArray): SeekableEncryptedMediaReader {
        val file = getMediaFile(mediaId)
        if (!file.exists()) {
            throw IOException("Media file not found: $mediaId.$MEDIA_EXTENSION")
        }
        val streamingAead = createStreamingAead(dek)
        val aad = buildAad(mediaId, vaultId)
        return SeekableEncryptedMediaReader(file, streamingAead, aad)
    }

    internal class SeekableEncryptedMediaReader(
        file: File,
        streamingAead: StreamingAead,
        aad: ByteArray
    ) : java.io.Closeable {
        private val fis = FileInputStream(file)
        private val channel = streamingAead.newSeekableDecryptingChannel(fis.channel, aad)
        private var cachedSize: Long = -1L

        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0) return -1
            val totalSize = getSize()
            if (totalSize >= 0 && position >= totalSize) return -1
            if (size <= 0) return 0
            channel.position(position)
            val bb = ByteBuffer.wrap(buffer, offset, size)
            var totalRead = 0
            while (bb.hasRemaining()) {
                val r = channel.read(bb)
                if (r == -1) break
                totalRead += r
            }
            return if (totalRead == 0) -1 else totalRead
        }

        @Synchronized
        fun getSize(): Long {
            if (cachedSize < 0) {
                cachedSize = runCatching { channel.size() }.getOrDefault(-1L)
            }
            return cachedSize
        }

        @Synchronized
        override fun close() {
            runCatching { channel.close() }
            runCatching { fis.close() }
        }
    }

    private class StreamingEncryptedMediaDataSource(
        private val reader: SeekableEncryptedMediaReader
    ) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            reader.readAt(position, buffer, offset, size)

        override fun getSize(): Long = reader.getSize()

        override fun close() = reader.close()
    }

    /**
     * Deletes media file and any associated temporary files.
     * Logs a warning and retries once if file deletion fails.
     */
    fun deleteMedia(mediaId: String): Boolean = synchronized(metadataLock) {
        val file = getMediaFile(mediaId)
        val tmpFile = File(mediaDir, "$mediaId.$MEDIA_EXTENSION.tmp")
        if (tmpFile.exists() && !activeImportIds.contains(mediaId)) tmpFile.delete()
        if (!file.exists()) {
            removeOrphanCandidates(setOf(mediaId))
            clearPendingSessionImports(setOf(mediaId))
            return@synchronized true
        }
        if (fileDeleter(file)) {
            removeOrphanCandidates(setOf(mediaId))
            clearPendingSessionImports(setOf(mediaId))
            return@synchronized true
        }
        runCatching { Log.w(TAG, "Failed to delete media $mediaId; retrying once.") }
        val retryOk = fileDeleter(file)
        if (!retryOk) {
            runCatching { Log.e(TAG, "Retry failed deleting media $mediaId at ${file.absolutePath}") }
        } else {
            removeOrphanCandidates(setOf(mediaId))
            clearPendingSessionImports(setOf(mediaId))
        }
        return@synchronized retryOk
    }

    /**
     * Checks if a media file exists on disk.
     */
    fun exists(mediaId: String): Boolean = getMediaFile(mediaId).exists()

    fun listMediaIdsOnDisk(): Set<String> {
        if (!mediaDir.exists()) return emptySet()
        return (mediaDir.listFiles { f -> f.isFile && f.name.endsWith(".$MEDIA_EXTENSION") } ?: emptyArray())
            .map { it.name.removeSuffix(".$MEDIA_EXTENSION") }
            .toSet()
    }

    fun loadOrphanCandidates(): Map<String, Long> = synchronized(metadataLock) {
        if (!orphanCandidatesFile.exists()) return@synchronized emptyMap()
        return@synchronized runCatching {
            json.decodeFromString(candidatesSerializer, orphanCandidatesFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyMap())
    }

    private fun saveOrphanCandidates(map: Map<String, Long>) {
        if (map.isEmpty()) {
            if (orphanCandidatesFile.exists()) orphanCandidatesFile.delete()
            return
        }
        vaultDir.mkdirs()
        orphanCandidatesFile.writeText(json.encodeToString(candidatesSerializer, map), Charsets.UTF_8)
    }

    fun markOrphanCandidates(mediaIds: Set<String>) = synchronized(metadataLock) {
        if (mediaIds.isEmpty()) return@synchronized
        val now = clockProvider.wallClockMs()
        val updated = loadOrphanCandidates().toMutableMap()
        var changed = false
        for (id in mediaIds) {
            if (!updated.containsKey(id) && exists(id)) {
                updated[id] = now
                changed = true
            }
        }
        if (changed) saveOrphanCandidates(updated)
    }

    fun removeOrphanCandidates(mediaIds: Set<String>) = synchronized(metadataLock) {
        if (mediaIds.isEmpty() || !orphanCandidatesFile.exists()) return@synchronized
        val updated = loadOrphanCandidates().toMutableMap()
        var changed = false
        for (id in mediaIds) {
            if (updated.remove(id) != null) changed = true
        }
        if (changed) saveOrphanCandidates(updated)
    }

    fun loadPendingSessionImports(): Map<String, Long> = synchronized(metadataLock) {
        if (!pendingSessionImportsFile.exists()) return@synchronized emptyMap()
        return@synchronized runCatching {
            json.decodeFromString(candidatesSerializer, pendingSessionImportsFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyMap())
    }

    private fun savePendingSessionImports(map: Map<String, Long>) {
        if (map.isEmpty()) {
            if (pendingSessionImportsFile.exists()) pendingSessionImportsFile.delete()
            File(vaultDir, "${pendingSessionImportsFile.name}.tmp").delete()
            return
        }
        vaultDir.mkdirs()
        val tmp = File(vaultDir, "${pendingSessionImportsFile.name}.tmp")
        tmp.writeText(json.encodeToString(candidatesSerializer, map), Charsets.UTF_8)
        if (!tmp.renameTo(pendingSessionImportsFile)) {
            pendingSessionImportsFile.writeText(json.encodeToString(candidatesSerializer, map), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun recordPendingSessionImport(mediaId: String) = synchronized(metadataLock) {
        if (mediaId.isBlank()) return@synchronized
        val updated = loadPendingSessionImports().toMutableMap()
        updated[mediaId] = clockProvider.wallClockMs()
        savePendingSessionImports(updated)
    }

    fun clearPendingSessionImports(mediaIds: Set<String>) = synchronized(metadataLock) {
        if (mediaIds.isEmpty() || !pendingSessionImportsFile.exists()) return@synchronized
        val current = loadPendingSessionImports()
        if (current.isEmpty()) {
            pendingSessionImportsFile.delete()
            return@synchronized
        }
        val updated = current.toMutableMap()
        var changed = false
        for (id in mediaIds) {
            if (updated.remove(id) != null) changed = true
        }
        if (changed) savePendingSessionImports(updated)
    }

    /**
     * Deletes media files that are referenced only by the specified diary and not referenced
     * by any other diary in the vault, returning deleted count and any failed media IDs.
     */
    fun deleteUnreferencedMediaForDiaryChecked(
        deletedDiaryMediaIds: Set<String>,
        remainingReferencedMediaIds: Set<String>
    ): MediaDeleteResult = synchronized(metadataLock) {
        var deletedCount = 0
        val failedIds = mutableListOf<String>()
        for (mediaId in deletedDiaryMediaIds) {
            if (!remainingReferencedMediaIds.contains(mediaId)) {
                if (deleteMedia(mediaId)) {
                    deletedCount++
                } else {
                    failedIds.add(mediaId)
                }
            }
        }
        return@synchronized MediaDeleteResult(deletedCount = deletedCount, failedIds = failedIds)
    }

    /**
     * Deletes media files that are referenced only by the specified diary and not referenced
     * by any other diary in the vault.
     */
    fun deleteUnreferencedMediaForDiary(
        deletedDiaryMediaIds: Set<String>,
        remainingReferencedMediaIds: Set<String>
    ): Int {
        return deleteUnreferencedMediaForDiaryChecked(deletedDiaryMediaIds, remainingReferencedMediaIds).deletedCount
    }

    /**
     * Cleans up orphaned media per STORAGE_SPEC Section 6 and the process-start abandonment rules:
     * - Protects `.tmp` and `.lkm` files belonging to [activeImportIds] or [protectedMediaIds].
     * - Protects any media referenced in saved/recoverable diaries ([referencedMediaIds]).
     * - Respects [hasUndecodableOrQuarantined] guard when unknown-ID undecodable diaries exist.
     * - Immediately deletes unreferenced media (without 24h wait) if:
     *   (a) [processStartTimeMs] > 0 and file.lastModified() < [processStartTimeMs], or
     *   (b) mediaId is in [forcedOrphanIds] (e.g., pending session import left behind by killed process), or
     *   (c) [olderThanMs] <= 0.
     * - Otherwise falls back to standard >24h ([olderThanMs]) orphan threshold.
     */
    fun cleanupOrphans(
        referencedMediaIds: Set<String>,
        hasUndecodableOrQuarantined: Boolean = false,
        olderThanMs: Long = ORPHAN_AGE_THRESHOLD_MS,
        processStartTimeMs: Long = 0L,
        protectedMediaIds: Set<String> = emptySet(),
        forcedOrphanIds: Set<String> = emptySet()
    ): Int = synchronized(metadataLock) {
        if (!mediaDir.exists()) return@synchronized 0

        // Clean abandoned .tmp files only when not actively importing
        val tmpFiles = mediaDir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        for (tmp in tmpFiles) {
            val tmpMediaId = tmp.name.removeSuffix(".$MEDIA_EXTENSION.tmp").removeSuffix(".tmp")
            if (!activeImportIds.contains(tmpMediaId)) {
                tmp.delete()
            }
        }

        val candidates = loadOrphanCandidates()
        val files = mediaDir.listFiles { f -> f.name.endsWith(".$MEDIA_EXTENSION") } ?: emptyArray()
        val diskIds = files.map { it.name.removeSuffix(".$MEDIA_EXTENSION") }.toSet()

        // Prune pending session import entries for IDs that are already referenced or missing from disk
        if (pendingSessionImportsFile.exists()) {
            val stalePending = loadPendingSessionImports().keys.filter { id ->
                referencedMediaIds.contains(id) || (!diskIds.contains(id) && !activeImportIds.contains(id))
            }.toSet()
            if (stalePending.isNotEmpty()) {
                clearPendingSessionImports(stalePending)
            }
        }

        if (hasUndecodableOrQuarantined && candidates.isEmpty()) {
            return@synchronized 0
        }

        val now = clockProvider.wallClockMs()
        var deletedCount = 0
        val remainingCandidates = candidates.toMutableMap()
        var candidatesChanged = false

        // Prune candidates that became referenced or no longer exist on disk
        for (candId in candidates.keys) {
            if (referencedMediaIds.contains(candId) || !diskIds.contains(candId)) {
                remainingCandidates.remove(candId)
                candidatesChanged = true
            }
        }

        val deletedForcedIds = mutableSetOf<String>()
        for (file in files) {
            val mediaId = file.name.removeSuffix(".$MEDIA_EXTENSION")
            if (referencedMediaIds.contains(mediaId)) continue
            if (protectedMediaIds.contains(mediaId) || activeImportIds.contains(mediaId)) continue

            val candidateTs = remainingCandidates[mediaId]
            if (hasUndecodableOrQuarantined && candidateTs == null) {
                continue
            }

            val refTimestamp = if (candidateTs != null) minOf(file.lastModified(), candidateTs) else file.lastModified()
            val age = now - refTimestamp
            val olderThanProcessStart = processStartTimeMs > 0L && file.lastModified() < processStartTimeMs
            val isForcedAbandoned = forcedOrphanIds.contains(mediaId)
            val eligible = olderThanMs <= 0L || isForcedAbandoned || olderThanProcessStart || age > olderThanMs
            if (eligible) {
                if (deleteMedia(mediaId)) {
                    deletedCount++
                    if (remainingCandidates.remove(mediaId) != null) {
                        candidatesChanged = true
                    }
                    if (isForcedAbandoned) {
                        deletedForcedIds.add(mediaId)
                    }
                }
            }
        }

        if (deletedForcedIds.isNotEmpty()) {
            clearPendingSessionImports(deletedForcedIds)
        }
        if (candidatesChanged) {
            saveOrphanCandidates(remainingCandidates)
        }
        return@synchronized deletedCount
    }
}
