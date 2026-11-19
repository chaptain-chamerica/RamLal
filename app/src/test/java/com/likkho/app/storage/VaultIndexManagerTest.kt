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

    @Test
    fun readIndexStatus_distinguishesMissingFromCorrupt() {
        assertTrue(indexManager.readIndexStatus(testDek) is IndexReadResult.Missing)

        val record = VaultIndexRecord(entries = listOf(
            IndexEntryRecord("d1", 1000L, 2000L, false, 0, "p", "h")
        ))
        indexManager.writeIndex(record, testDek)
        assertTrue(indexManager.readIndexStatus(testDek) is IndexReadResult.Loaded)

        // Wrong key => Corrupt (not Missing)
        val wrongDek = CryptoUtils.secureRandomBytes(32)
        assertTrue(indexManager.readIndexStatus(wrongDek) is IndexReadResult.Corrupt)

        // Truncated/corrupt bytes => Corrupt
        indexManager.indexFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertTrue(indexManager.readIndexStatus(testDek) is IndexReadResult.Corrupt)
    }

    @Test
    fun upsertEntry_whenIndexCorruptOrMissing_rebuildsInsteadOfWritingEmpty() {
        val diariesDir = File(vaultDir, "diaries")
        diariesDir.mkdirs()

        val d1 = DiaryRecord("d1", "Existing One", 100L, 200L, pinned = false, plainPreview = "P1")
        val d2 = DiaryRecord("d2", "Existing Two", 300L, 400L, pinned = false, plainPreview = "P2")
        File(diariesDir, "d1.lkd").writeBytes(DiaryFileCodec.encode(d1, testDek))
        File(diariesDir, "d2.lkd").writeBytes(DiaryFileCodec.encode(d2, testDek))

        // Case 1: Index missing, upsert d3 -> must keep d1 & d2
        val e3 = IndexEntryRecord("d3", 500L, 600L, false, 0, "P3", "New Three")
        indexManager.upsertEntry(e3, testDek, diariesDir)
        val afterMissing = indexManager.readIndex(testDek)!!
        assertEquals(setOf("d1", "d2", "d3"), afterMissing.entries.map { it.id }.toSet())

        // Case 2: Index corrupt, upsert d4 -> must rebuild d1, d2 (and keep existing .lkd), never wipe to [d4]
        indexManager.indexFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertTrue(indexManager.readIndexStatus(testDek) is IndexReadResult.Corrupt)
        val e4 = IndexEntryRecord("d4", 700L, 800L, false, 0, "P4", "New Four")
        indexManager.upsertEntry(e4, testDek, diariesDir)
        val afterCorrupt = indexManager.readIndex(testDek)!!
        assertEquals(setOf("d1", "d2", "d4"), afterCorrupt.entries.map { it.id }.toSet())
    }

    @Test
    fun corruptIndex_upsertAndRemove_failWithoutOverwritingIndex_whenWrongDekOrTooFewEntries() {
        val diariesDir = File(vaultDir, "diaries")
        diariesDir.mkdirs()

        val d1 = DiaryRecord("d1", "One", 100L, 200L, pinned = false, plainPreview = "P1")
        val d2 = DiaryRecord("d2", "Two", 300L, 400L, pinned = false, plainPreview = "P2")
        File(diariesDir, "d1.lkd").writeBytes(DiaryFileCodec.encode(d1, testDek))
        File(diariesDir, "d2.lkd").writeBytes(DiaryFileCodec.encode(d2, testDek))

        val initialIndex = VaultIndexRecord(
            entries = listOf(
                IndexEntryRecord("d1", 100L, 200L, false, 0, "P1", "One"),
                IndexEntryRecord("d2", 300L, 400L, false, 0, "P2", "Two")
            )
        )
        indexManager.writeIndex(initialIndex, testDek)
        val originalBytes = indexManager.indexFile.readBytes()

        // 1. Wrong DEK causes IndexReadResult.Corrupt; 0 .lkd files decode -> upsertEntry & removeEntry must throw and keep original index intact
        val wrongDek = CryptoUtils.secureRandomBytes(32)
        var upsertWrongDekThrew = false
        try {
            indexManager.upsertEntry(IndexEntryRecord("d3", 1L, 1L, false, 0, "P3", "Three"), wrongDek, diariesDir)
        } catch (_: StorageVerificationException) {
            upsertWrongDekThrew = true
        }
        assertTrue("upsertEntry with wrong DEK must fail without overwriting index", upsertWrongDekThrew)
        assertTrue(originalBytes.contentEquals(indexManager.indexFile.readBytes()))

        var removeWrongDekThrew = false
        try {
            indexManager.removeEntry("d1", wrongDek, diariesDir)
        } catch (_: StorageVerificationException) {
            removeWrongDekThrew = true
        }
        assertTrue("removeEntry with wrong DEK must fail without overwriting index", removeWrongDekThrew)
        assertTrue(originalBytes.contentEquals(indexManager.indexFile.readBytes()))

        // 2. Corrupt index bytes on disk + scanner returning fewer entries than decodable .lkd files -> must fail and NOT write index
        val corruptMarker = byteArrayOf(0x7F, 0x11, 0x22, 0x33)
        indexManager.indexFile.writeBytes(corruptMarker)
        val partialScannerManager = VaultIndexManager(VaultId.PRIMARY, vaultDir) { _, _ ->
            // Drops d2 even though both d1.lkd and d2.lkd decode cleanly
            listOf(IndexEntryRecord("d1", 100L, 200L, false, 0, "P1", "One"))
        }

        var partialUpsertThrew = false
        try {
            partialScannerManager.upsertEntry(IndexEntryRecord("d3", 500L, 600L, false, 0, "P3", "Three"), testDek, diariesDir)
        } catch (_: StorageVerificationException) {
            partialUpsertThrew = true
        }
        assertTrue("upsertEntry must refuse partial rebuild when rebuilt < decodable count", partialUpsertThrew)
        assertTrue("Corrupt index file must not be overwritten when rebuild count is insufficient", corruptMarker.contentEquals(partialScannerManager.indexFile.readBytes()))

        var partialRemoveThrew = false
        try {
            partialScannerManager.removeEntry("d1", testDek, diariesDir)
        } catch (_: StorageVerificationException) {
            partialRemoveThrew = true
        }
        assertTrue("removeEntry must refuse partial rebuild when rebuilt < decodable count", partialRemoveThrew)
        assertTrue(corruptMarker.contentEquals(partialScannerManager.indexFile.readBytes()))
    }
}
