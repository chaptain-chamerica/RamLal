package com.likkho.app.storage

import android.media.MediaDataSource
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Manages encrypted media storage (images, audio, fonts, pfp, background)
 * per STORAGE_SPEC Section 6:
 * - Encrypted file per media in vault's media folder. Diary contains only random media ID.
 * - Streaming AEAD (Tink AesGcmHkdfStreaming) so large audio files stream without full memory loading.
 * - Plaintext never persisted to disk. Decode in memory.
 * - Atomic import: temp write -> verify read-back -> atomic rename.
 * - Orphan cleanup: removes unreferenced media older than 24 hours (prevents race conditions with new imports).
 */
class EncryptedMediaRepository(
    val vaultId: VaultId,
    val vaultDir: File,
    val clockProvider: ClockProvider = DefaultClockProvider
) {
    val mediaDir = File(vaultDir, "media")

    companion object {
        const val MEDIA_EXTENSION = "lkm"
        const val CIPHERTEXT_SEGMENT_SIZE_BYTES = 64 * 1024 // 64 KB segments
        const val DERIVED_KEY_SIZE_BYTES = 32 // AES-256
        const val ORPHAN_AGE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours

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

    /**
     * Atomically imports an input stream into an encrypted media file.
     * Enforces free space check, writes to .tmp, fsyncs, verifies decryption, and atomically renames.
     */
    @Synchronized
    fun importMedia(inputStream: InputStream, dek: ByteArray): String {
        val mediaId = CryptoUtils.randomId("med_")
        return importMedia(mediaId, inputStream, dek)
    }

    @Synchronized
    fun importMedia(
        mediaId: String,
        inputStream: InputStream,
        dek: ByteArray,
        estimatedSizeBytes: Long = 0L
    ): String = importMediaInternal(mediaId, inputStream, dek, estimatedSizeBytes, afterEncryptHook = null)

    @Synchronized
    internal fun importMediaInternal(
        mediaId: String,
        inputStream: InputStream,
        dek: ByteArray,
        estimatedSizeBytes: Long = 0L,
        afterEncryptHook: ((File) -> Unit)? = null
    ): String {
        ensureDirs()

        // 1. Free space check
        val requiredBytes = estimatedSizeBytes + SafeStorageWriter.MIN_FREE_SPACE_BYTES
        val usable = mediaDir.usableSpace
        if (usable in 1 until requiredBytes) {
            throw InsufficientStorageException(
                "Insufficient storage for media import: required $requiredBytes bytes, available $usable bytes"
            )
        }

        val targetFile = getMediaFile(mediaId)
        val tmpFile = File(mediaDir, "$mediaId.$MEDIA_EXTENSION.tmp")
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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

        return mediaId
    }

    /**
     * Convenience method to import in-memory byte array (e.g. avatar, font, image preview).
     */
    @Synchronized
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
     * video/audio playback without persisting plaintext to disk or buffering entire large videos in RAM.
     */
    fun openMediaDataSource(mediaId: String, dek: ByteArray): MediaDataSource {
        val file = getMediaFile(mediaId)
        if (!file.exists()) {
            throw IOException("Media file not found: $mediaId.$MEDIA_EXTENSION")
        }
        val streamingAead = createStreamingAead(dek)
        val aad = buildAad(mediaId, vaultId)
        return StreamingEncryptedMediaDataSource(file, streamingAead, aad)
    }

    private class StreamingEncryptedMediaDataSource(
        file: File,
        streamingAead: StreamingAead,
        aad: ByteArray
    ) : MediaDataSource() {
        private val fis = FileInputStream(file)
        private val channel = streamingAead.newSeekableDecryptingChannel(fis.channel, aad)
        private var cachedSize: Long = -1L

        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
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
        override fun getSize(): Long {
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

    /**
     * Deletes media file and any associated temporary files.
     */
    @Synchronized
    fun deleteMedia(mediaId: String): Boolean {
        val file = getMediaFile(mediaId)
        val tmpFile = File(mediaDir, "$mediaId.$MEDIA_EXTENSION.tmp")
        if (tmpFile.exists()) tmpFile.delete()
        return if (file.exists()) file.delete() else true
    }

    /**
     * Checks if a media file exists on disk.
     */
    fun exists(mediaId: String): Boolean = getMediaFile(mediaId).exists()

    /**
     * Deletes media files that are referenced only by the specified diary and not referenced
     * by any other diary in the vault.
     */
    @Synchronized
    fun deleteUnreferencedMediaForDiary(
        deletedDiaryMediaIds: Set<String>,
        remainingReferencedMediaIds: Set<String>
    ): Int {
        var deletedCount = 0
        for (mediaId in deletedDiaryMediaIds) {
            if (!remainingReferencedMediaIds.contains(mediaId)) {
                if (deleteMedia(mediaId)) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }

    /**
     * Cleans up orphaned media per STORAGE_SPEC Section 6:
     * - Media files not referenced in any diary AND older than 24 hours are removed.
     * - Newly imported media (< 24 hours old) are kept to prevent race conditions during import.
     * - If undecodable or quarantined diaries exist, media deletion is skipped to preserve their attachments.
     */
    @Synchronized
    fun cleanupOrphans(
        referencedMediaIds: Set<String>,
        hasUndecodableOrQuarantined: Boolean = false,
        olderThanMs: Long = ORPHAN_AGE_THRESHOLD_MS
    ): Int {
        if (!mediaDir.exists()) return 0

        // Always clean abandoned .tmp files
        val tmpFiles = mediaDir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        for (tmp in tmpFiles) {
            tmp.delete()
        }

        if (hasUndecodableOrQuarantined) {
            return 0
        }

        val files = mediaDir.listFiles { f -> f.name.endsWith(".$MEDIA_EXTENSION") } ?: emptyArray()
        val now = clockProvider.wallClockMs()
        var deletedCount = 0

        for (file in files) {
            val mediaId = file.name.removeSuffix(".$MEDIA_EXTENSION")
            if (!referencedMediaIds.contains(mediaId)) {
                val age = now - file.lastModified()
                if (age > olderThanMs) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }
        }

        return deletedCount
    }
}
