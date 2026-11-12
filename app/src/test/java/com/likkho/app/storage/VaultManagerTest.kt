package com.likkho.app.storage

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class VaultManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var keyStoreProvider: KeyStoreProvider
    private lateinit var vaultManager: VaultManager
    private val testDispatcher = UnconfinedTestDispatcher()
    private val fakeClock = object : ClockProvider {
        var elapsed: Long = 10_000L
        var wall: Long = 1_000_000L
        override fun elapsedRealtimeMs(): Long = elapsed
        override fun wallClockMs(): Long = wall
    }

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("likkho_base")
        keyStoreProvider = InMemoryKeyStoreProvider()
        vaultManager = VaultManager(
            baseDir = baseDir,
            keyStoreProvider = keyStoreProvider,
            dispatcher = testDispatcher,
            pbkdf2Iterations = 5000, // Fast for unit tests
            clockProvider = fakeClock
        )
    }

    @Test
    fun primaryVault_initializesInKeystoreOnlyMode_whenFirstCreated() = runTest(testDispatcher) {
        val session = vaultManager.ensurePrimaryVaultInitialized()
        assertEquals(VaultId.PRIMARY, session.vaultId)
        assertTrue(vaultManager.isUnlocked)
        assertFalse(vaultManager.hasPasscode(VaultId.PRIMARY))

        // Decoy vault directory should not exist on disk
        assertFalse(vaultManager.decoyExists())
        assertFalse(File(baseDir, "v2").exists())
    }

    @Test
    fun dualVaults_isolationAndPinRouting() = runTest(testDispatcher) {
        // 1. Initialize primary vault and set primary PIN
        val primaryPin = "1111".toCharArray()
        vaultManager.ensurePrimaryVaultInitialized()
        vaultManager.setPrimaryPasscode(primaryPin)
        assertTrue(vaultManager.hasPasscode(VaultId.PRIMARY))
        vaultManager.lock()
        assertFalse(vaultManager.isUnlocked)

        // Decoy still does not exist
        assertFalse(vaultManager.decoyExists())

        // 2. Set Decoy PIN
        val decoyPin = "2222".toCharArray()
        vaultManager.setDecoyPasscode(decoyPin, primaryPin)
        assertTrue(vaultManager.decoyExists())
        assertTrue(vaultManager.hasPasscode(VaultId.DECOY))

        // 3. Unlock with Primary PIN -> opens PRIMARY vault
        val result1 = vaultManager.unlockWithPin("1111".toCharArray())
        assertTrue(result1 is UnlockResult.Success)
        assertEquals(VaultId.PRIMARY, (result1 as UnlockResult.Success).vaultId)
        assertEquals(VaultId.PRIMARY, vaultManager.currentVault)
        var primaryDek: ByteArray? = null
        vaultManager.withActiveDek { primaryDek = it.clone() }
        assertNotNull(primaryDek)
        vaultManager.lock()

        // 4. Unlock with Decoy PIN -> opens DECOY vault
        val result2 = vaultManager.unlockWithPin("2222".toCharArray())
        assertTrue(result2 is UnlockResult.Success)
        assertEquals(VaultId.DECOY, (result2 as UnlockResult.Success).vaultId)
        assertEquals(VaultId.DECOY, vaultManager.currentVault)
        var decoyDek: ByteArray? = null
        vaultManager.withActiveDek { decoyDek = it.clone() }
        assertNotNull(decoyDek)

        // 5. DEKs must be completely distinct
        assertFalse(primaryDek!!.contentEquals(decoyDek!!))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decoyPin_cannotEqualPrimaryPin() = runTest(testDispatcher) {
        val pin = "1234".toCharArray()
        vaultManager.ensurePrimaryVaultInitialized()
        vaultManager.setPrimaryPasscode(pin)

        // Same PIN for decoy must be rejected
        vaultManager.setDecoyPasscode(pin, pin)
    }

    @Test
    fun wrongPin_triggersExponentialCooldownOn5thAttempt() = runTest(testDispatcher) {
        val primaryPin = "4444".toCharArray()
        vaultManager.ensurePrimaryVaultInitialized()
        vaultManager.setPrimaryPasscode(primaryPin)
        vaultManager.lock()

        val wrongPin = "9999".toCharArray()

        // 1-4 wrong attempts: no cooldown
        for (i in 1..4) {
            val res = vaultManager.unlockWithPin(wrongPin)
            assertTrue(res is UnlockResult.WrongPin)
            assertEquals(0, (res as UnlockResult.WrongPin).cooldownTriggeredSeconds)
        }

        // 5th wrong attempt: 30s cooldown
        val res5 = vaultManager.unlockWithPin(wrongPin)
        assertTrue(res5 is UnlockResult.WrongPin)
        assertEquals(30, (res5 as UnlockResult.WrongPin).cooldownTriggeredSeconds)

        // Subsequent attempt immediately blocked by cooldown
        val blocked = vaultManager.unlockWithPin(wrongPin)
        assertTrue(blocked is UnlockResult.InCooldown)
        assertTrue((blocked as UnlockResult.InCooldown).remainingSeconds > 0)
    }

    @Test
    fun lock_zeroizesActiveDekInMemory() = runTest(testDispatcher) {
        val pin = "1234".toCharArray()
        vaultManager.ensurePrimaryVaultInitialized()
        vaultManager.setPrimaryPasscode(pin)

        val unlockRes = vaultManager.unlockWithPin(pin)
        assertTrue(unlockRes is UnlockResult.Success)
        val session = (unlockRes as UnlockResult.Success).session
        assertTrue(session.isOpen)

        // Lock vault
        vaultManager.lock()
        assertFalse(session.isOpen)
        assertFalse(vaultManager.isUnlocked)

        var accessFailed = false
        try {
            session.useDek { /* access */ }
        } catch (e: IllegalStateException) {
            accessFailed = true
        }
        assertTrue("Accessing DEK after lock must throw", accessFailed)
    }

    @Test
    fun clearAllData_performsCryptoShredding() = runTest(testDispatcher) {
        val primaryPin = "1111".toCharArray()
        val decoyPin = "2222".toCharArray()
        vaultManager.ensurePrimaryVaultInitialized()
        vaultManager.setPrimaryPasscode(primaryPin)
        vaultManager.setDecoyPasscode(decoyPin, primaryPin)

        assertTrue(File(baseDir, "v1").exists())
        assertTrue(File(baseDir, "v2").exists())

        vaultManager.clearAllData()

        assertFalse(vaultManager.isUnlocked)
        assertFalse(File(baseDir, "v1").exists())
        assertFalse(File(baseDir, "v2").exists())
        assertFalse(File(baseDir, WrongPinCounter.FILE_NAME).exists())
        assertFalse(File(baseDir, PinLengthsRegistry.FILE_NAME).exists())
    }
}
