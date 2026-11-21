package com.likkho.app

import com.likkho.app.storage.ClockProvider
import com.likkho.app.storage.InMemoryKeyStoreProvider
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.VaultManager
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class StoragePageAndIsolationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var vaultsDir: File
    private lateinit var cacheDir: File
    private lateinit var vaultManager: VaultManager
    private lateinit var hooks: RealFeatureHooks
    private val testDispatcher = UnconfinedTestDispatcher()

    private val clock = object : ClockProvider {
        var elapsed: Long = 10_000L
        var wall: Long = 1_700_000_000_000L
        override fun elapsedRealtimeMs(): Long = elapsed
        override fun wallClockMs(): Long = wall
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
    fun decoyAndPrimaryVaults_strictlyIsolateStorageReportsPreviewAndCleanup() = runTest(testDispatcher) {
        val primaryPin = "1111".toCharArray()
        val decoyPin = "2222".toCharArray()
        vaultManager.ensurePrimaryVaultInitialized()
        vaultManager.setPrimaryPasscode(primaryPin)
        vaultManager.setDecoyPasscode(decoyPin, primaryPin)

        // Populate PRIMARY vault with distinct files (photo, video, audio, orphan, quarantine, 2 backups)
        val (pPhotoId, pAudioId, pVideoId, pOrphanId) = vaultManager.withActiveDek { dek ->
            val m = vaultManager.primaryMediaRepo
            listOf(
                m.importMediaBytes("p_photo", ByteArray(20 * 1024) { 1 }, dek),
                m.importMediaBytes("p_audio", ByteArray(30 * 1024) { 2 }, dek),
                m.importMediaBytes("p_video", ByteArray(60 * 1024) { 3 }, dek),
                m.importMediaBytes("p_orphan", ByteArray(45 * 1024) { 4 }, dek)
            )
        }
        val pDiary = DiaryLog(
            id = "p_diary_secret",
            heading = "Primary Secret Diary",
            canvasImages = listOf(CanvasImage(id = pPhotoId, source = pPhotoId)),
            audioAttachments = listOf(CanvasAudio(id = pAudioId, name = "PrimaryNote.m4a", audioUri = pAudioId)),
            videoAttachments = listOf(CanvasVideo(id = pVideoId, name = "PrimaryClip.mp4", source = pVideoId)),
            updatedAt = 5_000L
        )
        hooks.persistLogs(listOf(pDiary))

        val pBackupOld = File(hooks.backupDirFor(VaultId.PRIMARY).apply { mkdirs() }, "p_old_100.lkb").apply {
            writeBytes(ByteArray(50 * 1024))
            setLastModified(1_000_000L)
        }
        val pBackupLatest = File(hooks.backupDirFor(VaultId.PRIMARY), "p_new_200.lkb").apply {
            writeBytes(ByteArray(40 * 1024))
            setLastModified(2_000_000L)
        }
        val pQuarantine = File(vaultManager.primaryDiaryRepo.quarantineDir.apply { mkdirs() }, "p_diary_secret_1000.lkd").apply {
            writeBytes(ByteArray(12 * 1024))
        }

        // Switch to DECOY vault and populate small distinct decoy data
        vaultManager.lock()
        assertTrue(vaultManager.unlockWithPin(decoyPin) is com.likkho.app.storage.UnlockResult.Success)

        val (dPhotoId, dOrphanId) = vaultManager.withActiveDek { dek ->
            val m = vaultManager.decoyMediaRepo
            listOf(
                m.importMediaBytes("d_photo", ByteArray(8 * 1024) { 5 }, dek),
                m.importMediaBytes("d_orphan", ByteArray(14 * 1024) { 6 }, dek)
            )
        }
        val dDiary = DiaryLog(
            id = "d_diary_1",
            heading = "Decoy Diary",
            canvasImages = listOf(CanvasImage(id = dPhotoId, source = dPhotoId)),
            updatedAt = 6_000L
        )
        hooks.persistLogs(listOf(dDiary))

        val dBackupOld = File(hooks.backupDirFor(VaultId.DECOY).apply { mkdirs() }, "d_old_100.lkb").apply {
            writeBytes(ByteArray(18 * 1024))
            setLastModified(1_000_000L)
        }
        val dBackupLatest = File(hooks.backupDirFor(VaultId.DECOY), "d_new_200.lkb").apply {
            writeBytes(ByteArray(16 * 1024))
            setLastModified(2_000_000L)
        }
        val dQuarantine = File(vaultManager.decoyDiaryRepo.quarantineDir.apply { mkdirs() }, "d_diary_1_1000.lkd").apply {
            writeBytes(ByteArray(4 * 1024))
        }

        // StorageReport in DECOY must show ONLY Decoy data (zero Primary counts, sizes, or names)
        val decoyReport = hooks.getStorageReport()
        assertEquals(1, decoyReport.diaries.count)
        assertEquals("Decoy Diary", decoyReport.diaries.items.single().name)
        assertEquals(1, decoyReport.photos.count)
        assertEquals(0, decoyReport.audio.count)
        assertEquals(0, decoyReport.videos.count)
        assertEquals(2, decoyReport.backups.count)
        assertEquals(setOf("d_new_200.lkb", "d_old_100.lkb"), decoyReport.backups.items.map { it.name }.toSet())
        assertEquals(1, decoyReport.unusedMedia.count)
        assertEquals(dOrphanId, decoyReport.unusedMedia.items.single().id)
        assertEquals(1, decoyReport.damagedCopies.count)

        val allDecoyLabels = buildString {
            listOf(
                decoyReport.diaries, decoyReport.photos, decoyReport.audio, decoyReport.videos,
                decoyReport.backups, decoyReport.olderBackups, decoyReport.temporaryFiles,
                decoyReport.unusedMedia, decoyReport.damagedCopies
            ).forEach { sec ->
                append(sec.title).append(' ')
                sec.items.forEach { append(it.id).append(' ').append(it.name).append(' ').append(it.detail).append(' ') }
            }
        }
        assertFalse(allDecoyLabels.contains("Primary", ignoreCase = true))
        assertFalse(allDecoyLabels.contains("decoy", ignoreCase = false)) // neutral folder names only
        assertFalse(allDecoyLabels.contains("p_old_100.lkb"))
        assertFalse(allDecoyLabels.contains("p_new_200.lkb"))
        assertFalse(allDecoyLabels.contains(pOrphanId))

        // Preview + execute Free up space in DECOY
        val decoyPreview = hooks.previewFreeUpSpace()
        assertEquals(listOf("d_old_100.lkb"), decoyPreview.deletedBackupNames)
        assertEquals("d_new_200.lkb", decoyPreview.keptBackupName)
        assertEquals(1, decoyPreview.unusedMediaCount)
        assertTrue(decoyPreview.items.none { it.filePath.contains(VaultId.PRIMARY.dirName) || it.filePath.contains("/a/") })

        val decoyFreed = hooks.executeFreeUpSpace(decoyPreview)
        assertEquals(decoyPreview.totalBytes, decoyFreed)
        assertFalse(dBackupOld.exists())
        assertFalse(vaultManager.decoyMediaRepo.exists(dOrphanId))
        assertTrue(dBackupLatest.exists())
        assertTrue(dQuarantine.exists())

        // Every PRIMARY file (including pOrphanId, pBackupOld, pBackupLatest, pQuarantine) MUST be untouched
        assertTrue(pBackupOld.exists())
        assertTrue(pBackupLatest.exists())
        assertTrue(pQuarantine.exists())
        assertTrue(vaultManager.primaryMediaRepo.exists(pPhotoId))
        assertTrue(vaultManager.primaryMediaRepo.exists(pAudioId))
        assertTrue(vaultManager.primaryMediaRepo.exists(pVideoId))
        assertTrue(vaultManager.primaryMediaRepo.exists(pOrphanId))

        // Deleting damaged copies in DECOY touches ONLY Decoy quarantine
        val deletedDecoyQuarantine = hooks.deleteDamagedCopies()
        assertTrue(deletedDecoyQuarantine > 0L)
        assertFalse(dQuarantine.exists())
        assertTrue("Primary quarantine must never be deleted from Decoy vault", pQuarantine.exists())

        // Symmetric check: switch back to PRIMARY -> verify Primary report & cleanup are unaffected by Decoy
        vaultManager.lock()
        assertTrue(vaultManager.unlockWithPin(primaryPin) is com.likkho.app.storage.UnlockResult.Success)
        val primaryReport = hooks.getStorageReport()
        assertEquals(1, primaryReport.diaries.count)
        assertEquals("Primary Secret Diary", primaryReport.diaries.items.single().name)
        assertEquals(1, primaryReport.photos.count)
        assertEquals(1, primaryReport.audio.count)
        assertEquals(1, primaryReport.videos.count)
        assertEquals(2, primaryReport.backups.count)
        assertEquals(1, primaryReport.unusedMedia.count)
        assertEquals(pOrphanId, primaryReport.unusedMedia.items.single().id)
        assertEquals(1, primaryReport.damagedCopies.count)
    }

    @Test
    fun previewAndExecute_matchIdentically_andHandleMissingAndPostPreviewFilesSafely() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val orphan1 = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMediaBytes("orphan_1", ByteArray(64 * 1024) { 7 }, dek)
        }
        val bDir = hooks.backupDirFor(VaultId.PRIMARY).apply { mkdirs() }
        val oldLkb = File(bDir, "backup_1000.lkb").apply {
            writeBytes(ByteArray(32 * 1024))
            setLastModified(1_000L)
        }
        val latestLkb = File(bDir, "backup_2000.lkb").apply {
            writeBytes(ByteArray(16 * 1024))
            setLastModified(2_000L)
        }
        val tempExport = File(hooks.exportCacheDir.apply { mkdirs() }, "temp_export.txt").apply {
            writeBytes(ByteArray(8 * 1024))
        }
        val tempStage = File(vaultManager.backupEngine.tempDir, "stage_leftover/data.bin").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(12 * 1024))
        }

        // 1. Preview deletes NOTHING
        val preview = hooks.previewFreeUpSpace()
        assertFalse(preview.isEmpty)
        assertEquals(listOf("backup_1000.lkb"), preview.deletedBackupNames)
        assertEquals("backup_2000.lkb", preview.keptBackupName)
        assertEquals(1, preview.unusedMediaCount)
        assertEquals(2, preview.temporaryFilesCount)
        assertTrue(vaultManager.activeMediaRepository().exists(orphan1))
        assertTrue(oldLkb.exists())
        assertTrue(latestLkb.exists())
        assertTrue(tempExport.exists())
        assertTrue(tempStage.exists())

        // Create a post-preview file that was NOT in the preview -> execute(preview) must NOT delete it
        val postPreviewOrphan = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMediaBytes("orphan_post_preview", ByteArray(10 * 1024) { 9 }, dek)
        }

        // 2. Execute deletes EXACT preview items
        val freed = hooks.executeFreeUpSpace(preview)
        assertEquals(preview.totalBytes, freed)
        assertFalse(vaultManager.activeMediaRepository().exists(orphan1))
        assertFalse(oldLkb.exists())
        assertFalse(tempExport.exists())
        assertFalse(tempStage.exists())
        assertTrue(latestLkb.exists())
        assertTrue("Files created after preview must not be deleted silently", vaultManager.activeMediaRepository().exists(postPreviewOrphan))

        // 3. Re-executing the stale preview (missing files) must NOT error and returns 0L
        val secondRunFreed = hooks.executeFreeUpSpace(preview)
        assertEquals(0L, secondRunFreed)
    }

    @Test
    fun protections_savedMediaSessionMediaActiveImportsSafBackupsQuarantineAndLegacyRootBackups_neverDeleted() =
        runTest(testDispatcher) {
            vaultManager.ensurePrimaryVaultInitialized()
            val mediaRepo = vaultManager.activeMediaRepository()

            // Saved diary media
            val savedImg = vaultManager.withActiveDek { dek ->
                mediaRepo.importMediaBytes("prot_saved_img", ByteArray(20 * 1024) { 1 }, dek)
            }
            hooks.persistLogs(
                listOf(
                    DiaryLog(
                        id = "saved_prot",
                        heading = "Keep Safe",
                        canvasImages = listOf(CanvasImage(id = savedImg, source = savedImg))
                    )
                )
            )

            // Current session protected media (not yet saved in diary)
            val sessionImg = vaultManager.withActiveDek { dek ->
                mediaRepo.importMediaBytes("prot_session_img", ByteArray(24 * 1024) { 2 }, dek)
            }
            vaultManager.recordSessionImportedMedia(sessionImg)

            // Active in-progress import (.lkm + .tmp)
            val activeTmpFile = File(mediaRepo.mediaDir.apply { mkdirs() }, "prot_active_imp.lkm.tmp").apply {
                writeBytes(ByteArray(16 * 1024))
            }

            // Latest backup + SAF backup outside app storage + quarantine + legacy shared backups/*.lkb
            val latestLkb = File(hooks.backupDirFor(VaultId.PRIMARY).apply { mkdirs() }, "latest.lkb").apply {
                writeBytes(ByteArray(28 * 1024))
            }
            val externalSafBackup = File(tempFolder.newFolder("user_saf_dir"), "my_saf_backup.lkb").apply {
                writeBytes(ByteArray(32 * 1024))
            }
            val quarantineFile = File(vaultManager.primaryDiaryRepo.quarantineDir.apply { mkdirs() }, "damaged_1.lkd").apply {
                writeBytes(ByteArray(10 * 1024))
            }
            val legacyShared1 = File(hooks.backupRootDir, "legacy_root_1.lkb").apply {
                writeBytes(ByteArray(22 * 1024))
                setLastModified(10_000L)
            }

            var freed = 0L
            mediaRepo.runWithActiveImportForTest("prot_active_imp") {
                val report = kotlinx.coroutines.runBlocking { hooks.getStorageReport() }
                assertEquals(1, report.olderBackups.count)
                assertEquals("legacy_root_1.lkb", report.olderBackups.items.single().name)
                assertEquals(1, report.damagedCopies.count)
                assertEquals(0, report.unusedMedia.count)
                assertEquals(0, report.temporaryFiles.count)

                val preview = kotlinx.coroutines.runBlocking { hooks.previewFreeUpSpace() }
                assertTrue("Nothing should be marked cleanable while only protected items exist", preview.isEmpty)
                assertEquals(0L, preview.totalBytes)

                freed = kotlinx.coroutines.runBlocking { hooks.executeFreeUpSpace(preview) }
            }

            assertEquals(0L, freed)
            assertTrue(mediaRepo.exists(savedImg))
            assertTrue(mediaRepo.exists(sessionImg))
            assertTrue(activeTmpFile.exists())
            assertTrue(latestLkb.exists())
            assertTrue(externalSafBackup.exists())
            assertTrue(quarantineFile.exists())
            assertTrue(legacyShared1.exists())

            // Legacy shared backups are ONLY removed when user explicitly confirms deleteOlderSharedBackups
            val freedLegacy = hooks.deleteOlderSharedBackups(listOf("legacy_root_1.lkb"))
            assertEquals(22L * 1024L, freedLegacy)
            assertFalse(legacyShared1.exists())
            assertTrue("Explicit legacy delete must NOT touch per-vault latest backup", latestLkb.exists())
        }

    @Test
    fun storageUiHelpers_formatCorrectlyAndBuildStackedBarSegmentsWithoutZeros() {
        assertEquals("0.00 MB", formatStorageMb(0L))
        assertEquals("0.00 MB", formatStorageMb(-50L))
        assertEquals("1.50 MB", formatStorageMb(1536L * 1024L))

        val emptyPreview = FreeUpPreview()
        assertEquals("Nothing to clean", storageFreeUpButtonLabel(emptyPreview))

        val nonEmptyPreview = FreeUpPreview(
            items = listOf(
                FreeUpPreviewItem(
                    id = "old_1",
                    name = "old_1.lkb",
                    categoryLabel = "Backups",
                    type = FreeUpItemType.OLD_BACKUP,
                    sizeBytes = 2L * 1024L * 1024L,
                    modifiedAtMs = 1000L,
                    filePath = "/tmp/old_1.lkb"
                )
            ),
            totalBytes = 2L * 1024L * 1024L
        )
        assertEquals("Free up space (2.00 MB)", storageFreeUpButtonLabel(nonEmptyPreview))

        val palette = StoragePalette(
            diaries = androidx.compose.ui.graphics.Color.Blue,
            photos = androidx.compose.ui.graphics.Color.Green,
            audio = androidx.compose.ui.graphics.Color.Magenta,
            videos = androidx.compose.ui.graphics.Color.Yellow,
            backups = androidx.compose.ui.graphics.Color.Cyan,
            temporary = androidx.compose.ui.graphics.Color.Gray,
            unused = androidx.compose.ui.graphics.Color.Red,
            damaged = androidx.compose.ui.graphics.Color.Black
        )
        val report = StorageReport(
            diaries = StorageSection(StorageCategory.DIARIES, "Diaries", 2, 1024L),
            photos = StorageSection(StorageCategory.PHOTOS, "Photos", 0, 0L),
            videos = StorageSection(StorageCategory.VIDEOS, "Videos", 1, 512L * 1024L),
            damagedCopies = StorageSection(StorageCategory.DAMAGED_COPIES, "Damaged diary copies", 1, 2048L)
        )
        val segments = buildStorageBarSegments(report, palette)
        assertEquals(3, segments.size)
        assertEquals(listOf(StorageCategory.DIARIES, StorageCategory.VIDEOS, StorageCategory.DAMAGED_COPIES), segments.map { it.category })
        assertEquals(listOf(androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Yellow, androidx.compose.ui.graphics.Color.Black), segments.map { it.color })
    }

    @Test
    fun unknownQuarantineFile_activatesSafetyGuard_protectsUnreferencedMediaUntilDamagedRemoved() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()
        val mediaRepo = vaultManager.activeMediaRepository()

        val orphanId = vaultManager.withActiveDek { dek ->
            mediaRepo.importMediaBytes("orphan_guarded", ByteArray(25 * 1024) { 3 }, dek)
        }
        // Unreadable quarantine file whose baseId is NOT in index.lki triggers hasUndecodableOrQuarantined
        val unknownQuarantine = File(vaultManager.primaryDiaryRepo.quarantineDir.apply { mkdirs() }, "unknown_corrupt_1000.lkd").apply {
            writeBytes(ByteArray(12 * 1024))
        }

        val guardedReport = hooks.getStorageReport()
        assertEquals(1, guardedReport.damagedCopies.count)
        assertEquals(0, guardedReport.unusedMedia.count)

        val guardedPreview = hooks.previewFreeUpSpace()
        assertEquals(0, guardedPreview.unusedMediaCount)
        assertTrue(guardedPreview.isEmpty)

        val freedFromClean = hooks.executeFreeUpSpace(guardedPreview)
        assertEquals(0L, freedFromClean)
        assertTrue("Unknown quarantine guard must protect unreferenced media", mediaRepo.exists(orphanId))
        assertTrue("Quarantine must NEVER be deleted by Free up space", unknownQuarantine.exists())

        // Explicit user delete of damaged copies removes quarantine file only
        val freedDamaged = hooks.deleteDamagedCopies()
        assertEquals(12L * 1024L, freedDamaged)
        assertFalse(unknownQuarantine.exists())
        assertTrue("deleteDamagedCopies must not touch media files directly", mediaRepo.exists(orphanId))

        // Once unknown quarantine is resolved, orphan media becomes cleanable
        val unlockedReport = hooks.getStorageReport()
        assertEquals(0, unlockedReport.damagedCopies.count)
        assertEquals(1, unlockedReport.unusedMedia.count)
        val cleanPreview = hooks.previewFreeUpSpace()
        assertEquals(1, cleanPreview.unusedMediaCount)
    }
}

