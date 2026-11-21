package com.likkho.app

import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.DiaryFileCodec
import com.likkho.app.storage.DiaryRepository
import com.likkho.app.storage.InMemoryKeyStoreProvider
import com.likkho.app.storage.UnlockResult
import com.likkho.app.storage.VaultEnvelope
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.VaultManager
import com.likkho.app.storage.model.DiaryLockRecord
import com.likkho.app.storage.model.DiaryRecord
import com.likkho.app.storage.model.PfpRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureNanoTime

@OptIn(ExperimentalCoroutinesApi::class)
class PasscodeAndBiometricSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var ks: InMemoryKeyStoreProvider
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("sec_vaults")
        ks = InMemoryKeyStoreProvider()
    }

    @Test
    fun indexFastPath_homeLoadDecryptsZeroLkdFiles_andPreservesSummaryFields() = runTest(testDispatcher) {
        val vaultDir = tempFolder.newFolder("idx_fast_vault")
        val dek = CryptoUtils.secureRandomBytes(CryptoUtils.AES_KEY_SIZE_BYTES)
        val lkdReads = AtomicInteger(0)

        val countingRepo = DiaryRepository(
            vaultId = VaultId.PRIMARY,
            vaultDir = vaultDir,
            fileReader = { file ->
                if (file.name.endsWith(".lkd")) {
                    lkdReads.incrementAndGet()
                }
                file.readBytes()
            }
        )

        // Populate 60 diaries with rich HTML body, lock hash, reminder, pinned, pfp
        for (i in 1..60) {
            val isLocked = i % 5 == 0
            countingRepo.saveDiary(
                DiaryRecord(
                    id = "d_$i",
                    heading = "Heading $i",
                    createdAt = 1_700_000_000_000L + i * 1000L,
                    updatedAt = 1_700_000_100_000L + i * 1000L,
                    pinned = i == 3,
                    reminderAt = if (i == 7) 1_800_000_000_000L else null,
                    diaryLock = if (isLocked) DiaryLockRecord("abcd", "1234") else null,
                    pfp = if (i == 1) PfpRecord(text = "★", bgColor = 0xFF3366CC) else null,
                    plainPreview = "Preview $i",
                    contentHtml = "<p>${"Body content ".repeat(100)}</p>"
                ),
                dek
            )
        }

        // Reset read counter and load Home via index fast path
        lkdReads.set(0)
        val result = countingRepo.loadHomeEntries(
            dek = dek,
            fromIndexEntry = { it.toSummaryDiaryLog() },
            fromFullRecord = { it.toSummaryDiaryLog() }
        )

        assertTrue("Valid index.lki must use index fast path", result.usedIndexFastPath)
        assertEquals("Home index load must decrypt 0 .lkd files", 0, lkdReads.get())
        assertEquals(60, result.items.size)
        assertEquals("Pinned item sorted first", "d_3", result.items.first().id)

        val lockedSummary = result.items.first { it.id == "d_5" }
        assertEquals(INDEX_LOCKED_MARKER, lockedSummary.diaryLockPin)
        assertFalse(lockedSummary.isContentLoaded)
        assertEquals("", lockedSummary.contentHtml)

        val reminderItem = result.items.first { it.id == "d_7" }
        assertEquals(1_800_000_000_000L, reminderItem.reminderAt)

        val pfpItem = result.items.first { it.id == "d_1" }
        assertEquals("★", pfpItem.pfp?.text)

        // Corrupt index.lki -> safely falls back and rebuilds from .lkd files
        countingRepo.indexManager.indexFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        lkdReads.set(0)
        val rebuiltResult = countingRepo.loadHomeEntries(
            dek = dek,
            fromIndexEntry = { it.toSummaryDiaryLog() },
            fromFullRecord = { it.toSummaryDiaryLog() }
        )
        assertFalse(rebuiltResult.usedIndexFastPath)
        assertTrue(lkdReads.get() >= 60)
        assertEquals(60, rebuiltResult.items.size)
    }

    @Test
    fun primaryVsDecoy_parallelUnlockTimingParity_fasterThanSequentialDoubleKdf() = runBlocking {
        // Use real multi-threaded Dispatchers.IO + Dispatchers.Default and realistic KDF cost
        val testIterations = 300_000
        val vm = VaultManager(
            baseDir = tempFolder.newFolder("parallel_timing"),
            keyStoreProvider = InMemoryKeyStoreProvider(),
            dispatcher = Dispatchers.IO,
            pbkdf2Iterations = testIterations,
            kdfDispatcher = Dispatchers.Default
        )
        val primaryPin = "1111".toCharArray()
        val decoyPin = "2222".toCharArray()

        vm.ensurePrimaryVaultInitialized()
        vm.setPrimaryPasscode(primaryPin)
        vm.setDecoyPasscode(decoyPin, primaryPin)
        vm.lock()

        // Warm up Dispatchers.Default worker threads, AES-GCM, and PBKDF2 JIT before timing
        val singleKdfSalt = CryptoUtils.secureRandomBytes(16)
        CryptoUtils.zeroize(CryptoUtils.deriveKeyPbkdf2(primaryPin, singleKdfSalt, 30_000))
        assertTrue(vm.unlockWithPin("1111".toCharArray()) is UnlockResult.Success)
        vm.lock()

        fun medianOf3(block: suspend () -> Double): Double {
            val samples = MutableList(3) { runBlocking { block() } }.sorted()
            return samples[1]
        }

        val singleKdfMs = medianOf3 {
            measureNanoTime {
                CryptoUtils.zeroize(CryptoUtils.deriveKeyPbkdf2(primaryPin, singleKdfSalt, testIterations))
            } / 1_000_000.0
        }

        val primaryUnlockMs = medianOf3 {
            val ms = measureNanoTime {
                val res = vm.unlockWithPin("1111".toCharArray())
                assertTrue(res is UnlockResult.Success && res.vaultId == VaultId.PRIMARY)
            } / 1_000_000.0
            vm.lock()
            ms
        }

        val decoyUnlockMs = medianOf3 {
            val ms = measureNanoTime {
                val res = vm.unlockWithPin("2222".toCharArray())
                assertTrue(res is UnlockResult.Success && res.vaultId == VaultId.DECOY)
            } / 1_000_000.0
            vm.lock()
            ms
        }

        val wrongUnlockMs = medianOf3 {
            measureNanoTime {
                val res = vm.unlockWithPin("9999".toCharArray())
                assertTrue(res is UnlockResult.WrongPin)
            } / 1_000_000.0
        }

        println("PARALLEL_TIMING singleKdfMs=${"%.2f".format(singleKdfMs)} primaryMs=${"%.2f".format(primaryUnlockMs)} decoyMs=${"%.2f".format(decoyUnlockMs)} wrongMs=${"%.2f".format(wrongUnlockMs)}")

        // Verify both run in parallel (each stays well below 2x sequential cost on multi-core CPU,
        // and Primary vs Decoy stay within tight wall-clock parity).
        val ratioPrimaryToDecoy = maxOf(primaryUnlockMs, decoyUnlockMs) / minOf(primaryUnlockMs, decoyUnlockMs).coerceAtLeast(1.0)
        assertTrue(
            "Primary (${primaryUnlockMs}ms) and Decoy (${decoyUnlockMs}ms) should take comparable wall time (ratio=$ratioPrimaryToDecoy)",
            ratioPrimaryToDecoy < 1.5
        )
        // If >=2 CPU cores are available, total wall time is noticeably less than sequential 2x
        if (Runtime.getRuntime().availableProcessors() >= 2) {
            assertTrue(
                "Parallel unlock ($wrongUnlockMs ms) should avoid 2x sequential wall time (~${2 * singleKdfMs} ms)",
                wrongUnlockMs < singleKdfMs * 1.75 + 25.0
            )
        }
    }

    @Test
    fun legacy600kVault_unlocksQuickly_rewrapsAtomicallyInBackground_andSurvivesMidRewrapFailure() =
        runTest(testDispatcher) {
            val legacyDir = tempFolder.newFolder("legacy_600k")
            // Step 1: Create legacy 600,000-iteration vault with diary content
            val legacyVm = VaultManager(
                baseDir = legacyDir,
                keyStoreProvider = ks,
                dispatcher = testDispatcher,
                pbkdf2Iterations = 600_000
            )
            legacyVm.ensurePrimaryVaultInitialized()
            legacyVm.setPrimaryPasscode("4321".toCharArray())
            legacyVm.withActiveDek { dek ->
                legacyVm.primaryDiaryRepo.saveDiary(
                    DiaryRecord(id = "d_600k", heading = "Legacy Diary", contentHtml = "<p>Keep safe</p>"),
                    dek
                )
            }
            assertEquals(600_000, legacyVm.envelopeV1.storedIterations())
            legacyVm.lock()

            // Step 2: Open with new calibrated VaultManager (target = 180,000)
            val modernVm = VaultManager(
                baseDir = legacyDir,
                keyStoreProvider = ks,
                dispatcher = testDispatcher,
                pbkdf2Iterations = null,
                targetIterationsProvider = { 180_000 }
            )
            val res1 = modernVm.unlockWithPin("4321".toCharArray())
            assertTrue(res1 is UnlockResult.Success)
            assertTrue("Should schedule background iteration rewrap", modernVm.hasPendingIterationRewrap())
            assertNull("Maintenance/rewrap must wait until background maintenance", modernVm.lastRecoveryReport)

            // Step 3: Simulate mid-rewrap failure before commit -> old 600k meta.dat stays completely intact!
            modernVm.runPostUnlockMaintenance(
                expectedVaultId = VaultId.PRIMARY,
                beforeRewrapCommitHook = { throw IOException("Simulated power loss before swap") }
            )
            assertEquals(
                "Aborted rewrap must preserve existing 600,000-iteration meta.dat intact",
                600_000,
                modernVm.envelopeV1.storedIterations()
            )
            modernVm.lock()

            // Step 4: Re-unlock with PIN -> verify still unlocks cleanly, then complete background rewrap normally
            val res2 = modernVm.unlockWithPin("4321".toCharArray())
            assertTrue(res2 is UnlockResult.Success)
            assertTrue(modernVm.hasPendingIterationRewrap())
            modernVm.runPostUnlockMaintenance(VaultId.PRIMARY)

            assertEquals(
                "Background rewrap should migrate meta.dat to calibrated 180,000 iterations",
                180_000,
                modernVm.envelopeV1.storedIterations()
            )
            modernVm.lock()

            // Step 5: Re-open vault in fresh VaultManager with 180k iterations -> same DEK decrypts existing diary!
            val verifiedVm = VaultManager(
                baseDir = legacyDir,
                keyStoreProvider = ks,
                dispatcher = testDispatcher,
                pbkdf2Iterations = null,
                targetIterationsProvider = { 180_000 }
            )
            val res3 = verifiedVm.unlockWithPin("4321".toCharArray())
            assertTrue(res3 is UnlockResult.Success)
            verifiedVm.withActiveDek { dek ->
                val loaded = verifiedVm.primaryDiaryRepo.loadDiary("d_600k", dek).record
                assertEquals("Legacy Diary", loaded.heading)
                assertEquals("<p>Keep safe</p>", loaded.contentHtml)
            }
        }

    @Test
    fun passcodeAttemptController_showsLoaderOnlyOver150ms_behavesIdenticallyForPrimaryAndDecoy_andCancelsStale() =
        runTest {
            val stdDispatcher = StandardTestDispatcher(testScheduler)

            // 1. Fast (<150ms) unlock: loader must NEVER turn true
            val fastOutcomes = mutableListOf<UnlockResult>()
            val fastSession = com.likkho.app.storage.VaultSession(VaultId.PRIMARY, ByteArray(32))
            val fastCtrl = PasscodeAttemptController(
                scope = kotlinx.coroutines.CoroutineScope(stdDispatcher),
                loaderDelayMs = 150L
            ) {
                delay(80)
                UnlockResult.Success(VaultId.PRIMARY, fastSession)
            }
            fastCtrl.submit("1234") { fastOutcomes.add(it) }
            advanceTimeBy(70); runCurrent()
            assertFalse("Fast unlock <150ms must not show loader", fastCtrl.showLoader.value)
            advanceTimeBy(20); runCurrent()
            assertFalse(fastCtrl.showLoader.value)
            assertEquals(1, fastOutcomes.size)

            // 2. Slow (>150ms) unlock: Primary & Decoy show identical loader state progression
            for (vaultId in listOf(VaultId.PRIMARY, VaultId.DECOY)) {
                val slowOutcomes = mutableListOf<UnlockResult>()
                val sess = com.likkho.app.storage.VaultSession(vaultId, ByteArray(32))
                val ctrl = PasscodeAttemptController(
                    scope = kotlinx.coroutines.CoroutineScope(stdDispatcher),
                    loaderDelayMs = 150L
                ) {
                    delay(300)
                    UnlockResult.Success(vaultId, sess)
                }
                ctrl.submit("1234") { slowOutcomes.add(it) }
                advanceTimeBy(140); runCurrent()
                assertFalse("Loader must stay hidden before 150ms for $vaultId", ctrl.showLoader.value)

                advanceTimeBy(15); runCurrent()
                assertTrue("Loader must appear once past 150ms for $vaultId", ctrl.showLoader.value)

                advanceTimeBy(160); runCurrent()
                assertFalse("Loader must hide after completion for $vaultId", ctrl.showLoader.value)
                assertEquals(1, slowOutcomes.size)
            }

            // 3. Stale attempt cancellation when user types new digit or presses backspace
            val staleOutcomes = mutableListOf<UnlockResult>()
            val ignoredSession = com.likkho.app.storage.VaultSession(VaultId.PRIMARY, ByteArray(32))
            val cancelCtrl = PasscodeAttemptController(
                scope = kotlinx.coroutines.CoroutineScope(stdDispatcher),
                loaderDelayMs = 150L
            ) { pin ->
                if (pin == "1234") {
                    // Simulate slow attempt that refuses/delays completion
                    kotlinx.coroutines.delay(250)
                    UnlockResult.Success(VaultId.PRIMARY, ignoredSession)
                } else {
                    kotlinx.coroutines.delay(40)
                    UnlockResult.WrongPin(0, isMaxApplicableLength = true)
                }
            }
            cancelCtrl.submit("1234") { staleOutcomes.add(it) }
            advanceTimeBy(100); runCurrent()
            // User presses backspace / extra digit before 1234 finishes
            cancelCtrl.cancelCurrent()
            assertFalse(cancelCtrl.isVerifying.value)
            assertFalse(cancelCtrl.showLoader.value)

            advanceTimeBy(300); runCurrent()
            assertTrue("Cancelled stale attempt must NEVER deliver unlock outcome", staleOutcomes.isEmpty())
        }

    @Test
    fun realBiometrics_enableDisablePersistence_impossibleWithoutPrompt_andInvalidatedKeyFallbackToPin() =
        runTest(testDispatcher) {
            val bioDir = tempFolder.newFolder("bio_vault")
            val vm1 = VaultManager(
                baseDir = bioDir,
                keyStoreProvider = ks,
                dispatcher = testDispatcher,
                pbkdf2Iterations = 5000
            )
            vm1.ensurePrimaryVaultInitialized()
            vm1.setPrimaryPasscode("2468".toCharArray())
            assertFalse(vm1.hasBiometric(VaultId.PRIMARY))

            // 1. Enable biometric via authenticated ENCRYPT Cipher (CryptoObject) using active session DEK (no PIN asked)
            val encCipher = vm1.prepareBiometricEncryptCipher()
            vm1.enableBiometricWithCipher(encCipher)
            assertTrue(vm1.hasBiometric(VaultId.PRIMARY))
            vm1.lock()

            // 2. Persistence across fresh VaultManager instance
            val vm2 = VaultManager(
                baseDir = bioDir,
                keyStoreProvider = ks,
                dispatcher = testDispatcher,
                pbkdf2Iterations = 5000
            )
            assertTrue("Biometric flag must persist across restart", vm2.hasBiometric(VaultId.PRIMARY))

            // 3. Impossible to bypass BiometricPrompt via direct KeyStore decrypt
            var directBlocked = false
            try {
                ks.decrypt(vm2.envelopeV1.biometricKeystoreAlias, ByteArray(48), "aad".toByteArray())
            } catch (_: SecurityException) {
                directBlocked = true
            }
            assertTrue("Direct KeyStore decrypt must throw SecurityException", directBlocked)

            // 4. Cancelled BiometricPrompt returns Cancelled without unlocking or adding wrong-PIN cooldown
            val cancelRes = vm2.unlockWithBiometricPrompt { _ -> null }
            assertTrue(cancelRes is UnlockResult.Cancelled)
            assertFalse(vm2.isUnlocked)
            assertEquals(0, vm2.wrongPinCounter.remainingCooldownSeconds())

            // 5. Real CryptoObject DECRYPT unlock succeeds
            val okRes = vm2.unlockWithBiometricPrompt { decryptCipher -> decryptCipher }
            assertTrue(okRes is UnlockResult.Success && okRes.vaultId == VaultId.PRIMARY)
            assertTrue(vm2.isUnlocked)

            // 6. Disable deletes Keystore key and wrapped DEK copy
            vm2.disableBiometric()
            assertFalse(vm2.hasBiometric(VaultId.PRIMARY))
            vm2.lock()
            val afterDisableRes = vm2.unlockWithBiometricPrompt { it }
            assertTrue(afterDisableRes is UnlockResult.Error)

            // 7. Re-enable, then simulate new biometric enrolled (KeyPermanentlyInvalidatedException)
            assertTrue(vm2.unlockWithPin("2468".toCharArray()) is UnlockResult.Success)
            vm2.enableBiometricWithCipher(vm2.prepareBiometricEncryptCipher())
            assertTrue(vm2.hasBiometric(VaultId.PRIMARY))
            vm2.lock()

            ks.invalidateBiometricKey(vm2.envelopeV1.biometricKeystoreAlias)
            val invalidRes = vm2.unlockWithBiometricPrompt { it }
            assertTrue(
                "Invalidated biometric key must return BiometricInvalidated, got $invalidRes",
                invalidRes is UnlockResult.BiometricInvalidated
            )
            assertEquals(
                "Biometrics changed. Unlock with PIN and enable again.",
                (invalidRes as UnlockResult.BiometricInvalidated).message
            )
            assertFalse("Invalidation must clear biometric flag & key", vm2.hasBiometric(VaultId.PRIMARY))

            // PIN unlock continues working seamlessly after biometric invalidation
            val pinFallback = vm2.unlockWithPin("2468".toCharArray())
            assertTrue(pinFallback is UnlockResult.Success && pinFallback.vaultId == VaultId.PRIMARY)

            // 8. Removing passcode also deletes biometric key & wrapped copy
            vm2.enableBiometricWithCipher(vm2.prepareBiometricEncryptCipher())
            assertTrue(vm2.hasBiometric(VaultId.PRIMARY))
            vm2.removePrimaryPasscode()
            assertFalse(vm2.hasPasscode(VaultId.PRIMARY))
            assertFalse("Removing passcode must delete biometric wrapper", vm2.hasBiometric(VaultId.PRIMARY))
        }
}
