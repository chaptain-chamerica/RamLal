package com.likkho.app.storage

import com.likkho.app.RealFeatureHooks
import com.likkho.app.storage.model.CanvasImageRecord
import com.likkho.app.storage.model.DiaryRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.system.measureNanoTime

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockBaselineBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun measureBaselineTimings() = runTest {
        val salt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val pin = "123456".toCharArray()

        // Warmup JVM JIT slightly
        CryptoUtils.zeroize(CryptoUtils.deriveKeyPbkdf2(pin, salt, 10_000))

        val t100kMs = measureNanoTime {
            CryptoUtils.zeroize(CryptoUtils.deriveKeyPbkdf2(pin, salt, 100_000))
        } / 1_000_000.0
        val t200kMs = measureNanoTime {
            CryptoUtils.zeroize(CryptoUtils.deriveKeyPbkdf2(pin, salt, 200_000))
        } / 1_000_000.0
        val t600kMs = measureNanoTime {
            CryptoUtils.zeroize(CryptoUtils.deriveKeyPbkdf2(pin, salt, 600_000))
        } / 1_000_000.0

        val baseDir = tempFolder.newFolder("bench_vaults")
        val ks = InMemoryKeyStoreProvider()
        val dispatcher = UnconfinedTestDispatcher()
        val vm = VaultManager(
            baseDir = baseDir,
            keyStoreProvider = ks,
            dispatcher = dispatcher,
            pbkdf2Iterations = 600_000
        )
        vm.ensurePrimaryVaultInitialized()
        vm.setPrimaryPasscode(pin)

        // Populate 40 realistic diaries + 20 media files
        val diaryCount = 40
        vm.withActiveDek { dek ->
            val mediaRepo = vm.primaryMediaRepo
            val diaryRepo = vm.primaryDiaryRepo
            val body = "<p>" + "Sample encrypted diary content line for benchmark testing. ".repeat(30) + "</p>"
            for (i in 1..diaryCount) {
                val imgId = if (i <= 20) {
                    mediaRepo.importMediaBytes("bench_img_$i", ByteArray(16 * 1024) { i.toByte() }, dek)
                } else null
                diaryRepo.saveDiary(
                    DiaryRecord(
                        id = "bench_diary_$i",
                        heading = "Diary Entry $i",
                        createdAt = 1_700_000_000_000L + i * 1000L,
                        updatedAt = 1_700_000_000_000L + i * 1000L,
                        plainPreview = "Preview for entry $i",
                        contentHtml = body,
                        images = if (imgId != null) listOf(CanvasImageRecord(mediaId = imgId)) else emptyList()
                    ),
                    dek
                )
            }
        }

        // Measure Keystore unwrap alone
        val sampleDek = CryptoUtils.secureRandomBytes(32)
        val ksWrapped = ks.encrypt("bench_ks", sampleDek, "aad".toByteArray())
        val tKsUnwrapMs = measureNanoTime {
            CryptoUtils.zeroize(ks.decrypt("bench_ks", ksWrapped, "aad".toByteArray()))
        } / 1_000_000.0

        // Measure runUnlockMaintenance equivalents (scanAndRecover + cleanupVaultOrphans)
        val tScanAndRecoverMs = measureNanoTime {
            vm.withActiveDek { dek ->
                vm.primaryDiaryRepo.scanAndRecover(dek, dekVerified = true)
            }
        } / 1_000_000.0
        val tOrphanCleanupMs = measureNanoTime {
            vm.withActiveDek { dek ->
                vm.cleanupVaultOrphans(VaultId.PRIMARY, dek)
            }
        } / 1_000_000.0

        // Measure loadAllDiaries & loadAllDiariesMapped & index-only listDiaries
        val tLoadAllDiariesMs = measureNanoTime {
            vm.withActiveDek { dek ->
                vm.primaryDiaryRepo.loadAllDiaries(dek)
            }
        } / 1_000_000.0
        val tLoadAllMappedMs = measureNanoTime {
            vm.withActiveDek { dek ->
                vm.primaryDiaryRepo.loadAllDiariesMapped(dek) { it.heading }
            }
        } / 1_000_000.0
        val tIndexListMs = measureNanoTime {
            vm.withActiveDek { dek ->
                vm.primaryDiaryRepo.listDiaries(dek)
            }
        } / 1_000_000.0

        // Measure current sequential PIN unlock (with decoy configured so wrong/decoy pays 2x)
        vm.setDecoyPasscode("654321".toCharArray(), pin)
        vm.lock()

        val tPrimaryPinUnlockMs = measureNanoTime {
            vm.unlockWithPin("123456".toCharArray())
        } / 1_000_000.0
        vm.lock()

        val tDecoyPinUnlockMs = measureNanoTime {
            vm.unlockWithPin("654321".toCharArray())
        } / 1_000_000.0
        vm.lock()

        val tWrongPinUnlockMs = measureNanoTime {
            vm.unlockWithPin("000000".toCharArray())
        } / 1_000_000.0

        // Cold start pre-lock-screen work simulation (startup cleanup + envelope check)
        val hooks = RealFeatureHooks(
            context = null,
            vaultManager = vm,
            dispatcher = dispatcher,
            scope = CoroutineScope(dispatcher),
            baseStorageDir = File(baseDir, "files"),
            cacheStorageDir = File(baseDir, "cache")
        )
        val tColdStartWithPinMs = measureNanoTime {
            hooks.runStartupStorageCleanup()
            vm.hasPasscode(VaultId.PRIMARY)
            vm.decoyExists() && vm.hasPasscode(VaultId.DECOY)
            vm.hasBiometric(VaultId.PRIMARY)
        } / 1_000_000.0

        println("STEP0_BASELINE_REPORT_START")
        println("PBKDF2_100k_ms=${"%.2f".format(t100kMs)}")
        println("PBKDF2_200k_ms=${"%.2f".format(t200kMs)}")
        println("PBKDF2_600k_ms=${"%.2f".format(t600kMs)}")
        println("Keystore_unwrap_software_ms=${"%.2f".format(tKsUnwrapMs)}")
        println("scanAndRecover_40diaries_ms=${"%.2f".format(tScanAndRecoverMs)}")
        println("orphanCleanup_20media_ms=${"%.2f".format(tOrphanCleanupMs)}")
        println("runUnlockMaintenance_total_ms=${"%.2f".format(tScanAndRecoverMs + tOrphanCleanupMs)}")
        println("loadAllDiaries_40_ms=${"%.2f".format(tLoadAllDiariesMs)}")
        println("loadAllDiariesMapped_40_ms=${"%.2f".format(tLoadAllMappedMs)}")
        println("indexOnlyListDiaries_40_ms=${"%.2f".format(tIndexListMs)}")
        println("unlockWithPin_primary_current_ms=${"%.2f".format(tPrimaryPinUnlockMs)}")
        println("unlockWithPin_decoy_current_ms=${"%.2f".format(tDecoyPinUnlockMs)}")
        println("unlockWithPin_wrong_current_ms=${"%.2f".format(tWrongPinUnlockMs)}")
        println("coldStart_preLockCheck_current_ms=${"%.2f".format(tColdStartWithPinMs)}")
        println("STEP0_BASELINE_REPORT_END")
    }

    @Test
    fun measurePostOptimizationTimings() = kotlinx.coroutines.runBlocking {
        val salt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val pin = "123456".toCharArray()
        CryptoUtils.zeroize(CryptoUtils.deriveKeyPbkdf2(pin, salt, 10_000))

        val calibratedIter = CryptoUtils.calibratePbkdf2Iterations()
        val baseDir = tempFolder.newFolder("after_vaults")
        val ks = InMemoryKeyStoreProvider()
        val vm = VaultManager(
            baseDir = baseDir,
            keyStoreProvider = ks,
            dispatcher = kotlinx.coroutines.Dispatchers.IO,
            pbkdf2Iterations = calibratedIter,
            kdfDispatcher = kotlinx.coroutines.Dispatchers.Default
        )
        vm.ensurePrimaryVaultInitialized()
        vm.setPrimaryPasscode(pin)

        // Populate same 40 realistic diaries + 20 media files + index.lki
        vm.withActiveDek { dek ->
            val mediaRepo = vm.primaryMediaRepo
            val diaryRepo = vm.primaryDiaryRepo
            val body = "<p>" + "Sample encrypted diary content line for benchmark testing. ".repeat(30) + "</p>"
            for (i in 1..40) {
                val imgId = if (i <= 20) {
                    mediaRepo.importMediaBytes("bench_img_$i", ByteArray(16 * 1024) { i.toByte() }, dek)
                } else null
                diaryRepo.saveDiary(
                    DiaryRecord(
                        id = "bench_diary_$i",
                        heading = "Diary Entry $i",
                        createdAt = 1_700_000_000_000L + i * 1000L,
                        updatedAt = 1_700_000_000_000L + i * 1000L,
                        plainPreview = "Preview for entry $i",
                        contentHtml = body,
                        images = if (imgId != null) listOf(CanvasImageRecord(mediaId = imgId)) else emptyList()
                    ),
                    dek
                )
            }
        }
        vm.enableBiometricWithCipher(vm.prepareBiometricEncryptCipher())
        vm.setDecoyPasscode("654321".toCharArray(), pin)
        vm.lock()

        val tColdHeaderMs = measureNanoTime {
            vm.envelopeV1.readHeaderFlags()
        } / 1_000_000.0

        val tPinPrimaryAfterMs = measureNanoTime {
            vm.unlockWithPin("123456".toCharArray())
        } / 1_000_000.0
        val tHomeIndexLoadMs = measureNanoTime {
            vm.withActiveDek { dek ->
                vm.primaryDiaryRepo.loadHomeEntries(dek, { it.heading }, { it.heading })
            }
        } / 1_000_000.0
        vm.lock()

        val tPinDecoyAfterMs = measureNanoTime {
            vm.unlockWithPin("654321".toCharArray())
        } / 1_000_000.0
        vm.lock()

        val tPinWrongAfterMs = measureNanoTime {
            vm.unlockWithPin("000000".toCharArray())
        }

        val tBioUnlockAfterMs = measureNanoTime {
            vm.unlockWithBiometricPrompt { it }
        } / 1_000_000.0

        println("AFTER_OPTIMIZATION_REPORT_START")
        println("calibrated_iterations=$calibratedIter")
        println("coldStart_12byteHeader_ms=${"%.2f".format(tColdHeaderMs)}")
        println("pinUnlock_primary_parallel_ms=${"%.2f".format(tPinPrimaryAfterMs)}")
        println("pinUnlock_decoy_parallel_ms=${"%.2f".format(tPinDecoyAfterMs)}")
        println("pinUnlock_wrong_parallel_ms=${"%.2f".format(tPinWrongAfterMs / 1_000_000.0)}")
        println("homeLoad_indexFast_40_ms=${"%.2f".format(tHomeIndexLoadMs)}")
        println("biometricUnlock_cryptoObject_software_ms=${"%.2f".format(tBioUnlockAfterMs)}")
        println("AFTER_OPTIMIZATION_REPORT_END")
    }
}
