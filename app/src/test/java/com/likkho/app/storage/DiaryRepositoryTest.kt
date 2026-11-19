package com.likkho.app.storage

import com.likkho.app.storage.model.DiaryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiaryRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var repository: DiaryRepository
    private val testDek = CryptoUtils.secureRandomBytes(32)

    @Before
    fun setUp() {
        vaultDir = tempFolder.newFolder("v1_repo_test")
        repository = DiaryRepository(VaultId.PRIMARY, vaultDir)
        repository.ensureDirs()
    }

    private fun sampleDiary(id: String = "diary_101", heading: String = "Test Diary"): DiaryRecord =
        DiaryRecord(
            id = id,
            heading = heading,
            createdAt = 1000L,
            updatedAt = 2000L,
            pinned = true,
            plainPreview = "Sample preview content"
        )

    @Test
    fun saveAndLoadDiary_roundTrip() {
        val diary = sampleDiary()
        repository.saveDiary(diary, testDek)

        val loaded = repository.loadDiary(diary.id, testDek)
        assertEquals(diary.id, loaded.record.id)
        assertEquals(diary.heading, loaded.record.heading)
        assertEquals(diary.plainPreview, loaded.record.plainPreview)
        assertFalse(loaded.isReadOnly)

        // Verify index is synced
        val list = repository.listDiaries(testDek)
        assertEquals(1, list.size)
        assertEquals(diary.id, list[0].id)
        assertEquals(diary.heading, list[0].heading)
    }

    @Test
    fun deleteDiary_removesFilesAndIndexEntry() {
        val diary = sampleDiary()
        repository.saveDiary(diary, testDek)
        assertEquals(1, repository.listDiaries(testDek).size)

        repository.deleteDiary(diary.id, testDek)
        assertFalse(File(repository.diariesDir, "${diary.id}.lkd").exists())
        assertEquals(0, repository.listDiaries(testDek).size)
    }

    @Test
    fun recoveryScan_cleansTempFiles() {
        // Simulate a crashed write leaving a .tmp file
        val staleTmp = File(repository.diariesDir, "abandoned.lkd.tmp")
        staleTmp.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(staleTmp.exists())

        val report = repository.scanAndRecover(testDek)
        assertEquals(1, report.cleanedTempFiles)
        assertFalse(staleTmp.exists())
    }

    @Test
    fun recoveryScan_restoresFromBakIfMainFileCorrupt() {
        val diary = sampleDiary("diary_bak_test", "Healthy Backup Data")
        val validBytes = DiaryFileCodec.encode(diary, testDek)

        // Put valid data into .bak and corrupted data into main .lkd
        val mainFile = File(repository.diariesDir, "${diary.id}.lkd")
        val bakFile = File(repository.diariesDir, "${diary.id}.lkd.bak")
        bakFile.writeBytes(validBytes)
        mainFile.writeBytes(byteArrayOf(0, 0, 0, 0)) // Corrupt main file

        val report = repository.scanAndRecover(testDek)
        assertTrue(report.restoredFromBackup.contains(diary.id))

        // Main file should now be restored and loadable
        val loaded = repository.loadDiary(diary.id, testDek)
        assertEquals("Healthy Backup Data", loaded.record.heading)
        assertFalse(bakFile.exists())
    }

    @Test
    fun corruptedFile_isCopiedToQuarantineAndOriginalPreserved() {
        val diaryId = "tampered_entry"
        val diary = sampleDiary(diaryId)
        val validBytes = DiaryFileCodec.encode(diary, testDek)

        // Tamper byte
        validBytes[validBytes.size - 3] = (validBytes[validBytes.size - 3].toInt() xor 0xFF).toByte()
        val file = File(repository.diariesDir, "$diaryId.lkd")
        file.writeBytes(validBytes)

        var thrown = false
        var qPath: String? = null
        try {
            repository.loadDiary(diaryId, testDek)
        } catch (e: CorruptedDiaryException) {
            thrown = true
            qPath = e.quarantinedPath
            assertNotNull(qPath)
            assertTrue(File(qPath!!).exists())
        }
        assertTrue("Corrupted file must throw CorruptedDiaryException", thrown)
        assertTrue("Original corrupt file must remain on disk (quarantine copies instead of moving)", file.exists())

        // Check quarantine directory contains an exact copy
        val quarantinedFiles = repository.quarantineDir.listFiles() ?: emptyArray()
        assertTrue(quarantinedFiles.any { it.name.startsWith(diaryId) })
        assertTrue(file.readBytes().contentEquals(File(qPath!!).readBytes()))

        // Running scanAndRecover again must be idempotent (no duplicate quarantine copy)
        val report = repository.scanAndRecover(testDek)
        assertEquals(1, (repository.quarantineDir.listFiles() ?: emptyArray()).size)
        assertEquals(1, report.quarantinedFiles.size)
    }

    @Test(expected = ReadOnlyDiaryException::class)
    fun newerSchemaVersion_preventsOverwrite() {
        val diaryId = "future_diary"
        val diary = sampleDiary(diaryId)
        val futureVersion = DiaryFileCodec.CURRENT_SCHEMA_VERSION + 1
        val futureBytes = DiaryFileCodec.encode(diary, testDek, schemaVersion = futureVersion)

        val file = File(repository.diariesDir, "$diaryId.lkd")
        file.writeBytes(futureBytes)

        // Reading is allowed and marks as read-only
        val loaded = repository.loadDiary(diaryId, testDek)
        assertTrue(loaded.isReadOnly)

        // Attempting to overwrite must throw ReadOnlyDiaryException
        val modified = diary.copy(heading = "Overwriting Attempt")
        repository.saveDiary(modified, testDek)
    }

    @Test
    fun loadAllDiaries_surfacesCorruptDiariesInFailedList() {
        val healthy = sampleDiary("healthy_1", "Good Diary")
        repository.saveDiary(healthy, testDek)

        val corruptBytes = DiaryFileCodec.encode(sampleDiary("bad_1", "Bad Diary"), testDek)
        corruptBytes[corruptBytes.size - 4] = (corruptBytes[corruptBytes.size - 4].toInt() xor 0xFF).toByte()
        File(repository.diariesDir, "bad_1.lkd").writeBytes(corruptBytes)

        val result = repository.loadAllDiaries(testDek)
        assertEquals(1, result.diaries.size)
        assertEquals("healthy_1", result.diaries[0].id)
        assertEquals(1, result.failed.size)
        assertEquals("bad_1", result.failed[0].diaryId)
        assertNotNull(result.failed[0].quarantinedPath)
    }

    @Test
    fun listDiaries_reconcilesOnDiskVsIndexMismatch() {
        val d1 = sampleDiary("d1", "One")
        repository.saveDiary(d1, testDek)

        // Add d2 directly to disk without updating index
        val d2 = sampleDiary("d2", "Two")
        File(repository.diariesDir, "d2.lkd").writeBytes(DiaryFileCodec.encode(d2, testDek))

        val reconciledAdd = repository.listDiaries(testDek)
        assertEquals(setOf("d1", "d2"), reconciledAdd.map { it.id }.toSet())

        // Remove d1 directly from disk without updating index
        File(repository.diariesDir, "d1.lkd").delete()
        val reconciledRemove = repository.listDiaries(testDek)
        assertEquals(setOf("d2"), reconciledRemove.map { it.id }.toSet())
    }

    @Test
    fun deleteDiary_strictOrder_fileThenIndexThenMedia() {
        val mediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, vaultDir)
        mediaRepo.importMediaBytes("m_del_order", "payload".toByteArray(), testDek)
        val diary = sampleDiary("d_order", "Order Test").copy(
            images = listOf(com.likkho.app.storage.model.CanvasImageRecord(mediaId = "m_del_order"))
        )
        repository.saveDiary(diary, testDek)

        val steps = mutableListOf<String>()
        repository.deleteDiary(diary.id, testDek, mediaRepo) { step ->
            steps.add(step)
            when (step) {
                "diary_file_deleted" -> {
                    assertFalse("File must be deleted in step 1", File(repository.diariesDir, "${diary.id}.lkd").exists())
                    assertTrue("Index must still have entry before step 2", repository.indexManager.readIndex(testDek)!!.entries.any { it.id == diary.id })
                    assertTrue("Media must still exist before step 3", mediaRepo.exists("m_del_order"))
                }
                "index_updated" -> {
                    assertFalse("Index entry must be gone in step 2", repository.indexManager.readIndex(testDek)!!.entries.any { it.id == diary.id })
                    assertTrue("Media must still exist before step 3", mediaRepo.exists("m_del_order"))
                }
                "media_deleted" -> {
                    assertFalse("Unreferenced media must be deleted in step 3", mediaRepo.exists("m_del_order"))
                }
            }
        }
        assertEquals(listOf("diary_file_deleted", "index_updated", "media_deleted"), steps)
    }

    @Test
    fun quarantine_doesNotTriggerOnWrongDekOrIoError_andRetriesTransientIo() {
        val healthy = sampleDiary("d_healthy", "Healthy")
        repository.saveDiary(healthy, testDek)

        // 1. Wrong DEK against vault with healthy index/diary must NOT quarantine
        val wrongDek = CryptoUtils.secureRandomBytes(32)
        val wrongReport = repository.scanAndRecover(wrongDek)
        assertTrue("Wrong DEK must never quarantine files", wrongReport.quarantinedFiles.isEmpty())
        assertEquals(0, (repository.quarantineDir.listFiles() ?: emptyArray()).size)

        // 2. Transient IOException succeeds on retry without quarantining
        var failCount = 0
        val transientRepo = DiaryRepository(VaultId.PRIMARY, vaultDir) { f ->
            if (f.name == "d_healthy.lkd" && failCount < 2) {
                failCount++
                throw java.io.IOException("Simulated transient disk busy")
            }
            f.readBytes()
        }
        val transientLoaded = transientRepo.loadDiary("d_healthy", testDek)
        assertEquals("Healthy", transientLoaded.record.heading)
        assertEquals(2, failCount)
        assertEquals(0, (repository.quarantineDir.listFiles() ?: emptyArray()).size)

        // 3. Persistent IOException exhausts retries, throws IOException, does NOT move or copy to quarantine
        var totalAttempts = 0
        val persistentIoRepo = DiaryRepository(VaultId.PRIMARY, vaultDir) { f ->
            if (f.name == "d_healthy.lkd") {
                totalAttempts++
                throw java.io.IOException("Persistent disk IO error")
            }
            f.readBytes()
        }
        val scanIoReport = persistentIoRepo.scanAndRecover(testDek)
        assertEquals(DiaryRepository.MAX_IO_RETRIES, totalAttempts)
        assertTrue(scanIoReport.quarantinedFiles.isEmpty())
        assertTrue(scanIoReport.ioErrors.isNotEmpty())
        assertTrue("File must not be moved on IO error", File(repository.diariesDir, "d_healthy.lkd").exists())
        assertEquals(0, (repository.quarantineDir.listFiles() ?: emptyArray()).size)
    }

    @Test
    fun orphanCleanup_protectsMediaWhenUndecodableOrQuarantinedDiaryExists() {
        var now = 1_700_000_000_000L
        val clock = object : ClockProvider {
            override fun elapsedRealtimeMs(): Long = now
            override fun wallClockMs(): Long = now
        }
        val mediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, vaultDir, clock)

        mediaRepo.importMediaBytes("m_good", "good".toByteArray(), testDek)
        mediaRepo.importMediaBytes("m_corrupt_diary", "belonged to bad diary".toByteArray(), testDek)
        mediaRepo.getMediaFile("m_corrupt_diary").setLastModified(now - (48L * 60 * 60 * 1000))

        val goodDiary = sampleDiary("good_d", "Good").copy(
            images = listOf(com.likkho.app.storage.model.CanvasImageRecord(mediaId = "m_good"))
        )
        repository.saveDiary(goodDiary, testDek)

        // Add corrupt diary referencing m_corrupt_diary (now undecodable!)
        val badDiary = sampleDiary("bad_d", "Bad").copy(
            images = listOf(com.likkho.app.storage.model.CanvasImageRecord(mediaId = "m_corrupt_diary"))
        )
        val badBytes = DiaryFileCodec.encode(badDiary, testDek)
        badBytes[badBytes.size - 2] = (badBytes[badBytes.size - 2].toInt() xor 0xFF).toByte()
        File(repository.diariesDir, "bad_d.lkd").writeBytes(badBytes)

        repository.scanAndRecover(testDek)
        val cleaned = repository.cleanupOrphanMedia(testDek, mediaRepo)
        assertEquals("Orphan cleanup must NOT delete media when undecodable/quarantined diary exists", 0, cleaned)
        assertTrue("Media of undecodable/quarantined diary must be preserved", mediaRepo.exists("m_corrupt_diary"))
    }
}
