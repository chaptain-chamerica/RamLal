package com.likkho.app.storage

import com.likkho.app.storage.model.DiaryRecord
import com.likkho.app.storage.model.IndexEntryRecord
import com.likkho.app.storage.model.VaultIndexRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultIndexManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var indexManager: VaultIndexManager
    private val testDek = CryptoUtils.secureRandomBytes(32)

    @Before
    fun setUp() {
        vaultDir = tempFolder.newFolder("v1")
        indexManager = VaultIndexManager(VaultId.PRIMARY, vaultDir)
    }

    @Test
    fun writeAndReadIndex_roundTrip() {
        val entry1 = IndexEntryRecord(
            id = "d1",
            createdAt = 1000L,
            updatedAt = 2000L,
            pinned = true,
            flags = 1,
            plainPreview = "Hello World",
            heading = "First Entry"
        )
        val entry2 = IndexEntryRecord(
            id = "d2",
            createdAt = 1500L,
            updatedAt = 1600L,
            pinned = false,
            flags = 0,
            plainPreview = "Second diary content",
            heading = "Second Entry"
        )

        val record = VaultIndexRecord(entries = listOf(entry1, entry2))
        indexManager.writeIndex(record, testDek)

        assertTrue(indexManager.indexFile.exists())
        val readBack = indexManager.readIndex(testDek)
        assertNotNull(readBack)
        assertEquals(2, readBack!!.entries.size)
        assertEquals("First Entry", readBack.entries[0].heading)
        assertEquals("Second Entry", readBack.entries[1].heading)
    }

    @Test
    fun wrongKeyOrCorruptIndex_returnsNullSafely() {
        val record = VaultIndexRecord(entries = listOf(
            IndexEntryRecord("d1", 1000L, 2000L, false, 0, "preview", "heading")
        ))
        indexManager.writeIndex(record, testDek)

        val wrongDek = CryptoUtils.secureRandomBytes(32)
        val readWrong = indexManager.readIndex(wrongDek)
        assertNull("Reading index with wrong key must return null", readWrong)
    }

    @Test
    fun selfHealing_rebuildsFromDiaryFilesWhenIndexCorrupted() {
        val diariesDir = File(vaultDir, "diaries")
        diariesDir.mkdirs()

        // Create 2 real encrypted .lkd files
        val d1 = DiaryRecord("d1", "Diary One", 100L, 200L, pinned = true, plainPreview = "Prev 1")
        val d2 = DiaryRecord("d2", "Diary Two", 300L, 400L, pinned = false, plainPreview = "Prev 2")
        File(diariesDir, "d1.lkd").writeBytes(DiaryFileCodec.encode(d1, testDek))
        File(diariesDir, "d2.lkd").writeBytes(DiaryFileCodec.encode(d2, testDek))

        // Index does not exist
        assertNull(indexManager.readIndex(testDek))

        // Trigger self-healing rebuild
        val rebuilt = indexManager.rebuildFromDiaries(diariesDir, testDek)
        assertEquals(2, rebuilt.entries.size)

        // Read index back from disk to verify it was safely written
        val readBack = indexManager.readIndex(testDek)
        assertNotNull(readBack)
        assertEquals(2, readBack!!.entries.size)
        assertTrue(readBack.entries.any { it.id == "d1" && it.heading == "Diary One" && it.pinned })
        assertTrue(readBack.entries.any { it.id == "d2" && it.heading == "Diary Two" && !it.pinned })
    }

    @Test
    fun upsertAndRemoveEntry() {
        val entry1 = IndexEntryRecord("d1", 100L, 200L, false, 0, "p1", "h1")
        indexManager.upsertEntry(entry1, testDek)

        val read1 = indexManager.readIndex(testDek)
        assertEquals(1, read1!!.entries.size)

        // Update entry1
        val entry1Updated = entry1.copy(heading = "h1_updated", pinned = true)
        indexManager.upsertEntry(entry1Updated, testDek)
        val read2 = indexManager.readIndex(testDek)
        assertEquals(1, read2!!.entries.size)
        assertEquals("h1_updated", read2.entries[0].heading)
        assertTrue(read2.entries[0].pinned)

        // Remove entry1
        indexManager.removeEntry("d1", testDek)
        val read3 = indexManager.readIndex(testDek)
        assertTrue(read3!!.entries.isEmpty())
    }
}
