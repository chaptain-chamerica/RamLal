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
    fun corruptedFile_isQuarantinedAndNotDeleted() {
        val diaryId = "tampered_entry"
        val diary = sampleDiary(diaryId)
        val validBytes = DiaryFileCodec.encode(diary, testDek)

        // Tamper byte
        validBytes[validBytes.size - 3] = (validBytes[validBytes.size - 3].toInt() xor 0xFF).toByte()
        val file = File(repository.diariesDir, "$diaryId.lkd")
        file.writeBytes(validBytes)

        var thrown = false
        try {
            repository.loadDiary(diaryId, testDek)
        } catch (e: CorruptedDiaryException) {
            thrown = true
            assertNotNull(e.quarantinedPath)
            assertTrue(File(e.quarantinedPath!!).exists())
        }
        assertTrue("Corrupted file must throw CorruptedDiaryException", thrown)
        assertFalse("Original corrupt file must be moved out of active directory", file.exists())

        // Check quarantine directory contains the file
        val quarantinedFiles = repository.quarantineDir.listFiles() ?: emptyArray()
        assertTrue(quarantinedFiles.any { it.name.startsWith(diaryId) })
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
}
