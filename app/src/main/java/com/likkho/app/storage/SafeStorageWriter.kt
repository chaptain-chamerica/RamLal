package com.likkho.app.storage

import android.annotation.SuppressLint
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class InsufficientStorageException(msg: String) : IOException(msg)
class StorageVerificationException(msg: String, cause: Throwable? = null) : IOException(msg, cause)

/**
 * Implements atomic, fail-safe disk writes per STORAGE_SPEC Section 5:
 * - Free space check before write (ensures disk full never corrupts existing data).
 * - Write to .tmp -> flush + fsync -> verify read-back -> backup to .bak -> atomic rename -> parent dir fsync -> remove .bak.
 */
object SafeStorageWriter {

    const val MIN_FREE_SPACE_BYTES = 5L * 1024L * 1024L // 5 MB safety margin

    /**
     * Safely writes [data] to [targetFile].
     * Optional [verifier] lambda reads back the newly written .tmp file to guarantee decodability before replacement.
     */
    @Synchronized
    fun safeWrite(
        targetFile: File,
        data: ByteArray,
        verifier: ((File) -> Unit)? = null
    ) {
        safeWriteInternal(targetFile, data, verifier, onDirectorySync = null)
    }

    /**
     * Streams data to [targetFile] via [writer] without holding the full payload in memory.
     * Enforces free space check, writes to .tmp -> flush + fsync -> [verifier] -> .bak -> atomic rename -> parent dir fsync -> remove .bak.
     */
    @Synchronized
    fun safeWriteStreaming(
        targetFile: File,
        estimatedRequiredBytes: Long = 0L,
        verifier: ((File) -> Unit)? = null,
        writer: (FileOutputStream) -> Unit
    ) {
        val parentDir = targetFile.parentFile ?: throw IOException("Target file has no parent directory")
        parentDir.mkdirs()

        val requiredBytes = estimatedRequiredBytes.coerceAtLeast(0L) + MIN_FREE_SPACE_BYTES
        val usable = parentDir.usableSpace
        if (usable in 1 until requiredBytes) {
            throw InsufficientStorageException(
                "Insufficient storage: required $requiredBytes bytes, available $usable bytes"
            )
        }

        val tmpFile = File(parentDir, "${targetFile.name}.tmp")
        val bakFile = File(parentDir, "${targetFile.name}.bak")
        if (tmpFile.exists()) {
            tmpFile.delete()
        }

        try {
            FileOutputStream(tmpFile).use { fos ->
                writer(fos)
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

        if (verifier != null) {
            try {
                verifier(tmpFile)
            } catch (e: Exception) {
                tmpFile.delete()
                throw StorageVerificationException("Verification of streamed file failed: ${e.message}", e)
            }
        }

        if (targetFile.exists()) {
            if (bakFile.exists()) {
                bakFile.delete()
            }
            if (!targetFile.renameTo(bakFile)) {
                copyFile(targetFile, bakFile)
                targetFile.delete()
            }
        }

        val renamed = tmpFile.renameTo(targetFile)
        if (!renamed) {
            if (bakFile.exists() && !targetFile.exists()) {
                if (bakFile.renameTo(targetFile)) {
                    fsyncDirectory(parentDir)
                }
            }
            tmpFile.delete()
            throw IOException("Failed to rename temporary file to ${targetFile.name}")
        }

        fsyncDirectory(parentDir)
        if (bakFile.exists()) {
            bakFile.delete()
        }
    }

    @Synchronized
    internal fun safeWriteInternal(
        targetFile: File,
        data: ByteArray,
        verifier: ((File) -> Unit)? = null,
        onDirectorySync: ((File) -> Unit)? = null
    ) {
        val parentDir = targetFile.parentFile ?: throw IOException("Target file has no parent directory")
        parentDir.mkdirs()

        // 1. Free space check
        val requiredBytes = data.size + MIN_FREE_SPACE_BYTES
        val usable = parentDir.usableSpace
        if (usable in 1 until requiredBytes) {
            throw InsufficientStorageException(
                "Insufficient storage: required $requiredBytes bytes, available $usable bytes"
            )
        }

        val tmpFile = File(parentDir, "${targetFile.name}.tmp")
        val bakFile = File(parentDir, "${targetFile.name}.bak")

        // Clean up any stale tmp file from an interrupted write
        if (tmpFile.exists()) {
            tmpFile.delete()
        }

        // 2. Write to .tmp and fsync
        FileOutputStream(tmpFile).use { fos ->
            fos.write(data)
            fos.flush()
            fos.fd.sync()
        }

        // 3. Verify write
        if (tmpFile.length() != data.size.toLong()) {
            tmpFile.delete()
            throw StorageVerificationException("Tmp file size mismatch after fsync")
        }

        if (verifier != null) {
            try {
                verifier(tmpFile)
            } catch (e: Exception) {
                tmpFile.delete()
                throw StorageVerificationException("Verification of written file failed: ${e.message}")
            }
        }

        // 4. Backup existing target file to .bak
        if (targetFile.exists()) {
            if (bakFile.exists()) {
                bakFile.delete()
            }
            if (!targetFile.renameTo(bakFile)) {
                // If rename fails, try copy
                copyFile(targetFile, bakFile)
                targetFile.delete()
            }
        }

        // 5. Atomic rename .tmp -> targetFile
        val renamed = tmpFile.renameTo(targetFile)
        if (!renamed) {
            // Atomic rename failed: attempt recovery from .bak
            if (bakFile.exists() && !targetFile.exists()) {
                if (bakFile.renameTo(targetFile)) {
                    fsyncDirectory(parentDir)
                }
            }
            tmpFile.delete()
            throw IOException("Failed to rename temporary file to ${targetFile.name}")
        }

        // Fsync parent directory so the renamed directory entry is durable (best-effort)
        onDirectorySync?.invoke(parentDir)
        fsyncDirectory(parentDir)

        // 6. Delete .bak after successful atomic rename
        if (bakFile.exists()) {
            bakFile.delete()
        }
    }

    /**
     * Best-effort fsync of [dir] via FileChannel (API 26+ / JVM) or Os.fsync (API 24-25).
     */
    @SuppressLint("NewApi")
    internal fun fsyncDirectory(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || Build.VERSION.SDK_INT == 0) {
            try {
                FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { channel ->
                    channel.force(true)
                }
                return true
            } catch (_: Throwable) {
                // Best-effort fallback below
            }
        }
        return try {
            val fd = Os.open(dir.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
                true
            } finally {
                Os.close(fd)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }
}
