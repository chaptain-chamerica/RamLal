package com.likkho.app.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

interface ClockProvider {
    fun elapsedRealtimeMs(): Long
    fun wallClockMs(): Long
}

object DefaultClockProvider : ClockProvider {
    override fun elapsedRealtimeMs(): Long = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (e: Throwable) {
        System.nanoTime() / 1_000_000L
    }
    override fun wallClockMs(): Long = System.currentTimeMillis()
}

/**
 * Wrong-PIN counter protected by Keystore HMAC-SHA256 and monotonic clock checks.
 * As required by STORAGE_SPEC Section 2:
 * "Wrong-PIN counter disk par save ho, HMAC (Keystore key) se protected,
 * elapsedRealtime/boot-count ke saath, taaki app restart ya clock change se bypass na ho.
 * Rule: 5th wrong = 30s, 6th+ = 30s x attempts, sahi par reset."
 */
class WrongPinCounter(
    private val storageDir: File,
    private val keyStoreProvider: KeyStoreProvider,
    private val clockProvider: ClockProvider = DefaultClockProvider
) {
    private val file = File(storageDir, FILE_NAME)
    private val tmpFile = File(storageDir, "$FILE_NAME.tmp")

    companion object {
        const val FILE_NAME = "counter.dat"
        const val HMAC_KEY_ALIAS = "likkho_ks_counter"
        private const val MAGIC = 0x57504331 // "WPC1"
        private const val PAYLOAD_SIZE = 4 + 4 + 4 + 8 + 8 // 28 bytes
        private const val HMAC_SIZE = 32
        private const val TOTAL_SIZE = PAYLOAD_SIZE + HMAC_SIZE // 60 bytes
    }

    data class State(
        val failedAttempts: Int,
        val cooldownSec: Int,
        val cooldownEndElapsedRealtimeMs: Long,
        val recordedWallClockMs: Long
    )

    @Synchronized
    fun readState(): State {
        if (!file.exists()) {
            return State(0, 0, 0L, 0L)
        }

        return try {
            val bytes = file.readBytes()
            if (bytes.size != TOTAL_SIZE) {
                // Corrupted or tampered
                return State(5, 30, clockProvider.elapsedRealtimeMs() + 30_000L, clockProvider.wallClockMs())
            }

            val payload = ByteArray(PAYLOAD_SIZE)
            val hmac = ByteArray(HMAC_SIZE)
            System.arraycopy(bytes, 0, payload, 0, PAYLOAD_SIZE)
            System.arraycopy(bytes, PAYLOAD_SIZE, hmac, 0, HMAC_SIZE)

            val isValid = keyStoreProvider.verifyHmac(HMAC_KEY_ALIAS, payload, hmac)
            if (!isValid) {
                // Tampered HMAC
                return State(5, 30, clockProvider.elapsedRealtimeMs() + 30_000L, clockProvider.wallClockMs())
            }

            val buf = ByteBuffer.wrap(payload)
            val magic = buf.int
            if (magic != MAGIC) {
                return State(5, 30, clockProvider.elapsedRealtimeMs() + 30_000L, clockProvider.wallClockMs())
            }
            val failed = buf.int
            val cdSec = buf.int
            val cdEndElapsed = buf.long
            val wallClock = buf.long

            State(failed, cdSec, cdEndElapsed, wallClock)
        } catch (e: Exception) {
            State(5, 30, clockProvider.elapsedRealtimeMs() + 30_000L, clockProvider.wallClockMs())
        }
    }

    @Synchronized
    private fun writeState(state: State) {
        storageDir.mkdirs()
        val buf = ByteBuffer.allocate(PAYLOAD_SIZE)
        buf.putInt(MAGIC)
        buf.putInt(state.failedAttempts)
        buf.putInt(state.cooldownSec)
        buf.putLong(state.cooldownEndElapsedRealtimeMs)
        buf.putLong(state.recordedWallClockMs)
        val payload = buf.array()

        val hmac = keyStoreProvider.computeHmac(HMAC_KEY_ALIAS, payload)

        FileOutputStream(tmpFile).use { fos ->
            fos.write(payload)
            fos.write(hmac)
            fos.fd.sync()
        }

        if (file.exists()) {
            file.delete()
        }
        tmpFile.renameTo(file)
    }

    /**
     * Calculates remaining cooldown seconds using monotonic clock.
     * Prevents clock rollbacks or app restarts from bypassing the cooldown.
     */
    @Synchronized
    fun remainingCooldownSeconds(
        nowElapsedRealtimeMs: Long = clockProvider.elapsedRealtimeMs()
    ): Int {
        val state = readState()
        if (state.cooldownSec <= 0) return 0

        val diffElapsedMs = state.cooldownEndElapsedRealtimeMs - nowElapsedRealtimeMs
        if (diffElapsedMs <= 0) {
            return 0
        }
        return ((diffElapsedMs + 999) / 1000).toInt()
    }

    /**
     * Records a wrong PIN attempt and enforces rule:
     * 1-4 wrong: 0s
     * 5th wrong: 30s
     * 6th+ wrong: 30s * attempts
     * Returns cooldown seconds triggered.
     */
    @Synchronized
    fun recordFailure(
        nowElapsedRealtimeMs: Long = clockProvider.elapsedRealtimeMs(),
        nowWallClockMs: Long = clockProvider.wallClockMs()
    ): Int {
        val current = readState()
        val nextAttempts = current.failedAttempts + 1

        val cooldownSec = when {
            nextAttempts == 5 -> 30
            nextAttempts > 5 -> 30 * nextAttempts
            else -> 0
        }

        val cooldownEndElapsed = if (cooldownSec > 0) {
            nowElapsedRealtimeMs + (cooldownSec * 1000L)
        } else {
            0L
        }

        val nextState = State(
            failedAttempts = nextAttempts,
            cooldownSec = cooldownSec,
            cooldownEndElapsedRealtimeMs = cooldownEndElapsed,
            recordedWallClockMs = nowWallClockMs
        )
        writeState(nextState)
        return cooldownSec
    }

    /**
     * Resets failed attempts and cooldown to 0 on successful authentication.
     */
    @Synchronized
    fun recordSuccess() {
        writeState(State(0, 0, 0L, 0L))
    }

    @Synchronized
    fun clear() {
        if (file.exists()) file.delete()
        if (tmpFile.exists()) tmpFile.delete()
        keyStoreProvider.deleteKey(HMAC_KEY_ALIAS)
    }
}
