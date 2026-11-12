package com.likkho.app.storage

import java.io.Closeable

/**
 * Represents an active, unlocked vault session in memory.
 * Per STORAGE_SPEC Section 2 & 10:
 * "DEK: har vault ke liye random 256-bit AES key. Sirf memory me jab vault unlocked ho.
 * Lock par ya app background jane par zero-fill karke hata do. Keys ByteArray me, use ke baad zero-fill."
 */
class VaultSession(
    val vaultId: VaultId,
    private val rawDek: ByteArray
) : Closeable {

    @Volatile
    private var isClosed = false

    /**
     * Provides temporary access to the active DEK.
     * Throws [IllegalStateException] if the session has already been locked or zero-filled.
     */
    fun <T> useDek(block: (ByteArray) -> T): T {
        check(!isClosed) { "VaultSession $vaultId has already been closed/zero-filled" }
        return block(rawDek)
    }

    val isOpen: Boolean get() = !isClosed

    override fun close() {
        if (!isClosed) {
            isClosed = true
            CryptoUtils.zeroize(rawDek)
        }
    }
}
