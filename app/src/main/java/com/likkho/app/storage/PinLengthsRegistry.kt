package com.likkho.app.storage

import java.io.File
import java.io.FileOutputStream

/**
 * Stores valid PIN lengths (e.g. 4, 6) in a plain file without revealing which length
 * belongs to which vault, as required by STORAGE_SPEC Section 2:
 * "PIN lengths (4–6) ek plain file me save ho, bina ye batae ki kaunsi vault ki hai (dots UI ke liye).
 * Auto-unlock attempt tab ho jab typed length is set me ho.
 * Failure sirf tab count ho jab aakhri applicable length par bhi koi vault na khule."
 */
class PinLengthsRegistry(private val storageDir: File) {

    private val file = File(storageDir, FILE_NAME)
    private val tmpFile = File(storageDir, "$FILE_NAME.tmp")

    companion object {
        const val FILE_NAME = "pin_lengths.txt"
    }

    @Synchronized
    fun getRegisteredLengths(): Set<Int> {
        if (!file.exists()) return emptySet()
        return try {
            file.readText()
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 4..12 }
                .toSortedSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    @Synchronized
    fun updateLengths(lengths: Set<Int>) {
        storageDir.mkdirs()
        val text = lengths.sorted().joinToString(",")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        if (file.exists()) file.delete()
        tmpFile.renameTo(file)
    }

    @Synchronized
    fun registerLength(len: Int) {
        val current = getRegisteredLengths().toMutableSet()
        current.add(len)
        updateLengths(current)
    }

    @Synchronized
    fun unregisterLength(len: Int) {
        val current = getRegisteredLengths().toMutableSet()
        current.remove(len)
        updateLengths(current)
    }

    val applicableLengths: Set<Int> get() = getRegisteredLengths()

    fun isRegisteredLength(len: Int): Boolean = shouldAttemptAutoUnlock(len)

    fun shouldAttemptAutoUnlock(length: Int): Boolean {
        val registered = getRegisteredLengths()
        return length in registered
    }

    fun isMaxRegisteredLength(length: Int): Boolean {
        val registered = getRegisteredLengths()
        if (registered.isEmpty()) return length >= 4
        return length >= (registered.maxOrNull() ?: 4)
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
        if (tmpFile.exists()) tmpFile.delete()
    }
}
