package com.likkho.app.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class InsufficientStorageException(msg: String) : IOException(msg)
class StorageVerificationException(msg: String, cause: Throwable? = null) : IOException(msg, cause)

/**
 * Implements atomic, fail-safe disk writes per STORAGE_SPEC Section 5:
 * - Free space check before write (ensures disk full never corrupts existing data).
 * - Write to .tmp -> flush + fsync -> verify read-back -> backup to .bak -> atomic rename -> remove .bak.
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
                bakFile.renameTo(targetFile)
            }
            tmpFile.delete()
            throw IOException("Failed to rename temporary file to ${targetFile.name}")
        }

        // 6. Delete .bak after successful atomic rename
        if (bakFile.exists()) {
            bakFile.delete()
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
