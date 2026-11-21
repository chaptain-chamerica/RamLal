package com.likkho.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import com.likkho.app.storage.ClockProvider
import com.likkho.app.storage.InMemoryKeyStoreProvider
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.VaultManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class RealFeatureHooksTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var filesDir: File
    private lateinit var vaultManager: VaultManager
    private lateinit var hooks: RealFeatureHooks
    private val testDispatcher = UnconfinedTestDispatcher()

    private val fakeClock = object : ClockProvider {
        var elapsed: Long = 10_000L
        var wall: Long = 1_700_000_000_000L
        override fun elapsedRealtimeMs(): Long = elapsed
        override fun wallClockMs(): Long = wall
    }

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("likkho_vaults")
        filesDir = tempFolder.newFolder("app_files")

        vaultManager = VaultManager(
            baseDir = baseDir,
            keyStoreProvider = InMemoryKeyStoreProvider(),
            dispatcher = testDispatcher,
            pbkdf2Iterations = 5000,
            clockProvider = fakeClock
        )

        hooks = RealFeatureHooks(
            context = null,
            vaultManager = vaultManager,
            dispatcher = testDispatcher,
            scope = kotlinx.coroutines.CoroutineScope(testDispatcher),
            baseStorageDir = filesDir
        )
    }

    @Test
    fun persistLogs_neverImplicitlyDeletes_andExplicitDeleteDiaryDeletes() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()
        assertTrue(vaultManager.isUnlocked)

        val log1 = DiaryLog(
            id = "d1",
            heading = "My First Diary",
            plainPreview = "Hello World",
            contentHtml = "Hello World HTML",
            pinned = true
        )
        val log2 = DiaryLog(
            id = "d2",
            heading = "Second Diary",
            plainPreview = "Secret notes"
        )

        // Save two logs
        hooks.persistLogs(listOf(log1, log2))

        val loaded = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadAllDiaries(dek).diaries.map { it.toDiaryLog() }
        }
        assertEquals(2, loaded.size)
        assertTrue(loaded.any { it.id == "d1" && it.heading == "My First Diary" && it.pinned })
        assertTrue(loaded.any { it.id == "d2" && it.heading == "Second Diary" })

        // Calling persistLogs with only log1 must NOT implicitly delete log2
        hooks.persistLogs(listOf(log1))

        val afterSubsetPersist = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadAllDiaries(dek).diaries.map { it.toDiaryLog() }
        }
        assertEquals("persistLogs must never delete omitted diaries", 2, afterSubsetPersist.size)

        // Explicit deleteDiary(id) removes d2
        hooks.deleteDiary("d2")

        val afterDelete = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadAllDiaries(dek).diaries.map { it.toDiaryLog() }
        }
        assertEquals(1, afterDelete.size)
        assertEquals("d1", afterDelete[0].id)
    }

    @Test
    fun persistLogs_bindsVaultIdAtCallTime_preventsCrossVaultWrite() = runTest(testDispatcher) {
        // Setup controlled dispatcher hooks to hold coroutine until after vault switch
        val queued = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val delayedHooks = RealFeatureHooks(
            context = null,
            vaultManager = vaultManager,
            dispatcher = queued,
            scope = kotlinx.coroutines.CoroutineScope(queued),
            baseStorageDir = filesDir
        )

        val primaryPin = "1111".toCharArray()
        val decoyPin = "2222".toCharArray()
        vaultManager.ensurePrimaryVaultInitialized()
        vaultManager.setPrimaryPasscode(primaryPin)
        vaultManager.setDecoyPasscode(decoyPin, primaryPin)

        // Call persistLogs while PRIMARY vault is active (before queued coroutine runs)
        assertEquals(VaultId.PRIMARY, vaultManager.currentVault)
        delayedHooks.persistLogs(listOf(DiaryLog(id = "p_only", heading = "Primary Secret")))

        // Switch active vault to DECOY before coroutine executes
        vaultManager.lock()
        vaultManager.unlockWithPin(decoyPin)
        assertEquals(VaultId.DECOY, vaultManager.currentVault)

        testScheduler.advanceUntilIdle()

        // Primary log must NEVER leak into DECOY vault
        val decoyDiaries = vaultManager.withActiveDek { dek ->
            vaultManager.decoyDiaryRepo.loadAllDiaries(dek).diaries
        }
        assertTrue("Primary persist must never write into Decoy vault", decoyDiaries.none { it.id == "p_only" })
    }

    @Test
    fun passwords_loadAndPersist_encryptedInVault() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val passwords = listOf(
            SavedPassword(id = "p1", name = "Email", password = "SecretPassword1!", createdAt = 1000L),
            SavedPassword(id = "p2", name = "Banking", password = "BankPassword99$", createdAt = 2000L)
        )

        hooks.persistPasswords(VaultMode.PRIMARY, passwords)
        val loaded = hooks.loadPasswords(VaultMode.PRIMARY)

        assertEquals(2, loaded.size)
        assertEquals("Email", loaded[0].name)
        assertEquals("SecretPassword1!", loaded[0].password)
        assertEquals("Banking", loaded[1].name)
    }

    @Test
    fun wordAnalysis_loadAndSaveAndDelete() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val record = hooks.analyzeWord("Epiphany")
        assertEquals("Epiphany", record.word)

        val words = hooks.loadSavedWords(VaultMode.PRIMARY)
        assertEquals(1, words.size)
        assertEquals("Epiphany", words[0].word)

        val remaining = hooks.deleteSavedWord("Epiphany", VaultMode.PRIMARY)
        assertTrue(remaining.isEmpty())
        assertTrue(hooks.loadSavedWords(VaultMode.PRIMARY).isEmpty())
    }

    @Test
    fun backupAndRestore_roundTrip() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val log = DiaryLog(id = "b1", heading = "Backed Up Entry", plainPreview = "Important memory")
        hooks.persistLogs(listOf(log))

        val passphrase = "SecurePassphrase123!"
        val backupResult = hooks.performBackup(passphrase)
        assertEquals(0, backupResult.failedMediaCount)

        // Read backup contents
        val contents = hooks.openBackupVault(passphrase)
        assertEquals(1, contents.size)
        assertEquals("b1", contents[0].id)
        assertEquals("Backed Up Entry", contents[0].heading)

        // Explicitly delete log to simulate restoring on clean state
        hooks.deleteDiary("b1")
        val emptyLogs = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadAllDiaries(dek).diaries
        }
        assertTrue(emptyLogs.isEmpty())

        // Restore backup
        val restoreResult = hooks.performRestore(passphrase)
        assertEquals(1, restoreResult.logs.size)
        assertEquals("Backed Up Entry", restoreResult.logs[0].heading)
    }

    @Test(expected = IncorrectKeyException::class)
    fun restoreBackup_withWrongPassphrase_throwsIncorrectKeyException() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val log = DiaryLog(id = "b1", heading = "Secret Entry")
        hooks.persistLogs(listOf(log))

        hooks.performBackup("CorrectPassphrase123!")
        hooks.performRestore("WrongPassphrase999!")
    }

    @Test
    fun exportAllDataZip_createsValidZipFile() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val log = DiaryLog(id = "e1", heading = "Export Me", plainPreview = "Some text")
        hooks.persistLogs(listOf(log))

        val message = hooks.exportAllDataZip()
        assertTrue(message.contains("ZIP"))

        val exportsDir = hooks.exportCacheDir
        val zipFiles = exportsDir.listFiles { file -> file.extension == "zip" }
        assertNotNull(zipFiles)
        assertTrue(zipFiles!!.isNotEmpty())
        assertTrue(zipFiles[0].length() > 0)
        assertEquals(0L, com.likkho.app.storage.StorageMetrics.directorySizeBytes(File(filesDir, "exports")))
    }

    @Test
    fun clearAllData_cryptoShredsVaults() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val log = DiaryLog(id = "s1", heading = "To Shred")
        hooks.persistLogs(listOf(log))

        hooks.clearAllData()

        assertFalse(vaultManager.isUnlocked)
        assertFalse(vaultManager.envelopeV1.exists())
        assertFalse(vaultManager.envelopeV2.exists())
    }

    @Test
    fun canvasMedia_infiniteCoordinatesAndEncryptedMediaRoundTrip() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val imagePayload = ByteArray(256) { (it * 7).toByte() }
        val audioPayload = ByteArray(512) { (it * 13).toByte() }
        val videoPayload = ByteArray(768) { (it * 19).toByte() }

        val (imgId, audId, vidId) = vaultManager.withActiveDek { dek ->
            val repo = vaultManager.activeMediaRepository()
            val iId = repo.importMediaBytes("img_inf_test", imagePayload, dek)
            val aId = repo.importMediaBytes("aud_inf_test", audioPayload, dek)
            val vId = repo.importMediaBytes("vid_inf_test", videoPayload, dek)
            Triple(iId, aId, vId)
        }

        val log = DiaryLog(
            id = "inf_log_1",
            heading = "Infinite Media Canvas",
            canvasBgSource = imgId,
            canvasImages = listOf(
                CanvasImage(
                    id = imgId,
                    source = imgId,
                    x = 5400f,
                    y = 3850f,
                    width = 240f,
                    height = 180f,
                    opacity = 0.85f,
                    rotation = 33f,
                    sizeBytes = imagePayload.size.toLong()
                )
            ),
            audioAttachments = listOf(
                CanvasAudio(
                    id = audId,
                    name = "distant_note.m4a",
                    format = "m4a",
                    audioUri = audId,
                    durationSec = 42,
                    x = 7200f,
                    y = 6100f
                )
            ),
            videoAttachments = listOf(
                CanvasVideo(
                    id = vidId,
                    name = "clip.mp4",
                    source = vidId,
                    durationSec = 18,
                    x = 8100f,
                    y = 6500f,
                    width = 320f,
                    height = 200f,
                    rotation = 15f,
                    sizeBytes = videoPayload.size.toLong()
                )
            )
        )

        hooks.persistLogs(listOf(log))

        val loaded = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadAllDiaries(dek).diaries.map { it.toDiaryLog() }
        }
        assertEquals(1, loaded.size)
        val restored = loaded.first()
        assertEquals(1, restored.canvasImages.size)
        assertEquals(5400f, restored.canvasImages[0].x)
        assertEquals(3850f, restored.canvasImages[0].y)
        assertEquals(33f, restored.canvasImages[0].rotation)
        assertEquals(1, restored.audioAttachments.size)
        assertEquals(7200f, restored.audioAttachments[0].x)
        assertEquals(6100f, restored.audioAttachments[0].y)
        assertEquals(1, restored.videoAttachments.size)
        assertEquals(8100f, restored.videoAttachments[0].x)
        assertEquals(6500f, restored.videoAttachments[0].y)
        assertEquals(15f, restored.videoAttachments[0].rotation)

        val (readImg, readAud, readVid) = vaultManager.withActiveDek { dek ->
            val repo = vaultManager.activeMediaRepository()
            Triple(
                repo.readDecryptedBytes(imgId, dek),
                repo.readDecryptedBytes(audId, dek),
                repo.readDecryptedBytes(vidId, dek)
            )
        }
        assertTrue(imagePayload.contentEquals(readImg))
        assertTrue(audioPayload.contentEquals(readAud))
        assertTrue(videoPayload.contentEquals(readVid))
    }

    @Test
    fun mergeRestoredLogs_mergesWithoutReplacingLocalLogs() {
        val localOnly = DiaryLog(id = "local_1", heading = "Local Only", updatedAt = 1000L)
        val localNewer = DiaryLog(id = "shared", heading = "Local Newer", updatedAt = 3000L)
        val backupOnly = DiaryLog(id = "backup_1", heading = "Backup Only", updatedAt = 2000L)
        val backupOlder = DiaryLog(id = "shared", heading = "Backup Older", updatedAt = 1500L)

        val merged = mergeRestoredLogs(listOf(localOnly, localNewer), listOf(backupOnly, backupOlder))
        val byId = merged.associateBy { it.id }

        assertTrue(byId.containsKey("local_1"))
        assertTrue(byId.containsKey("backup_1"))
        assertEquals("Local Newer", byId["shared"]?.heading)
        assertTrue(byId.keys.any { it.startsWith("shared_copy_") })
    }

    @Test
    fun canvasMediaCleanup_deletesRemovedOnSave_andDeletesUnsavedOnDiscard() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val (imgKeep, imgRemove, audUnsaved, sharedImg) = vaultManager.withActiveDek { dek ->
            val m = vaultManager.activeMediaRepository()
            val k = m.importMediaBytes("med_keep", "k".toByteArray(), dek)
            val r = m.importMediaBytes("med_remove", "r".toByteArray(), dek)
            val u = m.importMediaBytes("med_unsaved", "u".toByteArray(), dek)
            val s = m.importMediaBytes("med_shared", "s".toByteArray(), dek)
            listOf(k, r, u, s)
        }

        // Another diary shares sharedImg
        val otherLog = DiaryLog(
            id = "other_diary",
            heading = "Other",
            updatedAt = 1000L,
            canvasImages = listOf(CanvasImage(id = sharedImg, source = sharedImg, x = 0f, y = 0f, width = 100f, height = 100f))
        )
        // Initial saved diary has imgKeep, imgRemove, and sharedImg
        val diaryV1 = DiaryLog(
            id = "main_diary",
            heading = "Main",
            updatedAt = 1000L,
            canvasImages = listOf(
                CanvasImage(id = imgKeep, source = imgKeep, x = 0f, y = 0f, width = 100f, height = 100f),
                CanvasImage(id = imgRemove, source = imgRemove, x = 0f, y = 0f, width = 100f, height = 100f),
                CanvasImage(id = sharedImg, source = sharedImg, x = 0f, y = 0f, width = 100f, height = 100f)
            )
        )
        hooks.persistLogs(listOf(otherLog, diaryV1))

        // User removes imgRemove & sharedImg from main_diary on canvas and clicks Save
        val diaryV2 = diaryV1.copy(
            updatedAt = 2000L,
            canvasImages = listOf(CanvasImage(id = imgKeep, source = imgKeep, x = 0f, y = 0f, width = 100f, height = 100f))
        )
        val oldIds = collectCanvasMediaIds(diaryV1.pfp, diaryV1.canvasBgSource, diaryV1.canvasImages, diaryV1.audioAttachments, diaryV1.videoAttachments)
        val newIds = collectCanvasMediaIds(diaryV2.pfp, diaryV2.canvasBgSource, diaryV2.canvasImages, diaryV2.audioAttachments, diaryV2.videoAttachments)
        hooks.persistLogs(listOf(otherLog, diaryV2), obsoleteMediaIds = oldIds - newIds)

        val mediaRepo = vaultManager.activeMediaRepository()
        assertTrue("Kept media must remain", mediaRepo.exists(imgKeep))
        assertFalse("Removed unreferenced media must be deleted after save", mediaRepo.exists(imgRemove))
        assertTrue("Removed media still used by other_diary must remain", mediaRepo.exists(sharedImg))

        // User imports audUnsaved in an unsaved session and exits without saving
        assertTrue(mediaRepo.exists(audUnsaved))
        hooks.deleteUnreferencedMedia(setOf(audUnsaved, imgKeep))
        assertFalse("Unsaved session media must be deleted on discard", mediaRepo.exists(audUnsaved))
        assertTrue("Saved media in diary must NOT be deleted when discarding session", mediaRepo.exists(imgKeep))
    }

    @Test
    fun writeHooks_reportResultOnSuccess_andFailOnLockOrSwitchOrWriteError() = runTest(testDispatcher) {
        // 1. When vault is locked, all 4 write hooks must invoke onResult with failure (never silent return)
        val persistLocked = arrayOfNulls<Result<Unit>>(1)
        val deleteLocked = arrayOfNulls<Result<Unit>>(1)
        val mediaLocked = arrayOfNulls<Result<Unit>>(1)
        val reminderLocked = arrayOfNulls<Result<Unit>>(1)

        hooks.persistLogs(listOf(DiaryLog(id = "d_lock", heading = "Locked"))) { persistLocked[0] = it }
        hooks.deleteDiary("d_lock") { deleteLocked[0] = it }
        hooks.deleteUnreferencedMedia(setOf("med_1")) { mediaLocked[0] = it }
        hooks.scheduleReminder(DiaryLog(id = "d_lock", heading = "Locked"), 12345L) { reminderLocked[0] = it }

        assertTrue("Locked persistLogs must fail", persistLocked[0]?.isFailure == true)
        assertTrue("Locked deleteDiary must fail", deleteLocked[0]?.isFailure == true)
        assertTrue("Locked deleteUnreferencedMedia must fail", mediaLocked[0]?.isFailure == true)
        assertTrue("Locked scheduleReminder must fail", reminderLocked[0]?.isFailure == true)

        // 2. When unlocked, normal writes invoke onResult with success
        vaultManager.ensurePrimaryVaultInitialized()
        val persistOk = arrayOfNulls<Result<Unit>>(1)
        val log = DiaryLog(id = "d_ok", heading = "Saved OK", updatedAt = 1000L)
        hooks.persistLogs(listOf(log)) { persistOk[0] = it }
        assertTrue("Valid persistLogs must succeed", persistOk[0]?.isSuccess == true)

        // Write error case: corrupt existing diary file and call scheduleReminder -> must report failure
        val corruptFile = File(vaultManager.primaryDiaryRepo.diariesDir, "d_ok.lkd")
        corruptFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        val reminderErr = arrayOfNulls<Result<Unit>>(1)
        hooks.scheduleReminder(log, 99999L) { reminderErr[0] = it }
        assertTrue("scheduleReminder on corrupt file must fail", reminderErr[0]?.isFailure == true)

        // 3. If vault switches before queued task executes, all 4 write hooks must report failure
        val queued = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val delayedHooks = RealFeatureHooks(
            context = null,
            vaultManager = vaultManager,
            dispatcher = queued,
            scope = kotlinx.coroutines.CoroutineScope(queued),
            baseStorageDir = filesDir
        )
        val pPin = "1111".toCharArray()
        val dPin = "2222".toCharArray()
        vaultManager.setPrimaryPasscode(pPin)
        vaultManager.setDecoyPasscode(dPin, pPin)

        val persistSwitch = arrayOfNulls<Result<Unit>>(1)
        val deleteSwitch = arrayOfNulls<Result<Unit>>(1)
        val mediaSwitch = arrayOfNulls<Result<Unit>>(1)
        val reminderSwitch = arrayOfNulls<Result<Unit>>(1)

        delayedHooks.persistLogs(listOf(DiaryLog(id = "d_sw", heading = "Sw"))) { persistSwitch[0] = it }
        delayedHooks.deleteDiary("d_sw") { deleteSwitch[0] = it }
        delayedHooks.deleteUnreferencedMedia(setOf("med_sw")) { mediaSwitch[0] = it }
        delayedHooks.scheduleReminder(DiaryLog(id = "d_sw", heading = "Sw"), 5555L) { reminderSwitch[0] = it }

        vaultManager.lock()
        vaultManager.unlockWithPin(dPin)
        testScheduler.advanceUntilIdle()

        assertTrue("Vault-switched persistLogs must report failure", persistSwitch[0]?.isFailure == true)
        assertTrue("Vault-switched deleteDiary must report failure", deleteSwitch[0]?.isFailure == true)
        assertTrue("Vault-switched deleteUnreferencedMedia must report failure", mediaSwitch[0]?.isFailure == true)
        assertTrue("Vault-switched scheduleReminder must report failure", reminderSwitch[0]?.isFailure == true)
    }

    @Test
    fun pfpPicker_neverUsesFakeSample_andTracksRealImportsInSession() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        // Fake hook pickPfpImage must return null and import nothing into media repo
        val fakePick = hooks.pickPfpImage()
        assertEquals(null, fakePick)
        assertEquals(0, vaultManager.primaryMediaRepo.mediaDir.listFiles()?.size ?: 0)

        // importPickedPfpPhoto with failing onImportPhotoUri must return null (no fake fallback)
        val failedImport = importPickedPfpPhoto("content://test/missing") { null }
        assertEquals(null, failedImport)

        // Real importPfpForSession tracks imported ID in sessionImportedMediaIds and notifies on failure
        val sessionMedia = mutableSetOf<String>()
        var failNotified = false
        val failedSessionRes = importPfpForSession(
            uri = "content://test/bad",
            importPhoto = { null },
            sessionImportedMediaIds = sessionMedia,
            onFailure = { failNotified = true }
        )
        assertEquals(null, failedSessionRes)
        assertTrue(failNotified)
        assertTrue(sessionMedia.isEmpty())

        val importedId = vaultManager.withActiveDek { dek ->
            vaultManager.primaryMediaRepo.importMediaBytes("pfp_real_1", byteArrayOf(1, 2, 3), dek)
        }
        val successRes = importPfpForSession(
            uri = "content://test/good",
            importPhoto = { importedId },
            sessionImportedMediaIds = sessionMedia
        )
        assertEquals(importedId, successRes)
        assertTrue(sessionMedia.contains(importedId))
    }

    @Test
    fun deleteAndReminder_raceDoesNotResurrectDiary_andMissingFileFails() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        // 1. scheduleReminder on non-existent diary must fail and NOT create .lkd file
        val missingRes = arrayOfNulls<Result<Unit>>(1)
        val ghostLog = DiaryLog(id = "ghost_rem", heading = "Ghost")
        hooks.scheduleReminder(ghostLog, 99_999L) { missingRes[0] = it }
        assertTrue("scheduleReminder on missing diary must fail", missingRes[0]?.isFailure == true)
        assertFalse(
            "scheduleReminder must never create a diary file when missing",
            File(vaultManager.primaryDiaryRepo.diariesDir, "ghost_rem.lkd").exists()
        )

        // 2. Delete + stale queued scheduleReminder race must NEVER resurrect deleted diary
        val queued = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val queuedHooks = RealFeatureHooks(
            context = null,
            vaultManager = vaultManager,
            dispatcher = queued,
            scope = kotlinx.coroutines.CoroutineScope(queued),
            baseStorageDir = filesDir
        )
        val diary = DiaryLog(id = "d_race", heading = "With Reminder", reminderAt = 50_000L, updatedAt = 1000L)
        hooks.persistLogs(listOf(diary))
        assertTrue(File(vaultManager.primaryDiaryRepo.diariesDir, "d_race.lkd").exists())

        val delRes = arrayOfNulls<Result<Unit>>(1)
        val remRaceRes = arrayOfNulls<Result<Unit>>(1)
        queuedHooks.cancelReminder(diary.id)
        queuedHooks.deleteDiary(diary.id) { delRes[0] = it }
        queuedHooks.scheduleReminder(diary, 80_000L) { remRaceRes[0] = it }
        testScheduler.advanceUntilIdle()

        assertTrue("Delete must succeed", delRes[0]?.isSuccess == true)
        assertTrue("Stale scheduleReminder after delete must fail", remRaceRes[0]?.isFailure == true)
        assertFalse(
            "Deleted diary must not reappear after reminder race",
            File(vaultManager.primaryDiaryRepo.diariesDir, "d_race.lkd").exists()
        )
        val loadedAfter = vaultManager.withActiveDek { dek ->
            vaultManager.primaryDiaryRepo.loadAllDiaries(dek).diaries
        }
        assertTrue("Deleted diary must stay absent from loaded diaries", loadedAfter.none { it.id == "d_race" })
    }

    @Test
    fun statusBanner_mapsStorageFailuresToErrorKindWithWarningIcon() {
        val prefixes = listOf(
            "Save failed",
            "Delete failed",
            "Pin update failed",
            "Media cleanup failed"
        )
        for (prefix in prefixes) {
            val state = storageFailureBanner(prefix, IllegalStateException("Vault locked"))
            assertEquals(BannerKind.ERROR, state.kind)
            assertTrue(state.message.startsWith("$prefix:"))
        }
        assertEquals(Icons.Outlined.Warning, statusBannerIcon(BannerKind.ERROR))
        assertEquals(Icons.Outlined.CheckCircle, statusBannerIcon(BannerKind.SUCCESS))
        assertEquals(BannerKind.SUCCESS, StatusBannerState("Saved cleanly").kind)
    }

    @Test
    fun homeSummaryLogs_preserveFullDiskContentAcrossPersistPinExportAndShare() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMediaBytes("img_step4", byteArrayOf(10, 20, 30, 40), dek)
        }
        val fullContent = "<p>Secret <b>full diary body</b> that must not live in Home list RAM.</p>"
        val fullLog = DiaryLog(
            id = "lazy_diary_1",
            heading = "Lazy Heading",
            contentHtml = fullContent,
            plainPreview = "Secret full diary body",
            createdAt = 1_000L,
            updatedAt = 2_000L,
            pinned = false,
            diaryLockPin = "4321",
            canvasBgSource = mediaId,
            canvasImages = listOf(CanvasImage(id = mediaId, source = mediaId, x = 12f, y = 24f, width = 100f, height = 80f)),
            audioAttachments = listOf(CanvasAudio(id = "aud_1", name = "voice.m4a", audioUri = "aud_1", durationSec = 5)),
            videoAttachments = listOf(CanvasVideo(id = "vid_1", name = "clip.mp4", source = "vid_1", durationSec = 9)),
            isContentLoaded = true
        )
        hooks.persistLogs(listOf(fullLog))

        // Direct model summary helper strips heavy fields and marks isContentLoaded = false
        val directSummary = fullLog.toSummary()
        assertFalse(directSummary.isContentLoaded)
        assertEquals("", directSummary.contentHtml)
        assertTrue(directSummary.canvasImages.isEmpty())
        assertTrue(directSummary.audioAttachments.isEmpty())
        assertTrue(directSummary.videoAttachments.isEmpty())
        assertEquals(null, directSummary.canvasBgSource)
        assertEquals("Lazy Heading", directSummary.heading)
        assertEquals("Secret full diary body", directSummary.plainPreview)

        // loadAllDiariesMapped loads summaries 1-by-1 without contentHtml/media lists in memory
        val (summaries, failed) = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadAllDiariesMapped(dek) { it.toSummaryDiaryLog() }
        }
        assertTrue(failed.isEmpty())
        assertEquals(1, summaries.size)
        val summary = summaries.first()
        assertFalse(summary.isContentLoaded)
        assertEquals("", summary.contentHtml)
        assertTrue(summary.canvasImages.isEmpty())
        assertNotNull(summary.diaryLockPin)

        // Pinning a summary log (updated metadata, empty contentHtml) must merge metadata and preserve disk HTML/media
        val pinnedSummary = summary.copy(pinned = true, updatedAt = 3_000L)
        hooks.persistLogs(listOf(pinnedSummary))

        val reloadedFull = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadDiary("lazy_diary_1", dek).record.toDiaryLog()
        }
        assertTrue("Pinned flag should update", reloadedFull.pinned)
        assertEquals("Full contentHtml must remain intact after summary persistLogs", fullContent, reloadedFull.contentHtml)
        assertEquals(mediaId, reloadedFull.canvasBgSource)
        assertEquals(1, reloadedFull.canvasImages.size)
        assertEquals(1, reloadedFull.audioAttachments.size)
        assertEquals(1, reloadedFull.videoAttachments.size)

        // Exporting a summary log must lazy-load full content from disk into cacheDir/exports (0 bytes in filesDir/exports)
        hooks.exportDiary(pinnedSummary, ExportFormat("txt", ".txt", "Plain Text Document"))
        val exportedFile = File(hooks.exportCacheDir, "Lazy_Heading.txt")
        assertTrue(exportedFile.exists())
        assertTrue(exportedFile.readText().contains(fullContent))
        assertEquals(0L, com.likkho.app.storage.StorageMetrics.directorySizeBytes(File(filesDir, "exports")))

        // Sharing a summary log as .likkho must NOT wipe disk content and must bundle the full diary
        val shareMsg = hooks.shareDiaryAsArchive(pinnedSummary, "SharePass123!")
        assertTrue(shareMsg.contains("Shared"))
        val afterShareFull = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadDiary("lazy_diary_1", dek).record.toDiaryLog()
        }
        assertEquals(fullContent, afterShareFull.contentHtml)
        assertEquals(1, afterShareFull.canvasImages.size)
    }

    @Test
    fun performBackup_threeBackups_keepsOnlyLatestValidLkb() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()
        val pass = "BackupPass123!"
        val backupsDir = hooks.backupDirFor(VaultId.PRIMARY)

        hooks.persistLogs(listOf(DiaryLog(id = "d_b1", heading = "First State")))
        hooks.performBackup(pass)
        Thread.sleep(5)

        hooks.persistLogs(listOf(DiaryLog(id = "d_b2", heading = "Second State")))
        hooks.performBackup(pass)
        Thread.sleep(5)

        hooks.persistLogs(listOf(DiaryLog(id = "d_b3", heading = "Third State")))
        hooks.performBackup(pass)

        val lkbFiles = backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray()
        assertEquals("Only latest 1 .lkb backup should remain after 3 backups", 1, lkbFiles.size)

        val contents = hooks.openBackupVault(pass)
        assertEquals(3, contents.size)
        assertTrue(contents.any { it.id == "d_b3" })
    }

    @Test
    fun performBackup_failedNewBackup_preservesExistingValidBackup() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()
        val goodPass = "BackupPass123!"
        val backupsDir = hooks.backupDirFor(VaultId.PRIMARY)

        hooks.persistLogs(listOf(DiaryLog(id = "d_keep", heading = "Surviving Entry")))
        hooks.performBackup(goodPass)

        val beforeFiles = backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray()
        assertEquals(1, beforeFiles.size)
        val originalFile = beforeFiles.first()

        var threw = false
        try {
            // Passphrase < 8 chars fails inside BackupEngine.createBackup before pruneOldBackups
            hooks.performBackup("short")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)

        val afterFiles = backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray()
        assertEquals("Previous valid backup must remain when new backup fails", 1, afterFiles.size)
        assertEquals(originalFile.absolutePath, afterFiles.first().absolutePath)
        assertEquals(1, hooks.openBackupVault(goodPass).size)
    }

    @Test
    fun performBackupToStream_writesVerifiedContainerWithoutBloatingAppPrivateBackups() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()
        val pass = "SafPassphrase123!"
        hooks.persistLogs(listOf(DiaryLog(id = "saf_1", heading = "External SAF Entry", plainPreview = "Saved outside filesDir")))

        val externalSafFile = File(tempFolder.newFolder("external_saf"), "likkho_saf_backup.lkb")
        val result = hooks.performBackupToStream(pass) { externalSafFile.outputStream() }
        assertEquals(0, result.failedMediaCount)
        assertTrue(externalSafFile.exists() && externalSafFile.length() > 0L)

        val backupsDir = hooks.backupDirFor(VaultId.PRIMARY)
        val internalLkbs = backupsDir.listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray()
        assertEquals("SAF backup must not leave .lkb files in app-private backups/", 0, internalLkbs.size)
        val leftoverSafTemp = vaultManager.backupEngine.tempDir.listFiles { f -> f.name.startsWith("saf_backup_") } ?: emptyArray()
        assertEquals(0, leftoverSafTemp.size)

        val contents = vaultManager.backupEngine.readBackupContents(externalSafFile, pass.toCharArray())
        assertEquals(1, contents.diaries.size)
        assertEquals("External SAF Entry", contents.diaries.first().heading)
    }

    @Test
    fun exportsAndShares_writeToCacheDir_andStartupClearsCacheAndLegacyExports() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()
        hooks.persistLogs(listOf(DiaryLog(id = "exp_1", heading = "First Export", plainPreview = "body")))

        // Legacy bloat files present from previous app version
        val legacyExports = File(filesDir, "exports").apply { mkdirs() }
        val legacyShares = File(filesDir, "shares").apply { mkdirs() }
        File(legacyExports, "old_export.txt").writeBytes(ByteArray(1024))
        File(legacyShares, "old_share.likkho").writeBytes(ByteArray(1024))

        hooks.exportDiary(DiaryLog(id = "exp_1", heading = "First Export", contentHtml = "Hello"), ExportFormat("txt", ".txt", "Text"))
        assertEquals("filesDir/exports must be cleaned and unused", 0L, com.likkho.app.storage.StorageMetrics.directorySizeBytes(File(filesDir, "exports")))
        assertEquals(1, hooks.exportCacheDir.listFiles()?.size ?: 0)

        hooks.exportAllDataZip()
        val cachedFilesAfterZip = hooks.exportCacheDir.listFiles() ?: emptyArray()
        assertEquals("Previous export should be pruned before writing new ZIP", 1, cachedFilesAfterZip.size)
        assertTrue(cachedFilesAfterZip[0].extension == "zip")

        val freed = hooks.runStartupStorageCleanup()
        assertTrue(freed > 0L)
        assertEquals(0, hooks.exportCacheDir.listFiles()?.size ?: 0)
        assertEquals(0, hooks.shareCacheDir.listFiles()?.size ?: 0)
        assertEquals(0L, com.likkho.app.storage.StorageMetrics.directorySizeBytes(File(filesDir, "shares")))
    }

    @Test
    fun freeUpSpace_removesOrphanMediaOldBackupsExportsAndTemp_preservingActiveMediaAndLatestBackup() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()

        val activeId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMediaBytes("m_active_keep", ByteArray(40 * 1024) { 1 }, dek)
        }
        val orphanId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMediaBytes("m_orphan_drop", ByteArray(256 * 1024) { 2 }, dek)
        }
        hooks.persistLogs(
            listOf(
                DiaryLog(
                    id = "d_active",
                    heading = "Keep My Media",
                    canvasImages = listOf(CanvasImage(id = activeId, source = activeId, width = 100f, height = 100f))
                )
            )
        )

        // Create 2 backups manually (simulating stale extra backups) + export cache + vaults/tmp bloat
        val backupsDir = hooks.backupDirFor(VaultId.PRIMARY).apply { mkdirs() }
        val oldLkb = File(backupsDir, "backup_1000.lkb").apply {
            writeBytes(ByteArray(128 * 1024))
            setLastModified(1_000_000L)
        }
        val newLkb = File(backupsDir, "backup_2000.lkb").apply {
            writeBytes(ByteArray(32 * 1024))
            setLastModified(2_000_000L)
        }
        File(hooks.exportCacheDir.apply { mkdirs() }, "stale_export.zip").writeBytes(ByteArray(64 * 1024))
        val tmpSnap = File(vaultManager.backupEngine.tempDir, "vault_snapshot_stale").apply { mkdirs() }
        File(tmpSnap, "leftover.bin").writeBytes(ByteArray(96 * 1024))

        val freedBytes = hooks.freeUpSpace()
        val expectedMinFreed = (256L + 128L + 64L + 96L) * 1024L
        assertTrue("Expected at least $expectedMinFreed bytes freed, got $freedBytes", freedBytes >= expectedMinFreed)

        assertTrue("Active referenced media must be preserved", vaultManager.activeMediaRepository().exists(activeId))
        assertFalse("Unreferenced orphan media must be removed", vaultManager.activeMediaRepository().exists(orphanId))
        assertFalse("Older backup must be pruned", oldLkb.exists())
        assertTrue("Latest backup must remain", newLkb.exists())
        assertEquals(0, hooks.exportCacheDir.listFiles()?.size ?: 0)
        assertEquals(0, vaultManager.backupEngine.tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun freeUpSpace_removesAbandonedProcessKillMediaUnder24h_preservingSavedAndActiveSessionMedia() =
        runTest(testDispatcher) {
            vaultManager.ensurePrimaryVaultInitialized()

            // Saved media in diary
            val savedMedia = vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMediaBytes("m_saved_keep", ByteArray(20 * 1024) { 1 }, dek)
            }
            hooks.persistLogs(
                listOf(
                    DiaryLog(
                        id = "saved_log_1",
                        heading = "Saved",
                        canvasImages = listOf(CanvasImage(id = savedMedia, source = savedMedia, width = 100f, height = 100f))
                    )
                )
            )

            // Active current-process session media (protected in this process)
            val activeSessionMedia = vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMediaBytes("m_session_keep", ByteArray(24 * 1024) { 2 }, dek)
            }
            vaultManager.recordSessionImportedMedia(activeSessionMedia)

            // Simulated killed-process pending import (<24h old, on disk in pending_session_imports.json, NOT in this process's protected set)
            val abandonedKillMedia = vaultManager.withActiveDek { dek ->
                vaultManager.activeMediaRepository().importMediaBytes("m_kill_drop", ByteArray(64 * 1024) { 3 }, dek)
            }
            vaultManager.activeMediaRepository().recordPendingSessionImport(abandonedKillMedia)

            val freed = hooks.freeUpSpace()
            assertTrue("Expected at least 64KB freed, got $freed", freed >= 64L * 1024L)
            assertFalse("Abandoned process-kill media (<24h) must be removed by Free up space", vaultManager.activeMediaRepository().exists(abandonedKillMedia))
            assertTrue("Saved diary media must NEVER be deleted by Free up space", vaultManager.activeMediaRepository().exists(savedMedia))
            assertTrue("Active current-session protected media must stay safe during Free up space", vaultManager.activeMediaRepository().exists(activeSessionMedia))
        }

    @Test
    fun perVaultBackups_useNeutralFolders_neverDeleteLegacySharedBackups_andIsolateVaults() = runTest(testDispatcher) {
        val primaryPin = "1111".toCharArray()
        val decoyPin = "2222".toCharArray()
        vaultManager.ensurePrimaryVaultInitialized()
        vaultManager.setPrimaryPasscode(primaryPin)
        vaultManager.setDecoyPasscode(decoyPin, primaryPin)

        // Neutral backup folder names check
        assertEquals("a", VaultId.PRIMARY.backupDirName)
        assertEquals("b", VaultId.DECOY.backupDirName)
        assertFalse(VaultId.DECOY.backupDirName.contains("decoy", ignoreCase = true))

        // Put a legacy shared .lkb file directly in backups/ root
        val legacyRootLkb1 = File(hooks.backupRootDir, "legacy_shared_1.lkb").apply {
            writeBytes(ByteArray(32 * 1024))
            setLastModified(1_000L)
        }
        val legacyRootLkb2 = File(hooks.backupRootDir, "legacy_shared_2.lkb").apply {
            writeBytes(ByteArray(32 * 1024))
            setLastModified(2_000L)
        }

        // Create backup in Primary vault -> goes strictly to backups/a/
        hooks.persistLogs(listOf(DiaryLog(id = "p_entry", heading = "Primary Diary")))
        hooks.performBackup("PrimaryPass123!")
        val primaryLkbs = hooks.backupDirFor(VaultId.PRIMARY).listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray()
        assertEquals(1, primaryLkbs.size)
        assertTrue(legacyRootLkb1.exists() && legacyRootLkb2.exists())

        // Switch to Decoy vault -> create backups -> goes strictly to backups/b/, never touches backups/a/ or legacy root .lkbs
        vaultManager.lock()
        vaultManager.unlockWithPin(decoyPin)
        hooks.persistLogs(listOf(DiaryLog(id = "d_entry", heading = "Decoy Diary")))
        hooks.performBackup("DecoyPass123!")
        hooks.performBackup("DecoyPass123!")

        val decoyLkbs = hooks.backupDirFor(VaultId.DECOY).listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray()
        assertEquals(1, decoyLkbs.size)
        assertTrue("Primary backup must stay untouched when Decoy runs backup & prune", primaryLkbs[0].exists())
        assertTrue("Legacy shared backups must never be auto-deleted by backups/prune", legacyRootLkb1.exists() && legacyRootLkb2.exists())

        // openBackupVault in Decoy sees ONLY Decoy's diary, never Primary's
        val decoyContents = hooks.openBackupVault("DecoyPass123!")
        assertEquals(listOf("d_entry"), decoyContents.map { it.id })

        // Free up space in Decoy never touches legacy root .lkbs or Primary backups
        val extraPrimaryLkb = File(hooks.backupDirFor(VaultId.PRIMARY), "backup_extra_p.lkb").apply {
            writeBytes(ByteArray(64 * 1024))
            setLastModified(100L)
        }
        hooks.runStartupStorageCleanup()
        hooks.freeUpSpace()
        assertTrue("Legacy root backups must never be auto-deleted by Free up space", legacyRootLkb1.exists() && legacyRootLkb2.exists())
        assertTrue("Primary extra backup must NEVER be pruned while unlocked in Decoy", extraPrimaryLkb.exists())
        assertTrue("Primary latest backup must remain untouched", primaryLkbs[0].exists())
    }
}



