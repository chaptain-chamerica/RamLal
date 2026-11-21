package com.likkho.app.storage

import com.likkho.app.CanvasAudio
import com.likkho.app.CanvasImage
import com.likkho.app.CanvasVideo
import com.likkho.app.DiaryLog
import com.likkho.app.ExportFormat
import com.likkho.app.RealFeatureHooks
import com.likkho.app.collectCanvasMediaIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class StorageBloatEndToEndTest {

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
    fun endToEnd_mediaDelete_diaryDelete_backupCap_crashTempRecovery_andBeforeAfterMetrics() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        // 1. Insert heavy image (2 MB), audio (3 MB), video (5 MB) + save
        val imgBytes = ByteArray(2 * 1024 * 1024) { (it * 3).toByte() }
        val audBytes = ByteArray(3 * 1024 * 1024) { (it * 5).toByte() }
        val vidBytes = ByteArray(5 * 1024 * 1024) { (it * 7).toByte() }

        val (imgId, audId, vidId) = vaultManager.withActiveDek { dek ->
            val mediaRepo = vaultManager.activeMediaRepository()
            Triple(
                mediaRepo.importMedia("heavy_img", ByteArrayInputStream(imgBytes), dek, imgBytes.size.toLong()),
                mediaRepo.importMedia("heavy_aud", ByteArrayInputStream(audBytes), dek, audBytes.size.toLong()),
                mediaRepo.importMedia("heavy_vid", ByteArrayInputStream(vidBytes), dek, vidBytes.size.toLong())
            )
        }

        val diaryWithHeavyMedia = DiaryLog(
            id = "d_heavy_canvas",
            heading = "Heavy Canvas Diary",
            contentHtml = "<p>Test heavy media cleanup</p>",
            plainPreview = "Test heavy media cleanup",
            createdAt = 1000L,
            updatedAt = 1000L,
            canvasImages = listOf(CanvasImage(id = imgId, source = imgId, sizeBytes = imgBytes.size.toLong())),
            audioAttachments = listOf(CanvasAudio(id = audId, name = "clip.m4a", audioUri = audId, durationSec = 25)),
            videoAttachments = listOf(CanvasVideo(id = vidId, name = "vid.mp4", source = vidId, sizeBytes = vidBytes.size.toLong()))
        )
        hooks.persistLogs(listOf(diaryWithHeavyMedia))

        val mediaRepo = vaultManager.activeMediaRepository()
        assertTrue(mediaRepo.exists(imgId))
        assertTrue(mediaRepo.exists(audId))
        assertTrue(mediaRepo.exists(vidId))

        // Delete image, audio, video from canvas and save -> obsoleteMediaIds must be removed from disk
        val savedIdsBeforeEdit = collectCanvasMediaIds(
            diaryWithHeavyMedia.pfp,
            diaryWithHeavyMedia.canvasBgSource,
            diaryWithHeavyMedia.canvasImages,
            diaryWithHeavyMedia.audioAttachments,
            diaryWithHeavyMedia.videoAttachments
        )
        val editedDiary = diaryWithHeavyMedia.copy(
            updatedAt = 2000L,
            canvasImages = emptyList(),
            audioAttachments = emptyList(),
            videoAttachments = emptyList()
        )
        val savedIdsAfterEdit = collectCanvasMediaIds(
            editedDiary.pfp,
            editedDiary.canvasBgSource,
            editedDiary.canvasImages,
            editedDiary.audioAttachments,
            editedDiary.videoAttachments
        )
        val obsolete = savedIdsBeforeEdit - savedIdsAfterEdit
        hooks.persistLogs(listOf(editedDiary), obsoleteMediaIds = obsolete)

        assertFalse("Removed image must be deleted from disk", mediaRepo.exists(imgId))
        assertFalse("Removed audio must be deleted from disk", mediaRepo.exists(audId))
        assertFalse("Removed video must be deleted from disk", mediaRepo.exists(vidId))

        // 2. Insert media into another diary, save, then delete diary -> media deleted from disk
        val d2Media = vaultManager.withActiveDek { dek ->
            mediaRepo.importMedia("d2_vid", ByteArrayInputStream(ByteArray(2 * 1024 * 1024) { 9 }), dek, 2L * 1024 * 1024)
        }
        val d2 = DiaryLog(
            id = "d_delete_me",
            heading = "Delete Diary Entry",
            updatedAt = 3000L,
            videoAttachments = listOf(CanvasVideo(id = d2Media, name = "d2.mp4", source = d2Media, sizeBytes = 2L * 1024 * 1024))
        )
        hooks.persistLogs(listOf(editedDiary, d2))
        assertTrue(mediaRepo.exists(d2Media))

        hooks.deleteDiary("d_delete_me")
        assertFalse(File(vaultManager.activeDiaryRepository().diariesDir, "d_delete_me.lkd").exists())
        assertFalse("Media of deleted diary must be deleted from disk", mediaRepo.exists(d2Media))

        // Keep one small legitimate diary for realistic baseline comparison
        val activeImgId = vaultManager.withActiveDek { dek ->
            mediaRepo.importMedia("active_keep", ByteArrayInputStream(ByteArray(512 * 1024) { 4 }), dek, 512L * 1024)
        }
        hooks.persistLogs(
            listOf(
                editedDiary.copy(
                    updatedAt = 4000L,
                    canvasImages = listOf(CanvasImage(id = activeImgId, source = activeImgId, sizeBytes = 512L * 1024))
                )
            )
        )

        // 3. Create 3 backups -> only latest 1 .lkb remains
        val pass = "StrongBackupPass1!"
        hooks.performBackup(pass)
        Thread.sleep(5)
        hooks.performBackup(pass)
        Thread.sleep(5)
        hooks.performBackup(pass)

        val backupsDir = File(filesDir, "backups/a")
        val remainingLkbs = backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray()
        assertEquals("Only latest 1 .lkb must remain after 3 backups", 1, remainingLkbs.size)

        hooks.exportDiary(editedDiary, ExportFormat("txt", ".txt", "Text"))

        // Simulate BEFORE-fix legacy bloat to measure Before vs After report cleanly:
        // - extra duplicate backups, legacy filesDir exports & shares, stale vaults/tmp snapshots, orphan media
        val orphanBlobId = vaultManager.withActiveDek { dek ->
            mediaRepo.importMedia("orphan_bloat", ByteArrayInputStream(ByteArray(4 * 1024 * 1024) { 11 }), dek, 4L * 1024 * 1024)
        }
        File(backupsDir, "legacy_old_1.lkb").apply {
            writeBytes(ByteArray(3 * 1024 * 1024))
            setLastModified(1_000_000L)
        }
        File(backupsDir, "legacy_old_2.lkb").apply {
            writeBytes(ByteArray(3 * 1024 * 1024))
            setLastModified(2_000_000L)
        }
        File(File(filesDir, "exports").apply { mkdirs() }, "stale_all.zip").writeBytes(ByteArray(2 * 1024 * 1024))
        File(File(filesDir, "shares").apply { mkdirs() }, "stale_share.likkho").writeBytes(ByteArray(1 * 1024 * 1024))
        File(vaultManager.backupEngine.tempDir, "vault_snapshot_crash").apply { mkdirs() }
            .resolve("snapshot_media.bin").writeBytes(ByteArray(4 * 1024 * 1024))
        File(vaultManager.backupEngine.tempDir, "restore_stage_crash").apply { mkdirs() }
            .resolve("stage.bin").writeBytes(ByteArray(2 * 1024 * 1024))

        val beforeReport = StorageMetrics.measureStorageBreakdown(filesDir, vaultsDir, cacheDir)

        // 4. Simulate crash restart cleanup +Settings "Free up space"
        hooks.runStartupStorageCleanup()
        vaultManager.lock()
        assertTrue(vaultManager.unlockNoPasscode() is UnlockResult.Success)
        val freedBytes = hooks.freeUpSpace()

        val afterReport = StorageMetrics.measureStorageBreakdown(filesDir, vaultsDir, cacheDir)

        println("BEFORE:\n${beforeReport.formatReport()}")
        println("AFTER:\n${afterReport.formatReport()}")
        println("Freed by Free up space (after startup+unlock): ${String.format(Locale.US, "%.2f", freedBytes / (1024.0 * 1024.0))} MB")

        assertEquals(0L, afterReport.vaultTmpBytes)
        assertEquals(0L, afterReport.exportsBytes)
        assertEquals(0L, afterReport.sharesBytes)
        assertEquals(0L, afterReport.cacheExportsBytes)
        assertEquals(0L, afterReport.cacheSharesBytes)
        assertFalse(mediaRepo.exists(orphanBlobId))
        assertTrue(mediaRepo.exists(activeImgId))
        assertTrue(afterReport.backupsBytes < beforeReport.backupsBytes)
        assertTrue(afterReport.filesDirBytes < beforeReport.filesDirBytes)
        assertEquals(1, backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".lkb") }?.size ?: 0)
    }
}
