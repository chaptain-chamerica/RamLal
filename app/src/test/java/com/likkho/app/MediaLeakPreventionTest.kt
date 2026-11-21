package com.likkho.app

import com.likkho.app.storage.ClockProvider
import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.EncryptedMediaRepository
import com.likkho.app.storage.InMemoryKeyStoreProvider
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MediaLeakPreventionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var vaultsDir: File
    private lateinit var cacheDir: File
    private lateinit var vaultManager: VaultManager
    private lateinit var hooks: RealFeatureHooks
    private val testDispatcher = UnconfinedTestDispatcher()

    private var wallNow = 1_700_000_000_000L
    private val clock = object : ClockProvider {
        override fun elapsedRealtimeMs(): Long = 10_000L
        override fun wallClockMs(): Long = wallNow
    }

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("app_files")
        vaultsDir = File(filesDir, "vaults")
        cacheDir = tempFolder.newFolder("app_cache")

        vaultManager = VaultManager(
            baseDir = vaultsDir,
            keyStoreProvider = InMemoryKeyStoreProvider(),
            dispatcher = testDispatcher,
            pbkdf2Iterations = 5000,
            clockProvider = clock
        )
        hooks = RealFeatureHooks(
            context = null,
            vaultManager = vaultManager,
            dispatcher = testDispatcher,
            scope = CoroutineScope(testDispatcher),
            baseStorageDir = filesDir,
            cacheStorageDir = cacheDir
        )
    }

    @Test
    fun scenario1_unsavedVideoAfterProcessKill_deletedImmediatelyOnNextUnlockWithout24hWait() =
        runTest(testDispatcher) {
            vaultManager.ensurePrimaryVaultInitialized()

            val videoPayload = ByteArray(512 * 1024) { (it * 13).toByte() }
            val leakedVidId = vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMedia(
                    mediaId = "vid_kill_leak",
                    inputStream = ByteArrayInputStream(videoPayload),
                    dek = dek,
                    estimatedSizeBytes = videoPayload.size.toLong()
                )
            }
            vaultManager.recordSessionImportedMedia(leakedVidId)
            assertTrue(vaultManager.activeMediaRepository().exists(leakedVidId))

            // Simulate OS killing the app process only 10 seconds later (far less than 24h)
            val relaunchedVm = VaultManager(
                baseDir = vaultsDir,
                keyStoreProvider = vaultManager.keyStoreProvider,
                dispatcher = testDispatcher,
                pbkdf2Iterations = 5000,
                clockProvider = clock,
                processStartTimeMs = System.currentTimeMillis() + 10_000L
            )
            wallNow += 10_000L

            val res = relaunchedVm.unlockNoPasscode()
            assertTrue(res is com.likkho.app.storage.UnlockResult.Success)
            relaunchedVm.runPostUnlockMaintenance(com.likkho.app.storage.VaultId.PRIMARY)
            assertFalse(
                "Aborted video from killed process must be deleted after post-unlock maintenance",
                relaunchedVm.primaryMediaRepo.exists(leakedVidId)
            )
            assertTrue(
                "No orphan media should remain in media/",
                relaunchedVm.primaryMediaRepo.listMediaIdsOnDisk().isEmpty()
            )
        }

    @Test
    fun scenario2_cancelMidImportAndPostEncryptRace_leavesZeroMediaFiles() =
        runTest(testDispatcher) {
            vaultManager.ensurePrimaryVaultInitialized()
            val mediaRepo = vaultManager.activeMediaRepository()

            // Part A: Mid-stream cancellation cleans .tmp and never produces .lkm
            for (kind in ImportingMediaKind.entries) {
                val cancelled = AtomicBoolean(false)
                val reads = AtomicInteger(0)
                val infiniteStream = object : InputStream() {
                    override fun read(): Int = 1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (reads.incrementAndGet() >= 3) cancelled.set(true)
                        b.fill(0x5A, off, off + len)
                        return len
                    }
                }
                val midId = "med_mid_${kind.name.lowercase()}"
                var caughtCancel = false
                try {
                    CountingInputStream(infiniteStream, 256L * 1024L, isCancelled = { cancelled.get() }).use { cs ->
                        vaultManager.withActiveDek { dek ->
                            mediaRepo.importMedia(midId, cs, dek, 256L * 1024L)
                        }
                    }
                } catch (_: ImportCancelledException) {
                    caughtCancel = true
                }
                assertTrue(caughtCancel)
                assertFalse(mediaRepo.exists(midId))
                assertFalse(File(mediaRepo.mediaDir, "$midId.lkm.tmp").exists())
            }

            // Part B: Race right after performImport completes -> handleCompletedImportOutcome deletes orphan
            val raceResults = listOf(
                CanvasVideo(id = "vid_race_1", name = "v.mp4", source = "vid_race_1"),
                CanvasImage(id = "img_race_1", source = "img_race_1"),
                CanvasAudio(id = "aud_race_1", name = "a.m4a", audioUri = "aud_race_1")
            )
            val payloads = mapOf(
                "vid_race_1" to ByteArray(32 * 1024) { 1 },
                "img_race_1" to ByteArray(16 * 1024) { 2 },
                "aud_race_1" to ByteArray(24 * 1024) { 3 }
            )
            for (item in raceResults) {
                val id = when (item) {
                    is CanvasVideo -> item.source
                    is CanvasImage -> item.id
                    is CanvasAudio -> item.audioUri!!
                    else -> ""
                }
                vaultManager.withActiveDek { dek ->
                    mediaRepo.importMediaBytes(id, payloads.getValue(id), dek)
                }
                vaultManager.recordSessionImportedMedia(id)
                assertTrue(mediaRepo.exists(id))

                var addedToCanvas = false
                handleCompletedImportOutcome(
                    result = item,
                    cancelled = true,
                    failMessage = "Import failed",
                    extractMediaId = { res ->
                        when (res) {
                            is CanvasVideo -> res.source
                            is CanvasImage -> res.id
                            is CanvasAudio -> res.audioUri
                            else -> null
                        }
                    },
                    onImported = { addedToCanvas = true },
                    onCleanupCancelledMedia = { orphanId ->
                        vaultManager.unprotectSessionMedia(setOf(orphanId))
                        hooks.deleteUnreferencedMedia(setOf(orphanId))
                    },
                    onFailStatus = {}
                )
                assertFalse("Cancelled race import must NOT be added to canvas", addedToCanvas)
                assertFalse("Cancelled race import file $id must be deleted from disk", mediaRepo.exists(id))
            }
            assertTrue(mediaRepo.listMediaIdsOnDisk().isEmpty())
        }

    @Test
    fun scenario3_concurrentUnlockCleanup_neverDeletesActiveImportTmp() {
        val repoDir = tempFolder.newFolder("concurrent_import_vault")
        val mediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, repoDir, clock)
        mediaRepo.ensureDirs()
        val dek = CryptoUtils.secureRandomBytes(32)

        val activeId = "med_streaming_active"
        val abandonedTmp = File(mediaRepo.mediaDir, "med_abandoned.lkm.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val activePayload = ByteArray(160 * 1024) { (it % 241).toByte() }

        val firstChunkWrittenLatch = CountDownLatch(1)
        val cleanupFinishedLatch = CountDownLatch(1)
        val tmpExistedDuringCleanup = AtomicBoolean(false)

        val slowStream = object : InputStream() {
            var offset = 0
            override fun read(): Int {
                if (offset >= activePayload.size) return -1
                return activePayload[offset++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (offset >= activePayload.size) return -1
                val chunk = minOf(len, minOf(16 * 1024, activePayload.size - offset))
                System.arraycopy(activePayload, offset, b, off, chunk)
                offset += chunk
                if (offset >= 32 * 1024) {
                    firstChunkWrittenLatch.countDown()
                    cleanupFinishedLatch.await(5, TimeUnit.SECONDS)
                }
                return chunk
            }
        }

        val importerError = arrayOfNulls<Throwable>(1)
        val importerThread = Thread {
            try {
                mediaRepo.importMedia(activeId, slowStream, dek, activePayload.size.toLong())
            } catch (t: Throwable) {
                importerError[0] = t
            }
        }
        importerThread.start()

        assertTrue(firstChunkWrittenLatch.await(5, TimeUnit.SECONDS))
        assertTrue(mediaRepo.isImportActive(activeId))
        val activeTmp = File(mediaRepo.mediaDir, "$activeId.lkm.tmp")
        assertTrue(activeTmp.exists())

        // Concurrent orphan cleanup runs while import is paused mid-stream
        mediaRepo.cleanupOrphans(referencedMediaIds = emptySet(), olderThanMs = 0L)
        tmpExistedDuringCleanup.set(activeTmp.exists())
        assertFalse("Abandoned .tmp without active import must still be cleaned", abandonedTmp.exists())
        cleanupFinishedLatch.countDown()

        importerThread.join(5000)
        assertEquals(null, importerError[0])
        assertTrue("Active .tmp must never be deleted during concurrent cleanup", tmpExistedDuringCleanup.get())
        assertTrue("Active import must finalize to .lkm after cleanup", mediaRepo.exists(activeId))
        assertArrayEquals(activePayload, mediaRepo.readDecryptedBytes(activeId, dek))
    }

    @Test
    fun scenario4_savedDiaryMedia_neverDeletedAcrossUnlockFreeUpSpaceOrSessionCleanup() =
        runTest(testDispatcher) {
            vaultManager.ensurePrimaryVaultInitialized()

            val imgId = vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMediaBytes("saved_img", ByteArray(24 * 1024) { 1 }, dek)
            }
            val vidId = vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMediaBytes("saved_vid", ByteArray(48 * 1024) { 2 }, dek)
            }
            val audId = vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMediaBytes("saved_aud", ByteArray(16 * 1024) { 3 }, dek)
            }
            val pfpId = vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMediaBytes("saved_pfp", ByteArray(8 * 1024) { 4 }, dek)
            }
            val bgId = vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMediaBytes("saved_bg", ByteArray(12 * 1024) { 5 }, dek)
            }
            val allSaved = setOf(imgId, vidId, audId, pfpId, bgId)
            allSaved.forEach { vaultManager.recordSessionImportedMedia(it) }

            val diary = DiaryLog(
                id = "saved_full_diary",
                heading = "Important Saved Diary",
                pfp = Pfp(source = pfpId),
                canvasBgSource = bgId,
                canvasImages = listOf(CanvasImage(id = imgId, source = imgId, width = 160f, height = 120f)),
                audioAttachments = listOf(CanvasAudio(id = audId, name = "note.m4a", audioUri = audId)),
                videoAttachments = listOf(CanvasVideo(id = vidId, name = "clip.mp4", source = vidId)),
                updatedAt = 2_000L
            )
            hooks.persistLogs(listOf(diary))

            // Call all cleanup pathways aggressively
            hooks.deleteUnreferencedMedia(allSaved)
            hooks.freeUpSpace()
            vaultManager.lock()

            val reopened = VaultManager(
                baseDir = vaultsDir,
                keyStoreProvider = vaultManager.keyStoreProvider,
                dispatcher = testDispatcher,
                pbkdf2Iterations = 5000,
                clockProvider = clock,
                processStartTimeMs = System.currentTimeMillis() + 500_000L
            )
            assertTrue(reopened.unlockNoPasscode() is com.likkho.app.storage.UnlockResult.Success)
            allSaved.forEach { id ->
                assertTrue("Saved diary media $id must ALWAYS remain on disk", reopened.primaryMediaRepo.exists(id))
            }
        }

    @Test
    fun scenario5_workspaceDisposeAndExit_idempotentCleanup_andSaveRecompositionSafe() =
        runTest(testDispatcher) {
            vaultManager.ensurePrimaryVaultInitialized()
            val mediaRepo = vaultManager.activeMediaRepository()

            val savedImg = vaultManager.withActiveDek { dek ->
                mediaRepo.importMediaBytes("dispose_saved_img", ByteArray(10 * 1024) { 1 }, dek)
            }
            val unsavedVid = vaultManager.withActiveDek { dek ->
                mediaRepo.importMediaBytes("dispose_unsaved_vid", ByteArray(32 * 1024) { 2 }, dek)
            }
            val sessionImported = mutableListOf(savedImg, unsavedVid)
            var lastSaved = setOf(savedImg)

            // 1. Save recomposition case: when user just saved, sessionImported was cleared and lastSaved contains savedImg -> cleanup must do nothing
            val savedDuringSession = mutableListOf(savedImg)
            val saveCleanup = cleanupAbandonedSessionMedia(
                sessionImportedMediaIds = savedDuringSession.apply { clear() },
                savedMediaIds = lastSaved,
                onCleanup = { hooks.deleteUnreferencedMedia(it) }
            )
            assertTrue(saveCleanup.isEmpty())
            assertTrue(mediaRepo.exists(savedImg))

            // 2. Exit + DisposableEffect.onDispose firing back-to-back must be idempotent and only delete unsavedVid
            val callbackCalls = mutableListOf<Set<String>>()
            val firstDiscard = cleanupAbandonedSessionMedia(
                sessionImportedMediaIds = sessionImported,
                savedMediaIds = lastSaved,
                onCleanup = { ids ->
                    callbackCalls.add(ids)
                    hooks.deleteUnreferencedMedia(ids)
                }
            )
            val secondDiscardOnDispose = cleanupAbandonedSessionMedia(
                sessionImportedMediaIds = sessionImported,
                savedMediaIds = lastSaved,
                onCleanup = { ids ->
                    callbackCalls.add(ids)
                    hooks.deleteUnreferencedMedia(ids)
                }
            )

            assertEquals(setOf(unsavedVid), firstDiscard)
            assertTrue("Second dispose call must be a no-op (idempotent)", secondDiscardOnDispose.isEmpty())
            assertEquals(1, callbackCalls.size)
            assertFalse("Unsaved session media must be deleted on dispose/exit", mediaRepo.exists(unsavedVid))
            assertTrue("Saved media must never be deleted on dispose/exit", mediaRepo.exists(savedImg))
        }

    @Test
    fun scenario6_eightIterationsInsertAndExitWithoutSave_leavesZeroOrphansInMediaDir() =
        runTest(testDispatcher) {
            vaultManager.ensurePrimaryVaultInitialized()
            val mediaRepo = vaultManager.activeMediaRepository()

            // Create 1 pre-existing saved diary whose media must survive all 8 abandoned sessions
            val permanentImg = vaultManager.withActiveDek { dek ->
                mediaRepo.importMediaBytes("perm_saved_img", ByteArray(8 * 1024) { 9 }, dek)
            }
            hooks.persistLogs(
                listOf(
                    DiaryLog(
                        id = "perm_diary",
                        heading = "Permanent",
                        canvasImages = listOf(CanvasImage(id = permanentImg, source = permanentImg))
                    )
                )
            )

            repeat(8) { round ->
                val sessionList = mutableListOf<String>()
                val vidId = vaultManager.withActiveDek { dek ->
                    mediaRepo.importMediaBytes("loop_vid_$round", ByteArray(32 * 1024) { round.toByte() }, dek)
                }
                val imgId = vaultManager.withActiveDek { dek ->
                    mediaRepo.importMediaBytes("loop_img_$round", ByteArray(16 * 1024) { round.toByte() }, dek)
                }
                val audId = vaultManager.withActiveDek { dek ->
                    mediaRepo.importMediaBytes("loop_aud_$round", ByteArray(12 * 1024) { round.toByte() }, dek)
                }
                listOf(vidId, imgId, audId).forEach { id ->
                    vaultManager.recordSessionImportedMedia(id)
                    sessionList.add(id)
                }

                // Exit without saving
                cleanupAbandonedSessionMedia(
                    sessionImportedMediaIds = sessionList,
                    savedMediaIds = emptySet(),
                    onCleanup = { discarded ->
                        vaultManager.unprotectSessionMedia(discarded)
                        hooks.deleteUnreferencedMedia(discarded)
                    }
                )
            }

            assertEquals(
                "After 8 insert-and-exit cycles, only the saved diary media may remain in media/",
                setOf(permanentImg),
                mediaRepo.listMediaIdsOnDisk()
            )
            assertTrue(
                "pending_session_imports.json must have 0 leftover entries",
                mediaRepo.loadPendingSessionImports().isEmpty()
            )
        }
}
