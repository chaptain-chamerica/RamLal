package com.likkho.app.storage

import com.likkho.app.storage.model.CanvasImageRecord
import com.likkho.app.storage.model.DiaryRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class EncryptedMediaRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var mediaRepository: EncryptedMediaRepository
    private lateinit var diaryRepository: DiaryRepository
    private val testDek = CryptoUtils.secureRandomBytes(32)

    private var fakeCurrentTime = 1_700_000_000_000L
    private val testClock = object : ClockProvider {
        override fun elapsedRealtimeMs(): Long = fakeCurrentTime
        override fun wallClockMs(): Long = fakeCurrentTime
    }

    @Before
    fun setUp() {
        vaultDir = tempFolder.newFolder("v1_media_test")
        mediaRepository = EncryptedMediaRepository(VaultId.PRIMARY, vaultDir, testClock)
        diaryRepository = DiaryRepository(VaultId.PRIMARY, vaultDir)
        mediaRepository.ensureDirs()
        diaryRepository.ensureDirs()
    }

    @Test
    fun importAndReadMediaBytes_roundTrip() {
        val mediaId = "photo_123"
        val payload = "Hello, high-resolution diary attachment!".toByteArray(Charsets.UTF_8)

        val importedId = mediaRepository.importMediaBytes(mediaId, payload, testDek)
        assertEquals(mediaId, importedId)
        assertTrue(mediaRepository.exists(mediaId))

        // Read decrypted bytes
        val decrypted = mediaRepository.readDecryptedBytes(mediaId, testDek)
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun streamingDecryption_handlesLargePayload() {
        val mediaId = "audio_record_456"
        // 256 KB test payload exceeding 64KB chunk size
        val largePayload = ByteArray(256 * 1024) { (it % 251).toByte() }

        mediaRepository.importMedia(mediaId, ByteArrayInputStream(largePayload), testDek, largePayload.size.toLong())

        val decryptedStream = mediaRepository.openDecryptedStream(mediaId, testDek)
        val readBack = decryptedStream.readBytes()
        decryptedStream.close()

        assertArrayEquals(largePayload, readBack)
    }

    @Test
    fun wrongKey_failsDecryption() {
        val mediaId = "secret_pic"
        val payload = "Secret photo bytes".toByteArray(Charsets.UTF_8)
        mediaRepository.importMediaBytes(mediaId, payload, testDek)

        val wrongDek = CryptoUtils.secureRandomBytes(32)
        try {
            mediaRepository.readDecryptedBytes(mediaId, wrongDek)
            fail("Decryption with wrong DEK must fail")
        } catch (e: Exception) {
            // Expected Tink AEAD decryption failure
        }
    }

    @Test
    fun tamperedCiphertext_failsDecryption() {
        val mediaId = "tampered_audio"
        val payload = "Audio waveform data".toByteArray(Charsets.UTF_8)
        mediaRepository.importMediaBytes(mediaId, payload, testDek)

        val mediaFile = mediaRepository.getMediaFile(mediaId)
        val fileBytes = mediaFile.readBytes()
        // Tamper byte in ciphertext
        fileBytes[fileBytes.size - 2] = (fileBytes[fileBytes.size - 2].toInt() xor 0xAA).toByte()
        mediaFile.writeBytes(fileBytes)

        try {
            mediaRepository.readDecryptedBytes(mediaId, testDek)
            fail("Decryption of tampered media must fail")
        } catch (e: Exception) {
            // Expected tag check failure
        }
    }

    @Test
    fun aadBinding_swappedMediaIdFailsDecryption() {
        val mediaId1 = "media_item_1"
        val mediaId2 = "media_item_2"
        mediaRepository.importMediaBytes(mediaId1, "Original Media 1".toByteArray(), testDek)

        // Rename file 1 to file 2 (tamper swap attempt)
        val file1 = mediaRepository.getMediaFile(mediaId1)
        val file2 = mediaRepository.getMediaFile(mediaId2)
        file1.copyTo(file2)

        try {
            mediaRepository.readDecryptedBytes(mediaId2, testDek)
            fail("Swapped media file must fail AAD verification")
        } catch (e: Exception) {
            // Expected AAD mismatch failure
        }
    }

    @Test
    fun orphanCleanup_removesOldUnreferencedMedia_andKeepsNewerMedia() {
        val oldUnreferenced = "orphan_old"
        val newUnreferenced = "orphan_new"
        val referencedMedia = "referenced_pic"

        mediaRepository.importMediaBytes(oldUnreferenced, "old bytes".toByteArray(), testDek)
        mediaRepository.importMediaBytes(newUnreferenced, "new bytes".toByteArray(), testDek)
        mediaRepository.importMediaBytes(referencedMedia, "referenced bytes".toByteArray(), testDek)

        val oldFile = mediaRepository.getMediaFile(oldUnreferenced)
        val newFile = mediaRepository.getMediaFile(newUnreferenced)

        // Set old file modified time to 25 hours ago
        val twentyFiveHoursMs = 25 * 60 * 60 * 1000L
        oldFile.setLastModified(fakeCurrentTime - twentyFiveHoursMs)

        // Set new file modified time to 1 hour ago
        val oneHourMs = 1 * 60 * 60 * 1000L
        newFile.setLastModified(fakeCurrentTime - oneHourMs)

        val referencedSet = setOf(referencedMedia)

        // Run orphan cleanup
        val cleaned = mediaRepository.cleanupOrphans(referencedSet)
        assertEquals(1, cleaned)

        assertFalse("Old unreferenced file (>24h) must be removed", oldFile.exists())
        assertTrue("New unreferenced file (<24h) must be kept to prevent race conditions", newFile.exists())
        assertTrue("Referenced media must always be kept", mediaRepository.exists(referencedMedia))
    }

    @Test
    fun deleteDiary_transactionallyDeletesUnreferencedMedia() {
        val mediaId1 = "diary_pic_1"
        val mediaId2 = "diary_shared_pic"

        mediaRepository.importMediaBytes(mediaId1, "Pic 1".toByteArray(), testDek)
        mediaRepository.importMediaBytes(mediaId2, "Shared Pic".toByteArray(), testDek)

        // Diary A references pic 1 and shared pic
        val diaryA = DiaryRecord(
            id = "diary_A",
            heading = "Diary A",
            images = listOf(
                CanvasImageRecord(mediaId = mediaId1),
                CanvasImageRecord(mediaId = mediaId2)
            )
        )
        diaryRepository.saveDiary(diaryA, testDek)

        // Diary B also references shared pic
        val diaryB = DiaryRecord(
            id = "diary_B",
            heading = "Diary B",
            images = listOf(
                CanvasImageRecord(mediaId = mediaId2)
            )
        )
        diaryRepository.saveDiary(diaryB, testDek)

        assertTrue(mediaRepository.exists(mediaId1))
        assertTrue(mediaRepository.exists(mediaId2))

        // Delete Diary A
        diaryRepository.deleteDiary(diaryA.id, testDek, mediaRepository)

        assertFalse("mediaId1 was only referenced by diary A and must be deleted", mediaRepository.exists(mediaId1))
        assertTrue("mediaId2 is still referenced by diary B and must NOT be deleted", mediaRepository.exists(mediaId2))
    }

    @Test
    fun importMedia_verifiesEntireStreamToEof_andDeletesTmpOnTrailingCorruption() {
        val mediaId = "large_corrupt_tail"
        // 200 KB (> 3 Tink 64KB segments) so first-1KB check alone would miss tail corruption
        val largePayload = ByteArray(200 * 1024) { (it % 239).toByte() }
        val expectedTmp = File(mediaRepository.mediaDir, "$mediaId.lkm.tmp")

        var thrown = false
        try {
            mediaRepository.importMediaInternal(
                mediaId = mediaId,
                inputStream = ByteArrayInputStream(largePayload),
                dek = testDek,
                estimatedSizeBytes = largePayload.size.toLong()
            ) { tmpFile ->
                val bytes = tmpFile.readBytes()
                // Tamper near the very end of the final segment (well beyond first 1024 bytes)
                bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0xFF).toByte()
                tmpFile.writeBytes(bytes)
            }
        } catch (e: StorageVerificationException) {
            thrown = true
        }

        assertTrue("Verification must drain all chunks to EOF and catch tail corruption", thrown)
        assertFalse(".tmp file must be deleted on verification failure", expectedTmp.exists())
        assertFalse("Target .lkm must NOT exist when verification fails", mediaRepository.exists(mediaId))
    }

    @Test
    fun importMedia_onInputStreamError_deletesTmpWithoutFinalizingTarget() {
        val mediaId = "broken_source_stream"
        val expectedTmp = File(mediaRepository.mediaDir, "$mediaId.lkm.tmp")
        val failingStream = object : java.io.InputStream() {
            var count = 0
            override fun read(): Int {
                if (count >= 4096) throw IOException("Simulated stream disconnect")
                count++
                return 0x42
            }
        }

        var thrown = false
        try {
            mediaRepository.importMedia(mediaId, failingStream, testDek, 8192L)
        } catch (e: IOException) {
            thrown = true
        }

        assertTrue(thrown)
        assertFalse(".tmp must be deleted on input stream failure", expectedTmp.exists())
        assertFalse("Target .lkm must NOT be finalized on stream failure", mediaRepository.exists(mediaId))
    }

    @Test
    fun seekableEncryptedMediaReader_supportsRandomSeekAcrossSegments_andClosesCleanly() {
        val mediaId = "audio_seekable_stream"
        // 300 KB (> 4 Tink 64 KB segments) simulating a multi-minute audio track
        val totalLen = 300 * 1024
        val payload = ByteArray(totalLen) { idx -> ((idx * 37) and 0xFF).toByte() }
        mediaRepository.importMedia(mediaId, ByteArrayInputStream(payload), testDek, totalLen.toLong())

        mediaRepository.openSeekableMediaReader(mediaId, testDek).use { reader ->
            assertEquals(totalLen.toLong(), reader.getSize())

            // Seek forward into segment 3 (offset 200_000), read 4096 bytes
            val midBuf = ByteArray(4096)
            val midRead = reader.readAt(200_000L, midBuf, 0, midBuf.size)
            assertEquals(4096, midRead)
            assertArrayEquals(payload.copyOfRange(200_000, 204_096), midBuf)

            // Seek backward to segment 0 (offset 123), read 1024 bytes
            val startBuf = ByteArray(1024)
            val startRead = reader.readAt(123L, startBuf, 0, startBuf.size)
            assertEquals(1024, startRead)
            assertArrayEquals(payload.copyOfRange(123, 123 + 1024), startBuf)

            // Cross-segment boundary read (around 64 KB boundary)
            val crossBuf = ByteArray(8192)
            val crossOffset = (64 * 1024) - 512
            val crossRead = reader.readAt(crossOffset.toLong(), crossBuf, 0, crossBuf.size)
            assertEquals(8192, crossRead)
            assertArrayEquals(payload.copyOfRange(crossOffset, crossOffset + 8192), crossBuf)

            // Read at EOF returns -1
            assertEquals(-1, reader.readAt(totalLen.toLong(), ByteArray(16), 0, 16))
        }
    }

    @Test
    fun deleteMedia_retriesOnceOnFailure_andCheckedResultReportsFailedIds() {
        var transientAttempts = 0
        val retryRepo = EncryptedMediaRepository(VaultId.PRIMARY, vaultDir, testClock) { file ->
            transientAttempts++
            if (transientAttempts == 1) false else file.delete()
        }
        retryRepo.importMediaBytes("m_retry_ok", "retry".toByteArray(), testDek)
        assertTrue(retryRepo.deleteMedia("m_retry_ok"))
        assertEquals(2, transientAttempts)
        assertFalse(retryRepo.exists("m_retry_ok"))

        var hardFailAttempts = 0
        val failingRepo = EncryptedMediaRepository(VaultId.PRIMARY, vaultDir, testClock) { _ ->
            hardFailAttempts++
            false
        }
        failingRepo.importMediaBytes("m_always_fail", "locked".toByteArray(), testDek)
        val res = failingRepo.deleteUnreferencedMediaForDiaryChecked(setOf("m_always_fail"), emptySet())
        assertEquals(0, res.deletedCount)
        assertEquals(listOf("m_always_fail"), res.failedIds)
        assertEquals(2, hardFailAttempts)
        assertTrue(failingRepo.exists("m_always_fail"))
    }
}

