package com.likkho.app.storage.backup

import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.DiaryRepository
import com.likkho.app.storage.EncryptedMediaRepository
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.model.CanvasImageRecord
import com.likkho.app.storage.model.DiaryRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var vaultDir: File
    private lateinit var tempDir: File
    private lateinit var diaryRepo: DiaryRepository
    private lateinit var mediaRepo: EncryptedMediaRepository
    private lateinit var backupEngine: BackupEngine

    private val vaultDek = CryptoUtils.secureRandomBytes(32)
    private val testPassphrase = "mySecretBackupPassword123!".toCharArray()
    private val fastIterations = 5_000

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("backup_test_root")
        vaultDir = File(baseDir, "v1")
        vaultDir.mkdirs()
        tempDir = File(baseDir, "temp")
        tempDir.mkdirs()

        diaryRepo = DiaryRepository(VaultId.PRIMARY, vaultDir)
        mediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, vaultDir)
        diaryRepo.ensureDirs()
        mediaRepo.ensureDirs()

        backupEngine = BackupEngine(
            tempDir = tempDir,
            defaultIterations = fastIterations,
            appVersion = "1.0.0"
        )
    }

    @Test
    fun backupAndRestore_fullRoundTrip() {
        // 1. Seed vault with 2 diaries and 1 image media
        val mediaId = "photo_paris"
        val mediaBytes = "Paris Eiffel Tower Photo Bytes 123456789".toByteArray(Charsets.UTF_8)
        mediaRepo.importMediaBytes(mediaId, mediaBytes, vaultDek)

        val diary1 = DiaryRecord(
            id = "d1",
            heading = "Trip to Paris",
            images = listOf(CanvasImageRecord(mediaId = mediaId)),
            plainPreview = "Eiffel tower was great."
        )
        val diary2 = DiaryRecord(
            id = "d2",
            heading = "Grocery List",
            plainPreview = "Milk, Eggs, Bread"
        )
        diaryRepo.saveDiary(diary1, vaultDek)
        diaryRepo.saveDiary(diary2, vaultDek)

        val backupFile = File(baseDir, "my_backup.lkb")

        // 2. Create backup
        val backupResult = backupEngine.createBackup(
            targetFile = backupFile,
            passphrase = testPassphrase,
            diaryRepo = diaryRepo,
            mediaRepo = mediaRepo,
            vaultDek = vaultDek,
            iterations = fastIterations
        )

        assertTrue(backupResult.success)
        assertEquals(2, backupResult.diaryCount)
        assertEquals(1, backupResult.mediaCount)
        assertEquals(0, backupResult.failedMediaCount)
        assertTrue(backupFile.exists())

        // 3. Inspect backup without touching vault
        val contents = backupEngine.readBackupContents(backupFile, testPassphrase)
        assertEquals(2, contents.diaries.size)
        assertEquals(1, contents.mediaIds.size)
        assertTrue(contents.mediaIds.contains(mediaId))

        // 4. Wipe vault to simulate fresh device restore
        diaryRepo.deleteDiary("d1", vaultDek)
        diaryRepo.deleteDiary("d2", vaultDek)
        mediaRepo.deleteMedia(mediaId)
        assertEquals(0, diaryRepo.listDiaries(vaultDek).size)
        assertFalse(mediaRepo.exists(mediaId))

        // 5. Restore into clean vault
        val restoreResult = backupEngine.restoreBackup(
            backupFile = backupFile,
            passphrase = testPassphrase,
            diaryRepo = diaryRepo,
            mediaRepo = mediaRepo,
            vaultDek = vaultDek
        )

        assertEquals(2, restoreResult.restoredDiaries)
        assertEquals(0, restoreResult.conflictCopies)
        assertEquals(1, restoreResult.importedMedia)

        // Verify diaries and media are restored perfectly
        val loadedD1 = diaryRepo.loadDiary("d1", vaultDek)
        assertEquals("Trip to Paris", loadedD1.record.heading)
        assertEquals(1, loadedD1.record.images.size)

        val loadedMedia = mediaRepo.readDecryptedBytes(mediaId, vaultDek)
        assertArrayEquals(mediaBytes, loadedMedia)
    }

    @Test(expected = CannotOpenFileException::class)
    fun restore_withWrongPassphrase_throwsCannotOpenFileException_andLeavesVaultUntouched() {
        val diary = DiaryRecord(id = "d1", heading = "Secret Note")
        diaryRepo.saveDiary(diary, vaultDek)

        val backupFile = File(baseDir, "passphrase_test.lkb")
        backupEngine.createBackup(
            targetFile = backupFile,
            passphrase = testPassphrase,
            diaryRepo = diaryRepo,
            mediaRepo = mediaRepo,
            vaultDek = vaultDek,
            iterations = fastIterations
        )

        val wrongPassphrase = "completelyWrongPassword!".toCharArray()

        try {
            backupEngine.restoreBackup(
                backupFile = backupFile,
                passphrase = wrongPassphrase,
                diaryRepo = diaryRepo,
                mediaRepo = mediaRepo,
                vaultDek = vaultDek
            )
        } finally {
            // Live vault remains intact
            assertEquals(1, diaryRepo.listDiaries(vaultDek).size)
            assertEquals("Secret Note", diaryRepo.loadDiary("d1", vaultDek).record.heading)
        }
    }

    @Test
    fun restore_conflictMergeRule_preservesBothVersionsWithoutDataLoss() {
        val originalDiary = DiaryRecord(
            id = "d_shared",
            heading = "Original Draft",
            updatedAt = 1000L
        )
        diaryRepo.saveDiary(originalDiary, vaultDek)

        val backupFile = File(baseDir, "conflict_test.lkb")
        backupEngine.createBackup(
            targetFile = backupFile,
            passphrase = testPassphrase,
            diaryRepo = diaryRepo,
            mediaRepo = mediaRepo,
            vaultDek = vaultDek,
            iterations = fastIterations
        )

        // Scenario A: Local was updated to a NEWER timestamp (2000L) than the backup (1000L)
        val localUpdatedDiary = originalDiary.copy(
            heading = "Local Edited Title",
            updatedAt = 2000L
        )
        diaryRepo.saveDiary(localUpdatedDiary, vaultDek)

        val restoreResult = backupEngine.restoreBackup(
            backupFile = backupFile,
            passphrase = testPassphrase,
            diaryRepo = diaryRepo,
            mediaRepo = mediaRepo,
            vaultDek = vaultDek
        )

        assertEquals(1, restoreResult.conflictCopies)

        // Local newer version kept original ID
        val active = diaryRepo.loadDiary("d_shared", vaultDek).record
        assertEquals("Local Edited Title", active.heading)

        // Older restored version preserved as conflict copy
        val allDiaries = diaryRepo.listDiaries(vaultDek)
        assertEquals(2, allDiaries.size)
        val copySummary = allDiaries.find { it.id != "d_shared" }
        assertNotNull(copySummary)
        assertTrue(copySummary!!.heading.contains("Restored Copy"))
    }

    @Test
    fun deleteDiaryFromBackup_removesDiaryAndUnreferencedMediaAtomically() {
        val mediaOnlyForD1 = "media_d1"
        mediaRepo.importMediaBytes(mediaOnlyForD1, "d1 media".toByteArray(), vaultDek)

        val diary1 = DiaryRecord(
            id = "d1",
            heading = "Diary 1",
            images = listOf(CanvasImageRecord(mediaId = mediaOnlyForD1))
        )
        val diary2 = DiaryRecord(id = "d2", heading = "Diary 2")
        diaryRepo.saveDiary(diary1, vaultDek)
        diaryRepo.saveDiary(diary2, vaultDek)

        val backupFile = File(baseDir, "delete_from_backup.lkb")
        backupEngine.createBackup(
            targetFile = backupFile,
            passphrase = testPassphrase,
            diaryRepo = diaryRepo,
            mediaRepo = mediaRepo,
            vaultDek = vaultDek,
            iterations = fastIterations
        )

        // Delete d1 from the backup file
        val editResult = backupEngine.deleteDiaryFromBackup(backupFile, testPassphrase, "d1")
        assertEquals(1, editResult.diaryCount)
        assertEquals(0, editResult.mediaCount) // media_d1 was unreferenced and also removed

        // Inspect backup to verify
        val contents = backupEngine.readBackupContents(backupFile, testPassphrase)
        assertEquals(1, contents.diaries.size)
        assertEquals("d2", contents.diaries[0].id)
        assertFalse(contents.mediaIds.contains(mediaOnlyForD1))
    }

    @Test
    fun shareDiary_createAndRestore_roundTrip() {
        val shareMediaId = "shared_sunset"
        val mediaBytes = "Sunset image binary content".toByteArray(Charsets.UTF_8)
        mediaRepo.importMediaBytes(shareMediaId, mediaBytes, vaultDek)

        val diary = DiaryRecord(
            id = "d_shared_entry",
            heading = "Sunset View",
            images = listOf(CanvasImageRecord(mediaId = shareMediaId)),
            plainPreview = "A beautiful sunset"
        )
        diaryRepo.saveDiary(diary, vaultDek)

        val shareFile = File(baseDir, "sunset.likkho")
        val createdFile = backupEngine.createShareDiaryFile(
            targetFile = shareFile,
            diaryId = diary.id,
            passphrase = testPassphrase,
            diaryRepo = diaryRepo,
            mediaRepo = mediaRepo,
            vaultDek = vaultDek,
            iterations = fastIterations
        )
        assertTrue(createdFile.exists())

        // Simulate clean second vault/device
        val vault2Dir = File(baseDir, "v2_target")
        val diaryRepo2 = DiaryRepository(VaultId.PRIMARY, vault2Dir)
        val mediaRepo2 = EncryptedMediaRepository(VaultId.PRIMARY, vault2Dir)
        diaryRepo2.ensureDirs()
        mediaRepo2.ensureDirs()
        val targetVaultDek = CryptoUtils.secureRandomBytes(32)

        val restoreResult = backupEngine.restoreShareDiaryFile(
            shareFile = shareFile,
            passphrase = testPassphrase,
            diaryRepo = diaryRepo2,
            mediaRepo = mediaRepo2,
            vaultDek = targetVaultDek
        )

        assertEquals(1, restoreResult.restoredDiaries)
        assertEquals(1, restoreResult.importedMedia)

        val loaded = diaryRepo2.loadDiary(diary.id, targetVaultDek)
        assertEquals("Sunset View", loaded.record.heading)
        assertTrue(mediaRepo2.exists(shareMediaId))
        assertArrayEquals(mediaBytes, mediaRepo2.readDecryptedBytes(shareMediaId, targetVaultDek))
    }

    @Test
    fun restoreBackup_mergesWithExistingLocalDiariesInsteadOfReplacing() {
        val backupDiaryOnly = DiaryRecord(id = "d_from_backup", heading = "From Backup", updatedAt = 1000L)
        val sharedOld = DiaryRecord(id = "d_shared", heading = "Backup Shared", updatedAt = 1500L)
        diaryRepo.saveDiary(backupDiaryOnly, vaultDek)
        diaryRepo.saveDiary(sharedOld, vaultDek)

        val backupFile = File(baseDir, "merge_test.lkb")
        backupEngine.createBackup(backupFile, testPassphrase, diaryRepo, mediaRepo, vaultDek, fastIterations)

        // Mutate live vault: remove d_from_backup, update d_shared to newer timestamp, add local-only diary
        diaryRepo.deleteDiary("d_from_backup", vaultDek)
        diaryRepo.saveDiary(sharedOld.copy(heading = "Local Newer Shared", updatedAt = 3000L), vaultDek)
        diaryRepo.saveDiary(DiaryRecord(id = "d_local_only", heading = "Local Only", updatedAt = 2500L), vaultDek)

        backupEngine.restoreBackup(backupFile, testPassphrase, diaryRepo, mediaRepo, vaultDek)

        val ids = diaryRepo.listDiaries(vaultDek).map { it.id }.toSet()
        assertTrue("Local-only diary must NOT be replaced during restore", ids.contains("d_local_only"))
        assertTrue("Backup-only diary must be added", ids.contains("d_from_backup"))
        assertTrue("Newer local shared diary must keep original id", ids.contains("d_shared"))
        assertTrue("Older backup shared diary must be kept as conflict copy", ids.any { it.startsWith("d_shared_copy_") })
        assertEquals("Local Newer Shared", diaryRepo.loadDiary("d_shared", vaultDek).record.heading)
    }

    @Test
    fun restoreBackup_tamperedMidStream_throwsCannotOpenFileException_andCleansStaging() {
        mediaRepo.importMediaBytes("tamper_m1", ByteArray(150 * 1024) { it.toByte() }, vaultDek)
        val diary = DiaryRecord(
            id = "d_tamper",
            heading = "Tamper Guard",
            images = listOf(CanvasImageRecord(mediaId = "tamper_m1"))
        )
        diaryRepo.saveDiary(diary, vaultDek)

        val backupFile = File(baseDir, "tamper_stream.lkb")
        backupEngine.createBackup(backupFile, testPassphrase, diaryRepo, mediaRepo, vaultDek, fastIterations)

        val bytes = backupFile.readBytes()
        val midOffset = bytes.size / 2
        bytes[midOffset] = (bytes[midOffset].toInt() xor 0xFF).toByte()
        backupFile.writeBytes(bytes)

        diaryRepo.deleteDiary("d_tamper", vaultDek)

        var threw = false
        try {
            backupEngine.restoreBackup(backupFile, testPassphrase, diaryRepo, mediaRepo, vaultDek)
        } catch (_: CannotOpenFileException) {
            threw = true
        }
        assertTrue("Expected CannotOpenFileException on mid-stream tamper", threw)
        assertEquals("Vault must remain clean after failed restore", 0, diaryRepo.listDiaries(vaultDek).size)
        assertEquals("Staging and snapshot dirs must be cleaned up", 0, tempDir.listFiles()?.size ?: 0)
    }

    @Test
    fun restoreBackup_legacyV1Container_stillRestoresCleanly() {
        val mediaBytes = "legacy v1 payload".toByteArray(Charsets.UTF_8)
        val diary = DiaryRecord(
            id = "d_v1",
            heading = "Legacy V1 Entry",
            images = listOf(CanvasImageRecord(mediaId = "m_v1"))
        )
        val jsonBytes = kotlinx.serialization.json.Json.encodeToString(DiaryRecord.serializer(), diary).toByteArray(Charsets.UTF_8)
        val manifest = com.likkho.app.storage.model.BackupManifest(
            schemaVersion = 1,
            appVersion = "1.0.0",
            diaryCount = 1,
            mediaCount = 1,
            items = listOf(
                com.likkho.app.storage.model.BackupItemHash("diaries/d_v1", CryptoUtils.sha256Hex(jsonBytes), jsonBytes.size.toLong()),
                com.likkho.app.storage.model.BackupItemHash("media/m_v1", CryptoUtils.sha256Hex(mediaBytes), mediaBytes.size.toLong())
            )
        )
        val v1Bytes = SecureContainerCodec.encodeBackupContainer(
            manifest = manifest,
            diaries = listOf(diary),
            mediaList = listOf("m_v1" to mediaBytes),
            passphrase = testPassphrase,
            iterations = fastIterations
        )
        val v1File = File(baseDir, "legacy_v1.lkb")
        v1File.writeBytes(v1Bytes)

        val res = backupEngine.restoreBackup(v1File, testPassphrase, diaryRepo, mediaRepo, vaultDek)
        assertEquals(1, res.restoredDiaries)
        assertEquals(1, res.importedMedia)
        assertArrayEquals(mediaBytes, mediaRepo.readDecryptedBytes("m_v1", vaultDek))
    }

    @Test
    fun backupAndRestore_over200MbVault_passesUnderXmx64mHeapLimit() {
        val workRoot = tempFolder.newFolder("xmx64_work")
        try {
            val javaBin = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
            val classpath = System.getProperty("java.class.path")
            val process = ProcessBuilder(
                javaBin,
                "-Xmx64m",
                "-cp",
                classpath,
                "com.likkho.app.storage.backup.BackupLowHeapRunner",
                workRoot.absolutePath
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            assertEquals("200+ MB backup/restore failed under -Xmx64m:\n$output", 0, exitCode)
            assertTrue("Expected LOW_HEAP_OK marker in output:\n$output", output.contains("LOW_HEAP_OK"))
        } finally {
            workRoot.deleteRecursively()
        }
    }

    @Test
    fun restoreBackup_snapshotSkipsMediaDir_andRollsBackOnFailure() {
        val backupMediaId = "m_backup_new"
        mediaRepo.importMediaBytes(backupMediaId, ByteArray(128 * 1024) { (it + 1).toByte() }, vaultDek)
        val backupDiary = DiaryRecord(
            id = "d_from_backup",
            heading = "From Backup",
            images = listOf(CanvasImageRecord(mediaId = backupMediaId))
        )
        diaryRepo.saveDiary(backupDiary, vaultDek)

        val backupFile = File(baseDir, "slim_snapshot.lkb")
        backupEngine.createBackup(backupFile, testPassphrase, diaryRepo, mediaRepo, vaultDek, fastIterations)

        // Reset vault so it only has an existing large local media + local diary
        diaryRepo.deleteDiary("d_from_backup", vaultDek)
        mediaRepo.deleteMedia(backupMediaId)

        val existingMediaId = "m_local_large"
        val existingMediaPayload = ByteArray(1024 * 1024) { (it * 3).toByte() }
        mediaRepo.importMediaBytes(existingMediaId, existingMediaPayload, vaultDek)
        diaryRepo.saveDiary(
            DiaryRecord(
                id = "d_local",
                heading = "Local Entry",
                images = listOf(CanvasImageRecord(mediaId = existingMediaId))
            ),
            vaultDek
        )

        var sawMediaInSnapshot = true
        var cleanupDuringOpFreed = -1L
        try {
            backupEngine.onBeforeCommitHookForTest = { snapshotDir ->
                sawMediaInSnapshot = File(snapshotDir, "media").exists()
                // Guard check: cleanupStaleTempDirs must be a no-op while restore is active
                cleanupDuringOpFreed = backupEngine.cleanupStaleTempDirs()
                throw java.io.IOException("Simulated failure before commit")
            }
            var threw = false
            try {
                backupEngine.restoreBackup(backupFile, testPassphrase, diaryRepo, mediaRepo, vaultDek)
            } catch (_: java.io.IOException) {
                threw = true
            }
            assertTrue(threw)
        } finally {
            backupEngine.onBeforeCommitHookForTest = null
        }

        assertFalse("Slim snapshot must NOT copy media/ directory", sawMediaInSnapshot)
        assertEquals("cleanupStaleTempDirs must skip cleanup while restore is running", 0L, cleanupDuringOpFreed)
        assertTrue("Existing local diary must survive rollback", diaryRepo.listDiaries(vaultDek).any { it.id == "d_local" })
        assertFalse("Backup diary must be rolled back", diaryRepo.listDiaries(vaultDek).any { it.id == "d_from_backup" })
        assertTrue("Existing local media must survive rollback", mediaRepo.exists(existingMediaId))
        assertArrayEquals(existingMediaPayload, mediaRepo.readDecryptedBytes(existingMediaId, vaultDek))
        assertFalse("Newly imported backup media must be deleted on rollback", mediaRepo.exists(backupMediaId))
    }

    @Test
    fun cleanupStaleTempDirs_deletesOldSnapshotAndStageFoldersWhenIdle() {
        val snap = File(tempDir, "vault_snapshot_9999").apply { mkdirs() }
        File(snap, "big_blob.bin").writeBytes(ByteArray(256 * 1024))
        val shareSnap = File(tempDir, "share_snapshot_8888").apply { mkdirs() }
        File(shareSnap, "tmp.bin").writeBytes(ByteArray(64 * 1024))
        val stage = File(tempDir, "restore_stage_7777").apply { mkdirs() }
        File(stage, "stage.bin").writeBytes(ByteArray(64 * 1024))

        val freed = backupEngine.cleanupStaleTempDirs()
        assertTrue(freed >= 384 * 1024L)
        assertEquals(0, tempDir.listFiles()?.size ?: 0)
    }
}

