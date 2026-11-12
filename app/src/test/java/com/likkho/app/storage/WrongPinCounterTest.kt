package com.likkho.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WrongPinCounterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var keyStoreProvider: KeyStoreProvider
    private lateinit var counter: WrongPinCounter
    private val fakeClock = object : ClockProvider {
        var elapsed: Long = 5000L
        var wall: Long = 1000L
        override fun elapsedRealtimeMs(): Long = elapsed
        override fun wallClockMs(): Long = wall
    }

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("counter_test")
        keyStoreProvider = InMemoryKeyStoreProvider()
        counter = WrongPinCounter(storageDir, keyStoreProvider, fakeClock)
    }

    @Test
    fun initialCounter_hasZeroCooldownAndZeroAttempts() {
        assertEquals(0, counter.remainingCooldownSeconds(nowElapsedRealtimeMs = 1000L))
        val state = counter.readState()
        assertEquals(0, state.failedAttempts)
        assertEquals(0, state.cooldownSec)
    }

    @Test
    fun failureProgression_enforcesSpecRule() {
        var elapsed = 10_000L
        val wall = 1_000_000L

        // 1st to 4th failure: no cooldown
        for (i in 1..4) {
            val cd = counter.recordFailure(nowElapsedRealtimeMs = elapsed, nowWallClockMs = wall)
            assertEquals("Attempt $i should have 0s cooldown", 0, cd)
            assertEquals(0, counter.remainingCooldownSeconds(nowElapsedRealtimeMs = elapsed))
            elapsed += 1000L
        }

        // 5th failure: triggers 30s cooldown
        val cd5 = counter.recordFailure(nowElapsedRealtimeMs = elapsed, nowWallClockMs = wall)
        assertEquals(30, cd5)
        assertEquals(30, counter.remainingCooldownSeconds(nowElapsedRealtimeMs = elapsed))

        // After 10s passed: 20s remaining
        assertEquals(20, counter.remainingCooldownSeconds(nowElapsedRealtimeMs = elapsed + 10_000L))

        // After 30s passed: 0s remaining
        assertEquals(0, counter.remainingCooldownSeconds(nowElapsedRealtimeMs = elapsed + 30_000L))

        // 6th failure: 30s * 6 = 180s
        elapsed += 30_000L
        val cd6 = counter.recordFailure(nowElapsedRealtimeMs = elapsed, nowWallClockMs = wall)
        assertEquals(180, cd6)
        assertEquals(180, counter.remainingCooldownSeconds(nowElapsedRealtimeMs = elapsed))

        // 7th failure: 30s * 7 = 210s
        elapsed += 180_000L
        val cd7 = counter.recordFailure(nowElapsedRealtimeMs = elapsed, nowWallClockMs = wall)
        assertEquals(210, cd7)
        assertEquals(210, counter.remainingCooldownSeconds(nowElapsedRealtimeMs = elapsed))
    }

    @Test
    fun recordSuccess_resetsFailuresAndCooldown() {
        val elapsed = 5000L
        val wall = 5000L
        for (i in 1..5) {
            counter.recordFailure(elapsed, wall)
        }
        assertTrue(counter.remainingCooldownSeconds(elapsed) > 0)

        counter.recordSuccess()
        assertEquals(0, counter.remainingCooldownSeconds(elapsed))
        val state = counter.readState()
        assertEquals(0, state.failedAttempts)
        assertEquals(0, state.cooldownSec)
    }

    @Test
    fun persistence_survivesNewInstance() {
        val elapsed = 5000L
        val wall = 5000L
        counter.recordFailure(elapsed, wall)
        counter.recordFailure(elapsed, wall)

        val newCounter = WrongPinCounter(storageDir, keyStoreProvider, fakeClock)
        val state = newCounter.readState()
        assertEquals(2, state.failedAttempts)
    }

    @Test
    fun tamperedFile_failsSafeToLockdown() {
        val elapsed = 5000L
        val wall = 5000L
        counter.recordFailure(elapsed, wall)

        // Corrupt file on disk
        val file = File(storageDir, WrongPinCounter.FILE_NAME)
        val bytes = file.readBytes()
        bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 0xFF).toByte()
        file.writeBytes(bytes)

        // Reading tampered file should fail HMAC verification and lock down
        val state = counter.readState()
        assertEquals(5, state.failedAttempts)
        assertEquals(30, state.cooldownSec)
    }
}
