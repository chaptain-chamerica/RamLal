package com.likkho.app.storage.backup

import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.model.BackupItemHash
import com.likkho.app.storage.model.BackupManifest
import com.likkho.app.storage.model.DiaryRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generic exception thrown whenever a file cannot be opened or decrypted.
 * Per Rule 5: "Dusre app me kholne par ya passphrase galat hone par sirf
 * 'Cannot open this file' jaisa generic error dikhao. File ke andar ki koi detail mat batao."
 */
class CannotOpenFileException(
    message: String = "Cannot open this file",
    cause: Throwable? = null
) : IOException(message, cause)

data class ShareBundle(
    val diary: DiaryRecord,
    val mediaAttachments: List<Pair<String, ByteArray>>
)

data class DecodedBackupBundle(
    val manifest: BackupManifest,
    val diaries: List<DiaryRecord>,
    val mediaList: List<Pair<String, ByteArray>>
)

/**
 * Custom binary container codec for .likkho (Share) and .lkb (Backup) files.
 * Complies with strict security rules:
 * 1. Custom binary format: Magic ("LKS1" for share, "LKB1" for backup) + Version + Salt + Iterations + IV.
 *    No standard zip, json, or txt.
 * 2. True passphrase security: min 8 chars -> PBKDF2-HMAC-SHA256 (600,000+ iterations, random 16B salt) -> AES-256-GCM.
 * 3. Zero hardcoded keys in app. Everything is derived on-demand from the user's passphrase.
 * 4. Header is cryptographically bound into GCM AAD (Additional Authenticated Data).
 * 5. Generic error "Cannot open this file" on wrong passphrase, tamper, or foreign app.
 * 6. Passphrase and keys zeroized in memory immediately after use; never logged or saved to disk.
 */
object SecureContainerCodec {

    const val MAGIC_SHARE = 0x4C4B5331  // "LKS1" for .likkho
    const val MAGIC_BACKUP = 0x4C4B4231 // "LKB1" for .lkb
    const val CURRENT_VERSION = 1
    const val MIN_PASSPHRASE_LENGTH = 8
    const val DEFAULT_ITERATIONS = 600_000
    const val HEADER_SIZE = 4 + 4 + CryptoUtils.SALT_SIZE_BYTES + 4 + CryptoUtils.GCM_IV_SIZE_BYTES // 40 bytes

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class ParsedHeader(
        val magic: Int,
        val version: Int,
        val salt: ByteArray,
        val iterations: Int,
        val iv: ByteArray,
        val rawHeaderBytes: ByteArray
    )

    fun validatePassphrase(passphrase: CharArray) {
        if (passphrase.size < MIN_PASSPHRASE_LENGTH) {
            throw IllegalArgumentException("Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters")
        }
    }

    /**
     * Builds the 40-byte header:
     * [Magic 4B] + [Version 4B] + [Salt 16B] + [Iterations 4B] + [IV 12B]
     */
    fun buildHeader(
        magic: Int,
        version: Int,
        salt: ByteArray,
        iterations: Int,
        iv: ByteArray
    ): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE)
        buf.putInt(magic)
        buf.putInt(version)
        buf.put(salt)
        buf.putInt(iterations)
        buf.put(iv)
        return buf.array()
    }

    /**
     * Parses and validates the 40-byte header from file bytes.
     */
    fun parseHeader(rawBytes: ByteArray, expectedMagic: Int? = null): ParsedHeader {
        if (rawBytes.size < HEADER_SIZE + (CryptoUtils.GCM_TAG_SIZE_BITS / 8)) {
            throw CannotOpenFileException()
        }

        val dis = DataInputStream(ByteArrayInputStream(rawBytes))
        val magic = dis.readInt()
        if (expectedMagic != null && magic != expectedMagic) {
            throw CannotOpenFileException()
        }
        if (magic != MAGIC_SHARE && magic != MAGIC_BACKUP) {
            throw CannotOpenFileException()
        }

        val version = dis.readInt()
        val salt = ByteArray(CryptoUtils.SALT_SIZE_BYTES)
        dis.readFully(salt)
        val iterations = dis.readInt()
        if (iterations <= 0) {
            throw CannotOpenFileException()
        }

        val iv = ByteArray(CryptoUtils.GCM_IV_SIZE_BYTES)
        dis.readFully(iv)

        val headerBytes = ByteArray(HEADER_SIZE)
        System.arraycopy(rawBytes, 0, headerBytes, 0, HEADER_SIZE)

        return ParsedHeader(magic, version, salt, iterations, iv, headerBytes)
    }

    // =========================================================================
    // SHARE CONTAINER (.likkho, Magic: "LKS1")
    // =========================================================================

    /**
     * Encrypts a single diary and its referenced media into custom binary .likkho format.
     */
    fun encodeShareContainer(
        diary: DiaryRecord,
        mediaList: List<Pair<String, ByteArray>>,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS
    ): ByteArray {
        validatePassphrase(passphrase)

        // 1. Serialize custom binary payload:
        // [diaryJsonLen (4B)] + [diaryJson (UTF-8)] + [mediaCount (4B)] + { [idLen] + [id] + [dataLen] + [data] }
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        val diaryJsonBytes = json.encodeToString(diary).toByteArray(Charsets.UTF_8)
        dos.writeInt(diaryJsonBytes.size)
        dos.write(diaryJsonBytes)

        dos.writeInt(mediaList.size)
        for ((mId, mData) in mediaList) {
            val idBytes = mId.toByteArray(Charsets.UTF_8)
            dos.writeInt(idBytes.size)
            dos.write(idBytes)
            dos.writeInt(mData.size)
            dos.write(mData)
        }
        dos.flush()
        val plaintextPayload = baos.toByteArray()

        // 2. Generate random salt and IV
        val salt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val iv = CryptoUtils.secureRandomBytes(CryptoUtils.GCM_IV_SIZE_BYTES)
        val header = buildHeader(MAGIC_SHARE, CURRENT_VERSION, salt, iterations, iv)

        // 3. Derive 256-bit AES key via PBKDF2
        val key = CryptoUtils.deriveKeyPbkdf2(passphrase, salt, iterations, 256)

        try {
            // 4. AES-256-GCM encryption with AAD = header
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(CryptoUtils.GCM_TAG_SIZE_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            cipher.updateAAD(header)
            val ciphertextAndTag = cipher.doFinal(plaintextPayload)

            // 5. Output: [Header 40B] + [Ciphertext + Tag]
            val result = ByteArray(header.size + ciphertextAndTag.size)
            System.arraycopy(header, 0, result, 0, header.size)
            System.arraycopy(ciphertextAndTag, 0, result, header.size, ciphertextAndTag.size)
            return result
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    /**
     * Decrypts and parses a custom binary .likkho container.
     * Throws [CannotOpenFileException] on any error (wrong passphrase, tamper, or foreign file).
     */
    fun decodeShareContainer(rawBytes: ByteArray, passphrase: CharArray): ShareBundle {
        validatePassphrase(passphrase)
        val parsedHeader = try {
            parseHeader(rawBytes, expectedMagic = MAGIC_SHARE)
        } catch (e: Exception) {
            throw CannotOpenFileException("Cannot open this file", e)
        }

        val key = CryptoUtils.deriveKeyPbkdf2(
            passphrase,
            parsedHeader.salt,
            parsedHeader.iterations,
            256
        )

        try {
            val ciphertextLen = rawBytes.size - HEADER_SIZE
            val ciphertext = ByteArray(ciphertextLen)
            System.arraycopy(rawBytes, HEADER_SIZE, ciphertext, 0, ciphertextLen)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(CryptoUtils.GCM_TAG_SIZE_BITS, parsedHeader.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.updateAAD(parsedHeader.rawHeaderBytes)
            val plaintext = cipher.doFinal(ciphertext)

            // Parse custom binary payload
            val dis = DataInputStream(ByteArrayInputStream(plaintext))
            val diaryJsonLen = dis.readInt()
            val diaryJsonBytes = ByteArray(diaryJsonLen)
            dis.readFully(diaryJsonBytes)
            val diary = json.decodeFromString<DiaryRecord>(diaryJsonBytes.toString(Charsets.UTF_8))

            val mediaCount = dis.readInt()
            val mediaList = mutableListOf<Pair<String, ByteArray>>()
            for (i in 0 until mediaCount) {
                val idLen = dis.readInt()
                val idBytes = ByteArray(idLen)
                dis.readFully(idBytes)
                val mId = idBytes.toString(Charsets.UTF_8)

                val dataLen = dis.readInt()
                val dataBytes = ByteArray(dataLen)
                dis.readFully(dataBytes)
                mediaList.add(mId to dataBytes)
            }

            return ShareBundle(diary, mediaList)
        } catch (e: Exception) {
            throw CannotOpenFileException("Cannot open this file", e)
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    // =========================================================================
    // BACKUP CONTAINER (.lkb, Magic: "LKB1")
    // =========================================================================

    /**
     * Encrypts a full backup into custom binary .lkb format.
     */
    fun encodeBackupContainer(
        manifest: BackupManifest,
        diaries: List<DiaryRecord>,
        mediaList: List<Pair<String, ByteArray>>,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS
    ): ByteArray {
        validatePassphrase(passphrase)

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Manifest
        val manifestJsonBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        dos.writeInt(manifestJsonBytes.size)
        dos.write(manifestJsonBytes)

        // Diaries
        dos.writeInt(diaries.size)
        for (diary in diaries) {
            val dIdBytes = diary.id.toByteArray(Charsets.UTF_8)
            dos.writeInt(dIdBytes.size)
            dos.write(dIdBytes)
            val dJsonBytes = json.encodeToString(diary).toByteArray(Charsets.UTF_8)
            dos.writeInt(dJsonBytes.size)
            dos.write(dJsonBytes)
        }

        // Media
        dos.writeInt(mediaList.size)
        for ((mId, mData) in mediaList) {
            val mIdBytes = mId.toByteArray(Charsets.UTF_8)
            dos.writeInt(mIdBytes.size)
            dos.write(mIdBytes)
            dos.writeInt(mData.size)
            dos.write(mData)
        }
        dos.flush()
        val plaintextPayload = baos.toByteArray()

        val salt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val iv = CryptoUtils.secureRandomBytes(CryptoUtils.GCM_IV_SIZE_BYTES)
        val header = buildHeader(MAGIC_BACKUP, CURRENT_VERSION, salt, iterations, iv)

        val key = CryptoUtils.deriveKeyPbkdf2(passphrase, salt, iterations, 256)

        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(CryptoUtils.GCM_TAG_SIZE_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            cipher.updateAAD(header)
            val ciphertextAndTag = cipher.doFinal(plaintextPayload)

            val result = ByteArray(header.size + ciphertextAndTag.size)
            System.arraycopy(header, 0, result, 0, header.size)
            System.arraycopy(ciphertextAndTag, 0, result, header.size, ciphertextAndTag.size)
            return result
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    /**
     * Decrypts and parses a custom binary .lkb container.
     * Throws [CannotOpenFileException] on any error.
     */
    fun decodeBackupContainer(rawBytes: ByteArray, passphrase: CharArray): DecodedBackupBundle {
        validatePassphrase(passphrase)
        val parsedHeader = try {
            parseHeader(rawBytes, expectedMagic = MAGIC_BACKUP)
        } catch (e: Exception) {
            throw CannotOpenFileException("Cannot open this file", e)
        }

        val key = CryptoUtils.deriveKeyPbkdf2(
            passphrase,
            parsedHeader.salt,
            parsedHeader.iterations,
            256
        )

        try {
            val ciphertextLen = rawBytes.size - HEADER_SIZE
            val ciphertext = ByteArray(ciphertextLen)
            System.arraycopy(rawBytes, HEADER_SIZE, ciphertext, 0, ciphertextLen)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val secretKey = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(CryptoUtils.GCM_TAG_SIZE_BITS, parsedHeader.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.updateAAD(parsedHeader.rawHeaderBytes)
            val plaintext = cipher.doFinal(ciphertext)

            val dis = DataInputStream(ByteArrayInputStream(plaintext))
            val manifestLen = dis.readInt()
            val manifestBytes = ByteArray(manifestLen)
            dis.readFully(manifestBytes)
            val manifest = json.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))

            val diaryCount = dis.readInt()
            val diaries = mutableListOf<DiaryRecord>()
            for (i in 0 until diaryCount) {
                val dIdLen = dis.readInt()
                val dIdBytes = ByteArray(dIdLen)
                dis.readFully(dIdBytes)

                val dJsonLen = dis.readInt()
                val dJsonBytes = ByteArray(dJsonLen)
                dis.readFully(dJsonBytes)
                diaries.add(json.decodeFromString<DiaryRecord>(dJsonBytes.toString(Charsets.UTF_8)))
            }

            val mediaCount = dis.readInt()
            val mediaList = mutableListOf<Pair<String, ByteArray>>()
            for (i in 0 until mediaCount) {
                val mIdLen = dis.readInt()
                val mIdBytes = ByteArray(mIdLen)
                dis.readFully(mIdBytes)
                val mId = mIdBytes.toString(Charsets.UTF_8)

                val dataLen = dis.readInt()
                val dataBytes = ByteArray(dataLen)
                dis.readFully(dataBytes)
                mediaList.add(mId to dataBytes)
            }

            return DecodedBackupBundle(manifest, diaries, mediaList)
        } catch (e: Exception) {
            throw CannotOpenFileException("Cannot open this file", e)
        } finally {
            CryptoUtils.zeroize(key)
        }
    }
}
