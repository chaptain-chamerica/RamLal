package com.likkho.app.storage

import com.likkho.app.storage.model.ContentBlockRecord
import com.likkho.app.storage.model.DiaryRecord
import com.likkho.app.storage.model.SpanStyleRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class DiaryFileCodecTest {

    private val testDek = CryptoUtils.secureRandomBytes(32)

    private fun sampleDiary(id: String = "diary_123"): DiaryRecord = DiaryRecord(
        id = id,
        heading = "My First Secure Diary",
        createdAt = 1700000000000L,
        updatedAt = 1700000010000L,
        pinned = true,
        reminderAt = 1700050000000L,
        plainPreview = "A beautiful sunny day in the park.",
        content = listOf(
            ContentBlockRecord(
                type = "heading1",
                text = "Sunny Day",
                spans = listOf(SpanStyleRecord(start = 0, end = 5, style = "bold"))
            ),
            ContentBlockRecord(
                type = "paragraph",
                text = "A beautiful sunny day in the park.",
                spans = emptyList()
            )
        )
    )

    @Test
    fun roundTrip_encodesAndDecodesSuccessfully() {
        val original = sampleDiary()
        val encodedBytes = DiaryFileCodec.encode(original, testDek)

        assertTrue(encodedBytes.size > DiaryFileCodec.HEADER_SIZE)

        val decoded = DiaryFileCodec.decode(encodedBytes, testDek, expectedDiaryId = original.id)
        assertEquals(original.id, decoded.record.id)
        assertEquals(original.heading, decoded.record.heading)
        assertEquals(original.pinned, decoded.record.pinned)
        assertEquals(original.content.size, decoded.record.content.size)
        assertEquals("Sunny Day", decoded.record.content[0].text)
        assertFalse(decoded.isReadOnly)
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedCiphertext_failsGcmAuthenticationTag() {
        val original = sampleDiary()
        val encodedBytes = DiaryFileCodec.encode(original, testDek)

        // Tamper a byte in the ciphertext body
        encodedBytes[encodedBytes.size - 5] = (encodedBytes[encodedBytes.size - 5].toInt() xor 0x55).toByte()

        DiaryFileCodec.decode(encodedBytes, testDek, expectedDiaryId = original.id)
    }

    @Test(expected = AEADBadTagException::class)
    fun swappedDiaryId_failsAadVerification() {
        val original = sampleDiary("diary_A")
        val encodedBytes = DiaryFileCodec.encode(original, testDek)

        // Attacker attempts to swap this file into diary_B's slot
        DiaryFileCodec.decode(encodedBytes, testDek, expectedDiaryId = "diary_B")
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongKey_failsDecryption() {
        val original = sampleDiary()
        val encodedBytes = DiaryFileCodec.encode(original, testDek)
        val wrongDek = CryptoUtils.secureRandomBytes(32)

        DiaryFileCodec.decode(encodedBytes, wrongDek, expectedDiaryId = original.id)
    }

    @Test
    fun forwardCompatibility_ignoresUnknownJsonFields() {
        // Construct JSON with unknown extra fields (e.g. from future version 2)
        val futureJson = """
            {
                "id": "future_diary",
                "heading": "From the Future",
                "createdAt": 1700000000000,
                "updatedAt": 1700000000000,
                "unknownFieldA": "some new feature data",
                "quantumAIAnalysis": {"score": 99.9, "tags": ["future"]},
                "content": []
            }
        """.trimIndent()

        val iv = CryptoUtils.secureRandomBytes(CryptoUtils.GCM_IV_SIZE_BYTES)
        val aad = DiaryFileCodec.buildAad(DiaryFileCodec.CURRENT_SCHEMA_VERSION, "future_diary")
        val encrypted = CryptoUtils.aesGcmEncrypt(testDek, futureJson.toByteArray(Charsets.UTF_8), aad, iv)

        val ciphertext = ByteArray(encrypted.size - CryptoUtils.GCM_IV_SIZE_BYTES)
        System.arraycopy(encrypted, CryptoUtils.GCM_IV_SIZE_BYTES, ciphertext, 0, ciphertext.size)

        val fullBytes = java.io.ByteArrayOutputStream().apply {
            java.io.DataOutputStream(this).use { dos ->
                dos.writeInt(DiaryFileCodec.MAGIC)
                dos.writeInt(DiaryFileCodec.CURRENT_SCHEMA_VERSION)
                dos.write(iv)
                dos.write(ciphertext)
            }
        }.toByteArray()

        val decoded = DiaryFileCodec.decode(fullBytes, testDek, "future_diary")
        assertEquals("future_diary", decoded.record.id)
        assertEquals("From the Future", decoded.record.heading)
    }

    @Test
    fun newerSchemaVersion_marksReadOnly() {
        val original = sampleDiary("future_version")
        val futureVersion = DiaryFileCodec.CURRENT_SCHEMA_VERSION + 2
        val encodedBytes = DiaryFileCodec.encode(original, testDek, schemaVersion = futureVersion)

        val decoded = DiaryFileCodec.decode(encodedBytes, testDek, "future_version")
        assertEquals(futureVersion, decoded.schemaVersion)
        assertTrue("Newer schemaVersion must be marked read-only", decoded.isReadOnly)
    }
}
