package com.likkho.app.storage

import com.likkho.app.storage.model.DiaryRecord
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException

class UnsupportedSchemaVersionException(
    val fileVersion: Int,
    val maxSupportedVersion: Int
) : Exception("Schema version $fileVersion exceeds maximum supported version $maxSupportedVersion (read-only)")

data class DecodedDiary(
    val record: DiaryRecord,
    val schemaVersion: Int,
    val isReadOnly: Boolean
)

/**
 * Encodes and decodes the .lkd binary file format per STORAGE_SPEC Section 3:
 * - Header (plain): magic "LKD1", schemaVersion (int), IV (12 bytes random, har write par naya).
 * - Body: AES-256-GCM(DEK). AAD = magic + schemaVersion + diaryId (prevents file swap and downgrade attacks).
 * - Plaintext JSON (kotlinx.serialization): forward-compatible (unknown fields ignored).
 * - schemaVersion newer than app opens read-only and prevents overwriting.
 */
object DiaryFileCodec {

    const val MAGIC = 0x4C4B4431 // "LKD1"
    const val CURRENT_SCHEMA_VERSION = 1
    const val HEADER_SIZE = 4 + 4 + CryptoUtils.GCM_IV_SIZE_BYTES // 20 bytes

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Builds AAD = magic (4 bytes) + schemaVersion (4 bytes) + diaryId (UTF-8 bytes).
     * Binds the ciphertext strictly to this specific diary ID and version.
     */
    fun buildAad(schemaVersion: Int, diaryId: String): ByteArray {
        val idBytes = diaryId.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 4 + idBytes.size)
        buf.putInt(MAGIC)
        buf.putInt(schemaVersion)
        buf.put(idBytes)
        return buf.array()
    }

    /**
     * Encodes a [DiaryRecord] into raw .lkd file bytes encrypted with [dek].
     */
    fun encode(
        record: DiaryRecord,
        dek: ByteArray,
        schemaVersion: Int = CURRENT_SCHEMA_VERSION
    ): ByteArray {
        val plaintextJson = json.encodeToString(DiaryRecord.serializer(), record)
        val plaintextBytes = plaintextJson.toByteArray(Charsets.UTF_8)

        val iv = CryptoUtils.secureRandomBytes(CryptoUtils.GCM_IV_SIZE_BYTES)
        val aad = buildAad(schemaVersion, record.id)

        // AES-256-GCM encryption with fresh IV and bound AAD
        val encryptedWithIv = CryptoUtils.aesGcmEncrypt(dek, plaintextBytes, aad, iv)
        // Extract ciphertext + tag (without IV prefix since IV is in our plain header)
        val ciphertextLen = encryptedWithIv.size - CryptoUtils.GCM_IV_SIZE_BYTES
        val ciphertext = ByteArray(ciphertextLen)
        System.arraycopy(encryptedWithIv, CryptoUtils.GCM_IV_SIZE_BYTES, ciphertext, 0, ciphertextLen)

        val baos = ByteArrayOutputStream(HEADER_SIZE + ciphertext.size)
        DataOutputStream(baos).use { dos ->
            dos.writeInt(MAGIC)
            dos.writeInt(schemaVersion)
            dos.write(iv)
            dos.write(ciphertext)
        }
        return baos.toByteArray()
    }

    /**
     * Decodes and decrypts .lkd raw file bytes with [dek].
     * Throws [AEADBadTagException] if tampered, wrong key, or AAD mismatch (e.g. file swap).
     */
    fun decode(
        rawBytes: ByteArray,
        dek: ByteArray,
        expectedDiaryId: String
    ): DecodedDiary {
        require(rawBytes.size >= HEADER_SIZE + (CryptoUtils.GCM_TAG_SIZE_BITS / 8)) {
            "File size too small for .lkd header and GCM tag"
        }

        DataInputStream(ByteArrayInputStream(rawBytes)).use { dis ->
            val magic = dis.readInt()
            if (magic != MAGIC) {
                throw IllegalArgumentException("Invalid .lkd file magic: ${Integer.toHexString(magic)}")
            }
            val schemaVersion = dis.readInt()
            val iv = ByteArray(CryptoUtils.GCM_IV_SIZE_BYTES)
            dis.readFully(iv)

            val ciphertext = ByteArray(rawBytes.size - HEADER_SIZE)
            dis.readFully(ciphertext)

            // Reconstruct IV + ciphertext format for CryptoUtils.aesGcmDecrypt
            val ivAndCiphertext = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.size)
            System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.size, ciphertext.size)

            val aad = buildAad(schemaVersion, expectedDiaryId)
            val decryptedPlaintext = CryptoUtils.aesGcmDecrypt(dek, ivAndCiphertext, aad)

            val jsonString = String(decryptedPlaintext, Charsets.UTF_8)
            val record = json.decodeFromString(DiaryRecord.serializer(), jsonString)

            val isReadOnly = schemaVersion > CURRENT_SCHEMA_VERSION
            return DecodedDiary(record, schemaVersion, isReadOnly)
        }
    }
}
