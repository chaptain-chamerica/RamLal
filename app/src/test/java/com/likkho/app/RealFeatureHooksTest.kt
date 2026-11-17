package com.likkho.app

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
    fun persistLogs_savesAndDeletesGranularly() = runTest(testDispatcher) {
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
            vaultManager.activeDiaryRepository().loadAllDiaries(dek).map { it.toDiaryLog() }
        }
        assertEquals(2, loaded.size)
        assertTrue(loaded.any { it.id == "d1" && it.heading == "My First Diary" && it.pinned })
        assertTrue(loaded.any { it.id == "d2" && it.heading == "Second Diary" })

        // Delete log2 by persisting only log1
        hooks.persistLogs(listOf(log1))

        val afterDelete = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadAllDiaries(dek).map { it.toDiaryLog() }
        }
        assertEquals(1, afterDelete.size)
        assertEquals("d1", afterDelete[0].id)
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

        // Delete log to simulate restoring on clean state
        hooks.persistLogs(emptyList())
        val emptyLogs = vaultManager.withActiveDek { dek ->
            vaultManager.activeDiaryRepository().loadAllDiaries(dek)
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

        val exportsDir = File(filesDir, "exports")
        val zipFiles = exportsDir.listFiles { file -> file.extension == "zip" }
        assertNotNull(zipFiles)
        assertTrue(zipFiles!!.isNotEmpty())
        assertTrue(zipFiles[0].length() > 0)
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
            vaultManager.activeDiaryRepository().loadAllDiaries(dek).map { it.toDiaryLog() }
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
}
