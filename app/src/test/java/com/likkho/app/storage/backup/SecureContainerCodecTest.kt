package com.likkho.app.storage.backup

import com.likkho.app.storage.model.BackupItemHash
import com.likkho.app.storage.model.BackupManifest
import com.likkho.app.storage.model.CanvasImageRecord
import com.likkho.app.storage.model.DiaryRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecureContainerCodecTest {

    private val validPassphrase = "SecretPassphrase123!".toCharArray()
    private val fastIterations = 5_000

    @Test
    fun shareContainer_crossDeviceRoundTrip_withPassphrase() {
        // Device A: Create single diary with an attached image
        val imagePayload = "Raw Camera Image Data 987654321".toByteArray(Charsets.UTF_8)
        val diary = DiaryRecord(
            id = "d_share_1",
            heading = "Trip Memories",
            createdAt = 1000L,
            updatedAt = 2000L,
            images = listOf(CanvasImageRecord(mediaId = "media_img_1")),
            plainPreview = "A beautiful memory"
        )
        val mediaList = listOf("media_img_1" to imagePayload)

        val encryptedBytes = SecureContainerCodec.encodeShareContainer(
            diary = diary,
            mediaList = mediaList,
            passphrase = validPassphrase,
            iterations = fastIterations
        )

        // Verify header
        assertTrue(encryptedBytes.size >= SecureContainerCodec.HEADER_SIZE + 16)
        assertEquals(0x4C.toByte(), encryptedBytes[0]) // 'L'
        assertEquals(0x4B.toByte(), encryptedBytes[1]) // 'K'
        assertEquals(0x53.toByte(), encryptedBytes[2]) // 'S'
        assertEquals(0x31.toByte(), encryptedBytes[3]) // '1'

        // Device B: Decrypt with exact passphrase
        val decodedBundle = SecureContainerCodec.decodeShareContainer(encryptedBytes, validPassphrase)
        assertEquals("d_share_1", decodedBundle.diary.id)
        assertEquals("Trip Memories", decodedBundle.diary.heading)
        assertEquals(1, decodedBundle.mediaAttachments.size)
        assertEquals("media_img_1", decodedBundle.mediaAttachments[0].first)
        assertArrayEquals(imagePayload, decodedBundle.mediaAttachments[0].second)
    }

    @Test
    fun wrongPassphrase_throwsCannotOpenFileException_genericMessage() {
        val diary = DiaryRecord(id = "d1", heading = "Confidential")
        val encryptedBytes = SecureContainerCodec.encodeShareContainer(
            diary = diary,
            mediaList = emptyList(),
            passphrase = validPassphrase,
            iterations = fastIterations
        )

        val wrongPassphrase = "WrongPassword999!".toCharArray()
        try {
            SecureContainerCodec.decodeShareContainer(encryptedBytes, wrongPassphrase)
            fail("Must throw CannotOpenFileException on incorrect passphrase")
        } catch (e: CannotOpenFileException) {
            assertEquals("Cannot open this file", e.message)
        }
    }

    @Test
    fun tamperedCiphertextByte_throwsCannotOpenFileException() {
        val diary = DiaryRecord(id = "d1", heading = "Tamper Test")
        val encryptedBytes = SecureContainerCodec.encodeShareContainer(
            diary = diary,
            mediaList = emptyList(),
            passphrase = validPassphrase,
            iterations = fastIterations
        )

        // Alter 1 byte in ciphertext body
        val tampered = encryptedBytes.copyOf()
        tampered[tampered.size - 2] = (tampered[tampered.size - 2].toInt() xor 0xFF).toByte()

        try {
            SecureContainerCodec.decodeShareContainer(tampered, validPassphrase)
            fail("Must throw CannotOpenFileException on tampered ciphertext")
        } catch (e: CannotOpenFileException) {
            assertEquals("Cannot open this file", e.message)
        }
    }

    @Test
    fun tamperedHeaderByte_AADViolation_throwsCannotOpenFileException() {
        val diary = DiaryRecord(id = "d1", heading = "Header Tamper Test")
        val encryptedBytes = SecureContainerCodec.encodeShareContainer(
            diary = diary,
            mediaList = emptyList(),
            passphrase = validPassphrase,
            iterations = fastIterations
        )

        // Alter 1 byte in the salt area of the header (offset 10)
        val tampered = encryptedBytes.copyOf()
        tampered[10] = (tampered[10].toInt() xor 0xAA).toByte()

        try {
            SecureContainerCodec.decodeShareContainer(tampered, validPassphrase)
            fail("Must throw CannotOpenFileException on tampered AAD header")
        } catch (e: CannotOpenFileException) {
            assertEquals("Cannot open this file", e.message)
        }
    }

    @Test
    fun invalidMagic_throwsCannotOpenFileException() {
        val diary = DiaryRecord(id = "d1", heading = "Magic Test")
        val encryptedBytes = SecureContainerCodec.encodeShareContainer(
            diary = diary,
            mediaList = emptyList(),
            passphrase = validPassphrase,
            iterations = fastIterations
        )

        // Corrupt magic bytes
        val tampered = encryptedBytes.copyOf()
        tampered[0] = 'X'.code.toByte()

        try {
            SecureContainerCodec.decodeShareContainer(tampered, validPassphrase)
            fail("Must throw CannotOpenFileException on invalid magic")
        } catch (e: CannotOpenFileException) {
            assertEquals("Cannot open this file", e.message)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun passphraseUnder8Chars_isRejected() {
        val diary = DiaryRecord(id = "d1", heading = "Short Passphrase")
        SecureContainerCodec.encodeShareContainer(
            diary = diary,
            mediaList = emptyList(),
            passphrase = "short".toCharArray(), // Only 5 chars
            iterations = fastIterations
        )
    }

    @Test
    fun backupContainer_customBinaryRoundTrip() {
        val diary1 = DiaryRecord(id = "b1", heading = "Backup 1")
        val diary2 = DiaryRecord(id = "b2", heading = "Backup 2")
        val mediaPayload = "Audio clip sound waveform".toByteArray(Charsets.UTF_8)
        val mediaList = listOf("m1" to mediaPayload)

        val manifest = BackupManifest(
            schemaVersion = 1,
            appVersion = "1.0.0",
            diaryCount = 2,
            mediaCount = 1,
            items = listOf(
                BackupItemHash("diaries/b1", "hash1", 100),
                BackupItemHash("diaries/b2", "hash2", 100),
                BackupItemHash("media/m1", "hash3", mediaPayload.size.toLong())
            )
        )

        val encryptedBytes = SecureContainerCodec.encodeBackupContainer(
            manifest = manifest,
            diaries = listOf(diary1, diary2),
            mediaList = mediaList,
            passphrase = validPassphrase,
            iterations = fastIterations
        )

        // Verify "LKB1" magic in header
        assertEquals(0x4C.toByte(), encryptedBytes[0]) // 'L'
        assertEquals(0x4B.toByte(), encryptedBytes[1]) // 'K'
        assertEquals(0x42.toByte(), encryptedBytes[2]) // 'B'
        assertEquals(0x31.toByte(), encryptedBytes[3]) // '1'

        val decoded = SecureContainerCodec.decodeBackupContainer(encryptedBytes, validPassphrase)
        assertEquals(2, decoded.diaries.size)
        assertEquals("Backup 1", decoded.diaries[0].heading)
        assertEquals("Backup 2", decoded.diaries[1].heading)
        assertEquals(1, decoded.mediaList.size)
        assertEquals("m1", decoded.mediaList[0].first)
        assertArrayEquals(mediaPayload, decoded.mediaList[0].second)
    }
}
