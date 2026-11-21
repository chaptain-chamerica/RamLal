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
        var wall: Long = 100_000_000_000L
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

    @Test
    fun startupAndAllUnlockPaths_deferMaintenanceUntilAfterSuccess_andCleanWhenTriggered() = runTest(testDispatcher) {
        // 1. Initialize Primary vault cleanly: Success returns without synchronous scanAndRecover
        vaultManager.ensurePrimaryVaultInitialized()
        org.junit.Assert.assertNull(
            "ensurePrimaryVaultInitialized must not block on synchronous scanAndRecover",
            vaultManager.lastRecoveryReport
        )
        vaultManager.withActiveDek { dek ->
            val mediaRepo = vaultManager.primaryMediaRepo
            mediaRepo.importMediaBytes("orphan_old_1", "old orphan".toByteArray(), dek)
            mediaRepo.getMediaFile("orphan_old_1").setLastModified(fakeClock.wall - (25L * 60 * 60 * 1000))
        }
        vaultManager.lock()

        // Create dangling .tmp before unlockNoPasscode
        vaultManager.primaryDiaryRepo.ensureDirs()
        val tmp1 = File(vaultManager.primaryDiaryRepo.diariesDir, "stale1.lkd.tmp")
        tmp1.writeBytes(byteArrayOf(1, 2, 3))

        // 2. unlockNoPasscode returns Success immediately BEFORE maintenance runs
        val resNoPin = vaultManager.unlockNoPasscode()
        assertTrue(resNoPin is UnlockResult.Success)
        org.junit.Assert.assertNull("Unlock must return Success before maintenance", vaultManager.lastRecoveryReport)
        assertTrue(".tmp must still exist right after unlock (deferred)", tmp1.exists())

        vaultManager.runPostUnlockMaintenance(VaultId.PRIMARY)
        assertFalse("runPostUnlockMaintenance must clean .tmp", tmp1.exists())
        assertFalse("runPostUnlockMaintenance must clean old orphan media", vaultManager.primaryMediaRepo.exists("orphan_old_1"))
        assertEquals(1, vaultManager.lastRecoveryReport?.cleanedTempFiles)

        // 3. Set PIN, lock, create .tmp, verify unlockWithPin (Primary) defers maintenance
        val pin = "1234".toCharArray()
        vaultManager.setPrimaryPasscode(pin)
        vaultManager.lock()
        val tmp2 = File(vaultManager.primaryDiaryRepo.diariesDir, "stale2.lkd.tmp")
        tmp2.writeBytes(byteArrayOf(4, 5, 6))

        val resPin = vaultManager.unlockWithPin(pin)
        assertTrue(resPin is UnlockResult.Success)
        org.junit.Assert.assertNull("PIN unlock must return Success before maintenance", vaultManager.lastRecoveryReport)
        assertTrue(tmp2.exists())

        vaultManager.runPostUnlockMaintenance(VaultId.PRIMARY)
        assertFalse("Post-unlock maintenance (Primary) must clean .tmp", tmp2.exists())
        assertEquals(1, vaultManager.lastRecoveryReport?.cleanedTempFiles)

        // 4. Enable biometric via CryptoObject cipher (unlocked), lock, create .tmp, verify biometric unlock + deferred maintenance
        val encCipher = vaultManager.prepareBiometricEncryptCipher()
        vaultManager.enableBiometricWithCipher(encCipher)
        vaultManager.lock()

        val tmp3 = File(vaultManager.primaryDiaryRepo.diariesDir, "stale3.lkd.tmp")
        tmp3.writeBytes(byteArrayOf(7, 8, 9))

        val resBio = vaultManager.unlockWithBiometricPrompt { decryptCipher -> decryptCipher }
        assertTrue(resBio is UnlockResult.Success)
        org.junit.Assert.assertNull("Biometric unlock must return Success before maintenance", vaultManager.lastRecoveryReport)
        assertTrue(tmp3.exists())

        vaultManager.runPostUnlockMaintenance(VaultId.PRIMARY)
        assertFalse("Post-unlock maintenance (Biometric) must clean .tmp", tmp3.exists())
        assertEquals(1, vaultManager.lastRecoveryReport?.cleanedTempFiles)
        vaultManager.lock()

        // 5. Setup Decoy vault, create .tmp in decoy dir, verify unlockWithPin (Decoy) + deferred maintenance
        val decoyPin = "5678".toCharArray()
        vaultManager.setDecoyPasscode(decoyPin, pin)
        vaultManager.decoyDiaryRepo.ensureDirs()
        val tmpDecoy = File(vaultManager.decoyDiaryRepo.diariesDir, "stale_decoy.lkd.tmp")
        tmpDecoy.writeBytes(byteArrayOf(10, 11))

        val resDecoy = vaultManager.unlockWithPin(decoyPin)
        assertTrue(resDecoy is UnlockResult.Success)
        assertEquals(VaultId.DECOY, (resDecoy as UnlockResult.Success).vaultId)
        org.junit.Assert.assertNull(vaultManager.lastRecoveryReport)
        assertTrue(tmpDecoy.exists())

        vaultManager.runPostUnlockMaintenance(VaultId.DECOY)
        assertFalse("Post-unlock maintenance (Decoy) must clean .tmp", tmpDecoy.exists())
        assertEquals(1, vaultManager.lastRecoveryReport?.cleanedTempFiles)
    }

    @Test
    fun postUnlockMaintenance_cleansStaleVaultTmpDirs() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()
        vaultManager.lock()

        val staleDir = File(vaultManager.backupEngine.tempDir, "vault_snapshot_crashed").apply { mkdirs() }
        File(staleDir, "leftover.bin").writeBytes(ByteArray(128 * 1024))
        assertTrue(staleDir.exists())

        val res = vaultManager.unlockNoPasscode()
        assertTrue(res is UnlockResult.Success)
        assertTrue("Cold unlock should not block cleaning tmp before Success", staleDir.exists())

        vaultManager.runPostUnlockMaintenance(VaultId.PRIMARY)
        assertFalse("Post-unlock maintenance must clean stale vaults/tmp folders", staleDir.exists())
    }

    @Test
    fun processKillSimulation_pendingSessionImportDeletedOnPostUnlockMaintenance_whileSavedMediaPreserved() =
        runTest(testDispatcher) {
            vaultManager.ensurePrimaryVaultInitialized()
            val unsavedVideoId = "med_unsaved_kill_vid"
            val savedImageId = "med_saved_img"

            vaultManager.withActiveDek { dek ->
                val mediaRepo = vaultManager.primaryMediaRepo
                mediaRepo.importMediaBytes(unsavedVideoId, ByteArray(16 * 1024) { 7 }, dek)
                mediaRepo.importMediaBytes(savedImageId, ByteArray(8 * 1024) { 3 }, dek)

                // Simulate Workspace session import tracking for both media items
                vaultManager.recordSessionImportedMedia(unsavedVideoId, VaultId.PRIMARY)
                vaultManager.recordSessionImportedMedia(savedImageId, VaultId.PRIMARY)

                // User saves only savedImageId into a diary (never saves unsavedVideoId)
                vaultManager.primaryDiaryRepo.saveDiary(
                    com.likkho.app.storage.model.DiaryRecord(
                        id = "saved_diary_1",
                        heading = "Kept Diary",
                        images = listOf(com.likkho.app.storage.model.CanvasImageRecord(mediaId = savedImageId))
                    ),
                    dek
                )
                vaultManager.clearSavedSessionMedia(setOf(savedImageId), VaultId.PRIMARY)
            }

            // Simulate sudden OS process kill (no exitWorkspace / cleanup called) and fresh VaultManager on next launch
            val restartedVm = VaultManager(
                baseDir = baseDir,
                keyStoreProvider = keyStoreProvider,
                dispatcher = testDispatcher,
                pbkdf2Iterations = 5000,
                clockProvider = fakeClock,
                processStartTimeMs = System.currentTimeMillis() + 60_000L
            )
            val unlockRes = restartedVm.unlockNoPasscode()
            assertTrue(unlockRes is UnlockResult.Success)
            restartedVm.runPostUnlockMaintenance(VaultId.PRIMARY)

            assertFalse(
                "Abandonment from killed process must delete unsaved video immediately without 24h wait",
                restartedVm.primaryMediaRepo.exists(unsavedVideoId)
            )
            assertTrue(
                "Saved diary media must NEVER be deleted across process restart",
                restartedVm.primaryMediaRepo.exists(savedImageId)
            )
            assertTrue(
                "pending_session_imports must be cleaned after unlock reconciliation",
                restartedVm.primaryMediaRepo.loadPendingSessionImports().isEmpty()
            )
        }

    @Test
    fun unlockMaintenance_protectsCurrentProcessSessionMedia_whileCleaningPreProcessOrphans() =
        runTest(testDispatcher) {
            val processStart = System.currentTimeMillis()
            val vm = VaultManager(
                baseDir = tempFolder.newFolder("proc_start_vault"),
                keyStoreProvider = keyStoreProvider,
                dispatcher = testDispatcher,
                pbkdf2Iterations = 5000,
                clockProvider = fakeClock,
                processStartTimeMs = processStart
            )
            vm.ensurePrimaryVaultInitialized()

            val preProcOrphan = "pre_proc_orphan"
            val activeSessionUnsaved = "active_session_unsaved"
            vm.withActiveDek { dek ->
                vm.primaryMediaRepo.importMediaBytes(preProcOrphan, "old".toByteArray(), dek)
                vm.primaryMediaRepo.getMediaFile(preProcOrphan).setLastModified(processStart - 5_000L)

                vm.primaryMediaRepo.importMediaBytes(activeSessionUnsaved, "new".toByteArray(), dek)
                vm.primaryMediaRepo.getMediaFile(activeSessionUnsaved).setLastModified(processStart + 5_000L)
                vm.recordSessionImportedMedia(activeSessionUnsaved, VaultId.PRIMARY)
            }
            vm.lock()

            val reUnlock = vm.unlockNoPasscode()
            assertTrue(reUnlock is UnlockResult.Success)
            vm.runPostUnlockMaintenance(VaultId.PRIMARY)
            assertFalse("Pre-process unreferenced media must be deleted immediately", vm.primaryMediaRepo.exists(preProcOrphan))
            assertTrue("Current-process session media must remain protected", vm.primaryMediaRepo.exists(activeSessionUnsaved))
        }
}

