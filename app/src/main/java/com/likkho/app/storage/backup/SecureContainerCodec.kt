package com.likkho.app.storage.backup

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.model.BackupDiarySummary
import com.likkho.app.storage.model.BackupContents
import com.likkho.app.storage.model.BackupItemHash
import com.likkho.app.storage.model.BackupManifest
import com.likkho.app.storage.model.DiaryRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
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

data class StagedDiaryEntry(
    val id: String,
    val file: File
)

data class StagedBackupData(
    val manifest: BackupManifest,
    val diaries: List<StagedDiaryEntry>,
    val mediaFiles: List<Pair<String, File>>
)

data class StagedShareData(
    val diary: DiaryRecord,
    val mediaFiles: List<Pair<String, File>>
)

/**
 * Custom binary container codec for .likkho (Share) and .lkb (Backup) files.
 * Complies with strict security rules:
 * 1. Custom binary format: Magic ("LKS1" for share, "LKB1" for backup) + Version + Salt + Iterations (+ IV for v1).
 *    Version 2 streams encrypted payloads chunk-by-chunk (64 KB segments) via Tink AesGcmHkdfStreaming
 *    so 200 MB+ vaults never accumulate ByteArrays in RAM.
 * 2. True passphrase security: min 8 chars -> PBKDF2-HMAC-SHA256 (600,000+ iterations, random 16B salt) -> AES-256.
 * 3. Zero hardcoded keys in app. Everything is derived on-demand from the user's passphrase.
 * 4. Header is cryptographically bound into AEAD AAD (Additional Authenticated Data).
 * 5. Generic error "Cannot open this file" on wrong passphrase, tamper, or foreign app.
 * 6. Passphrase and keys zeroized in memory immediately after use; never logged or saved to disk.
 */
object SecureContainerCodec {

    const val MAGIC_SHARE = 0x4C4B5331  // "LKS1" for .likkho
    const val MAGIC_BACKUP = 0x4C4B4231 // "LKB1" for .lkb
    const val CURRENT_VERSION = 1
    const val STREAMING_VERSION = 2
    const val MIN_PASSPHRASE_LENGTH = 8
    const val DEFAULT_ITERATIONS = 600_000
    const val HEADER_SIZE = 4 + 4 + CryptoUtils.SALT_SIZE_BYTES + 4 + CryptoUtils.GCM_IV_SIZE_BYTES // 40 bytes (v1)
    const val STREAMING_HEADER_SIZE = 4 + 4 + CryptoUtils.SALT_SIZE_BYTES + 4 // 28 bytes (v2)
    const val STREAMING_SEGMENT_SIZE_BYTES = 64 * 1024
    private const val STREAMING_BUFFER_BYTES = 64 * 1024
    private const val MAX_METADATA_ITEM_BYTES = 32 * 1024 * 1024

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

    // =========================================================================
    // STREAMING v2 CONTAINERS (Constant-memory chunked AEAD via Tink)
    // =========================================================================

    private data class StreamingHeader(
        val magic: Int,
        val version: Int,
        val salt: ByteArray,
        val iterations: Int,
        val rawHeaderBytes: ByteArray
    )

    private fun buildStreamingHeader(
        magic: Int,
        salt: ByteArray,
        iterations: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(STREAMING_HEADER_SIZE)
        buf.putInt(magic)
        buf.putInt(STREAMING_VERSION)
        buf.put(salt)
        buf.putInt(iterations)
        return buf.array()
    }

    private fun createStreamingAead(key: ByteArray): AesGcmHkdfStreaming {
        return AesGcmHkdfStreaming(
            key,
            "HmacSha256",
            32,
            STREAMING_SEGMENT_SIZE_BYTES,
            0
        )
    }

    private fun nonClosingOutputStream(out: OutputStream): OutputStream {
        return object : FilterOutputStream(out) {
            override fun write(b: Int) = out.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun flush() = out.flush()
            override fun close() = out.flush()
        }
    }

    private fun readHeaderPrefix(file: File, expectedMagic: Int): Pair<Int, Int> {
        if (!file.exists() || file.length() < STREAMING_HEADER_SIZE + 16) {
            throw CannotOpenFileException()
        }
        FileInputStream(file).use { fis ->
            val dis = DataInputStream(fis)
            val magic = dis.readInt()
            if (magic != expectedMagic) throw CannotOpenFileException()
            val version = dis.readInt()
            return magic to version
        }
    }

    private fun parseStreamingHeader(fis: FileInputStream, expectedMagic: Int): StreamingHeader {
        val raw = ByteArray(STREAMING_HEADER_SIZE)
        DataInputStream(fis).readFully(raw)
        val dis = DataInputStream(ByteArrayInputStream(raw))
        val magic = dis.readInt()
        if (magic != expectedMagic) throw CannotOpenFileException()
        val version = dis.readInt()
        if (version != STREAMING_VERSION) throw CannotOpenFileException()
        val salt = ByteArray(CryptoUtils.SALT_SIZE_BYTES)
        dis.readFully(salt)
        val iterations = dis.readInt()
        if (iterations <= 0) throw CannotOpenFileException()
        return StreamingHeader(magic, version, salt, iterations, raw)
    }

    private fun readLengthPrefixedBytes(dis: DataInputStream, maxBytes: Int = MAX_METADATA_ITEM_BYTES): ByteArray {
        val len = dis.readInt()
        if (len < 0 || len > maxBytes) {
            throw CannotOpenFileException()
        }
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return bytes
    }

    private fun readUtf8String(dis: DataInputStream, maxBytes: Int = 4 * 1024): String {
        return readLengthPrefixedBytes(dis, maxBytes).toString(Charsets.UTF_8)
    }

    private fun writeUtf8String(dos: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun copyExactBytes(
        input: InputStream,
        output: OutputStream?,
        length: Long,
        digest: MessageDigest?,
        buffer: ByteArray
    ) {
        if (length < 0L) throw CannotOpenFileException()
        var remaining = length
        while (remaining > 0L) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) {
                throw EOFException("Unexpected EOF with $remaining bytes left")
            }
            output?.write(buffer, 0, read)
            digest?.update(buffer, 0, read)
            remaining -= read
        }
    }

    private fun streamExactly(
        input: InputStream,
        output: OutputStream,
        expectedLength: Long,
        buffer: ByteArray
    ) {
        var remaining = expectedLength
        while (remaining > 0L) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) {
                throw EOFException("Source stream truncated ($remaining bytes remaining)")
            }
            output.write(buffer, 0, read)
            remaining -= read
        }
        if (input.read() != -1) {
            throw IOException("Source stream exceeded expected byte count ($expectedLength)")
        }
    }

    /**
     * Streams a full backup container (.lkb v2) directly to [outputStream] without accumulating
     * diaries or media ByteArrays in RAM.
     */
    fun encodeBackupContainerStreaming(
        outputStream: OutputStream,
        manifest: BackupManifest,
        diaryIds: List<String>,
        diaryLoader: (String) -> DiaryRecord,
        mediaItems: List<Pair<String, Long>>,
        mediaOpener: (String) -> InputStream,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS
    ) {
        validatePassphrase(passphrase)
        val salt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val header = buildStreamingHeader(MAGIC_BACKUP, salt, iterations)
        val key = CryptoUtils.deriveKeyPbkdf2(passphrase, salt, iterations, 256)

        try {
            outputStream.write(header)
            val aead = createStreamingAead(key)
            val encStream = aead.newEncryptingStream(nonClosingOutputStream(outputStream), header)
            val dos = DataOutputStream(BufferedOutputStream(encStream, STREAMING_BUFFER_BYTES))
            val buffer = ByteArray(STREAMING_BUFFER_BYTES)

            // 1. Manifest
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            dos.writeInt(manifestBytes.size)
            dos.write(manifestBytes)

            // 2. Diaries one by one
            dos.writeInt(diaryIds.size)
            for (dId in diaryIds) {
                val diary = diaryLoader(dId)
                writeUtf8String(dos, diary.id)
                val dJsonBytes = json.encodeToString(diary).toByteArray(Charsets.UTF_8)
                dos.writeInt(dJsonBytes.size)
                dos.write(dJsonBytes)
            }

            // 3. Media items file-to-file stream one by one
            dos.writeInt(mediaItems.size)
            for ((mId, dataLen) in mediaItems) {
                writeUtf8String(dos, mId)
                dos.writeLong(dataLen)
                mediaOpener(mId).use { mediaIn ->
                    streamExactly(mediaIn, dos, dataLen, buffer)
                }
            }

            dos.flush()
            dos.close()
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    /**
     * Re-encodes staged diary files and staged media files into a streaming v2 .lkb container.
     */
    fun encodeStagedBackupStreaming(
        outputStream: OutputStream,
        manifest: BackupManifest,
        diaryFiles: List<StagedDiaryEntry>,
        mediaFiles: List<Pair<String, File>>,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS
    ) {
        validatePassphrase(passphrase)
        val salt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val header = buildStreamingHeader(MAGIC_BACKUP, salt, iterations)
        val key = CryptoUtils.deriveKeyPbkdf2(passphrase, salt, iterations, 256)

        try {
            outputStream.write(header)
            val aead = createStreamingAead(key)
            val encStream = aead.newEncryptingStream(nonClosingOutputStream(outputStream), header)
            val dos = DataOutputStream(BufferedOutputStream(encStream, STREAMING_BUFFER_BYTES))
            val buffer = ByteArray(STREAMING_BUFFER_BYTES)

            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            dos.writeInt(manifestBytes.size)
            dos.write(manifestBytes)

            dos.writeInt(diaryFiles.size)
            for (entry in diaryFiles) {
                val jsonLen = entry.file.length()
                if (jsonLen > Int.MAX_VALUE) throw IOException("Diary payload too large")
                writeUtf8String(dos, entry.id)
                dos.writeInt(jsonLen.toInt())
                FileInputStream(entry.file).use { fis ->
                    streamExactly(fis, dos, jsonLen, buffer)
                }
            }

            dos.writeInt(mediaFiles.size)
            for ((mId, mediaFile) in mediaFiles) {
                val dataLen = mediaFile.length()
                writeUtf8String(dos, mId)
                dos.writeLong(dataLen)
                FileInputStream(mediaFile).use { fis ->
                    streamExactly(fis, dos, dataLen, buffer)
                }
            }

            dos.flush()
            dos.close()
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    /**
     * Streams through a backup file without buffering media bytes to verify AEAD tags + SHA-256 hashes.
     */
    fun verifyBackupContainerStreaming(backupFile: File, passphrase: CharArray) {
        validatePassphrase(passphrase)
        val (_, version) = readHeaderPrefix(backupFile, MAGIC_BACKUP)
        if (version == CURRENT_VERSION) {
            decodeBackupContainer(backupFile.readBytes(), passphrase)
            return
        }
        if (version != STREAMING_VERSION) throw CannotOpenFileException()

        var key: ByteArray? = null
        try {
            FileInputStream(backupFile).use { fis ->
                val header = parseStreamingHeader(fis, MAGIC_BACKUP)
                key = CryptoUtils.deriveKeyPbkdf2(passphrase, header.salt, header.iterations, 256)
                val aead = createStreamingAead(key!!)
                val dis = DataInputStream(BufferedInputStream(aead.newDecryptingStream(fis, header.rawHeaderBytes), STREAMING_BUFFER_BYTES))
                val buffer = ByteArray(STREAMING_BUFFER_BYTES)

                val manifestBytes = readLengthPrefixedBytes(dis)
                val manifest = json.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))
                val expectedHashes = manifest.items.associateBy { it.path }

                val diaryCount = dis.readInt()
                if (diaryCount < 0) throw CannotOpenFileException()
                for (i in 0 until diaryCount) {
                    val dId = readUtf8String(dis)
                    val dJsonBytes = readLengthPrefixedBytes(dis)
                    expectedHashes["diaries/$dId"]?.let { expected ->
                        if (dJsonBytes.size.toLong() != expected.sizeBytes ||
                            CryptoUtils.sha256Hex(dJsonBytes) != expected.sha256
                        ) {
                            throw CannotOpenFileException()
                        }
                    }
                }

                val mediaCount = dis.readInt()
                if (mediaCount < 0) throw CannotOpenFileException()
                for (i in 0 until mediaCount) {
                    val mId = readUtf8String(dis)
                    val dataLen = dis.readLong()
                    val md = MessageDigest.getInstance("SHA-256")
                    copyExactBytes(dis, null, dataLen, md, buffer)
                    val sha = CryptoUtils.toHex(md.digest())
                    expectedHashes["media/$mId"]?.let { expected ->
                        if (dataLen != expected.sizeBytes || sha != expected.sha256) {
                            throw CannotOpenFileException()
                        }
                    }
                }

                if (dis.read() != -1) {
                    throw CannotOpenFileException()
                }
            }
        } catch (e: CannotOpenFileException) {
            throw e
        } catch (e: Exception) {
            throw CannotOpenFileException("Cannot open this file", e)
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    /**
     * Inspects backup metadata and diary summaries without storing media payloads in RAM.
     */
    fun inspectBackupManifestStreaming(backupFile: File, passphrase: CharArray): BackupContents {
        validatePassphrase(passphrase)
        val (_, version) = readHeaderPrefix(backupFile, MAGIC_BACKUP)
        if (version == CURRENT_VERSION) {
            val bundle = decodeBackupContainer(backupFile.readBytes(), passphrase)
            val summaries = bundle.diaries.map {
                BackupDiarySummary(it.id, it.heading, it.createdAt, it.updatedAt, it.plainPreview)
            }.sortedByDescending { it.createdAt }
            return BackupContents(bundle.manifest, summaries, bundle.mediaList.map { it.first })
        }
        if (version != STREAMING_VERSION) throw CannotOpenFileException()

        var key: ByteArray? = null
        try {
            FileInputStream(backupFile).use { fis ->
                val header = parseStreamingHeader(fis, MAGIC_BACKUP)
                key = CryptoUtils.deriveKeyPbkdf2(passphrase, header.salt, header.iterations, 256)
                val aead = createStreamingAead(key!!)
                val dis = DataInputStream(BufferedInputStream(aead.newDecryptingStream(fis, header.rawHeaderBytes), STREAMING_BUFFER_BYTES))
                val buffer = ByteArray(STREAMING_BUFFER_BYTES)

                val manifestBytes = readLengthPrefixedBytes(dis)
                val manifest = json.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))
                val expectedHashes = manifest.items.associateBy { it.path }

                val diaryCount = dis.readInt()
                if (diaryCount < 0) throw CannotOpenFileException()
                val summaries = ArrayList<BackupDiarySummary>(diaryCount.coerceAtMost(1024))
                for (i in 0 until diaryCount) {
                    val dId = readUtf8String(dis)
                    val dJsonBytes = readLengthPrefixedBytes(dis)
                    expectedHashes["diaries/$dId"]?.let { expected ->
                        if (dJsonBytes.size.toLong() != expected.sizeBytes ||
                            CryptoUtils.sha256Hex(dJsonBytes) != expected.sha256
                        ) {
                            throw CannotOpenFileException()
                        }
                    }
                    val rec = json.decodeFromString<DiaryRecord>(dJsonBytes.toString(Charsets.UTF_8))
                    summaries.add(
                        BackupDiarySummary(
                            id = rec.id,
                            heading = rec.heading,
                            createdAt = rec.createdAt,
                            updatedAt = rec.updatedAt,
                            plainPreview = rec.plainPreview
                        )
                    )
                }

                val mediaCount = dis.readInt()
                if (mediaCount < 0) throw CannotOpenFileException()
                val mediaIds = ArrayList<String>(mediaCount.coerceAtMost(1024))
                for (i in 0 until mediaCount) {
                    val mId = readUtf8String(dis)
                    val dataLen = dis.readLong()
                    val md = MessageDigest.getInstance("SHA-256")
                    copyExactBytes(dis, null, dataLen, md, buffer)
                    val sha = CryptoUtils.toHex(md.digest())
                    expectedHashes["media/$mId"]?.let { expected ->
                        if (dataLen != expected.sizeBytes || sha != expected.sha256) {
                            throw CannotOpenFileException()
                        }
                    }
                    mediaIds.add(mId)
                }

                if (dis.read() != -1) {
                    throw CannotOpenFileException()
                }

                return BackupContents(
                    manifest = manifest,
                    diaries = summaries.sortedByDescending { it.createdAt },
                    mediaIds = mediaIds
                )
            }
        } catch (e: CannotOpenFileException) {
            throw e
        } catch (e: Exception) {
            throw CannotOpenFileException("Cannot open this file", e)
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    /**
     * Stream-decrypts a backup container (.lkb v1 or v2) to [stagingDir] on disk,
     * verifying all AEAD tags and manifest SHA-256 hashes while streaming.
     */
    fun decodeBackupContainerToStaging(
        backupFile: File,
        passphrase: CharArray,
        stagingDir: File
    ): StagedBackupData {
        validatePassphrase(passphrase)
        val (_, version) = readHeaderPrefix(backupFile, MAGIC_BACKUP)
        val diariesStageDir = File(stagingDir, "diaries").apply { mkdirs() }
        val mediaStageDir = File(stagingDir, "media").apply { mkdirs() }

        if (version == CURRENT_VERSION) {
            val bundle = decodeBackupContainer(backupFile.readBytes(), passphrase)
            val stagedDiaries = bundle.diaries.mapIndexed { idx, rec ->
                val f = File(diariesStageDir, "d_$idx.json")
                f.writeText(json.encodeToString(rec), Charsets.UTF_8)
                StagedDiaryEntry(rec.id, f)
            }
            val stagedMedia = bundle.mediaList.mapIndexed { idx, (mId, bytes) ->
                val f = File(mediaStageDir, "m_$idx.bin")
                f.writeBytes(bytes)
                mId to f
            }
            return StagedBackupData(bundle.manifest, stagedDiaries, stagedMedia)
        }
        if (version != STREAMING_VERSION) throw CannotOpenFileException()

        var key: ByteArray? = null
        try {
            FileInputStream(backupFile).use { fis ->
                val header = parseStreamingHeader(fis, MAGIC_BACKUP)
                key = CryptoUtils.deriveKeyPbkdf2(passphrase, header.salt, header.iterations, 256)
                val aead = createStreamingAead(key!!)
                val dis = DataInputStream(BufferedInputStream(aead.newDecryptingStream(fis, header.rawHeaderBytes), STREAMING_BUFFER_BYTES))
                val buffer = ByteArray(STREAMING_BUFFER_BYTES)

                val manifestBytes = readLengthPrefixedBytes(dis)
                val manifest = json.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))
                val expectedHashes = manifest.items.associateBy { it.path }

                val diaryCount = dis.readInt()
                if (diaryCount < 0) throw CannotOpenFileException()
                val stagedDiaries = ArrayList<StagedDiaryEntry>(diaryCount.coerceAtMost(1024))
                for (i in 0 until diaryCount) {
                    val dId = readUtf8String(dis)
                    val dJsonBytes = readLengthPrefixedBytes(dis)
                    expectedHashes["diaries/$dId"]?.let { expected ->
                        if (dJsonBytes.size.toLong() != expected.sizeBytes ||
                            CryptoUtils.sha256Hex(dJsonBytes) != expected.sha256
                        ) {
                            throw CannotOpenFileException()
                        }
                    }
                    val stageFile = File(diariesStageDir, "d_$i.json")
                    stageFile.writeBytes(dJsonBytes)
                    stagedDiaries.add(StagedDiaryEntry(dId, stageFile))
                }

                val mediaCount = dis.readInt()
                if (mediaCount < 0) throw CannotOpenFileException()
                val stagedMedia = ArrayList<Pair<String, File>>(mediaCount.coerceAtMost(1024))
                for (i in 0 until mediaCount) {
                    val mId = readUtf8String(dis)
                    val dataLen = dis.readLong()
                    val stageFile = File(mediaStageDir, "m_$i.bin")
                    val md = MessageDigest.getInstance("SHA-256")
                    FileOutputStream(stageFile).use { stageOut ->
                        copyExactBytes(dis, stageOut, dataLen, md, buffer)
                        stageOut.flush()
                    }
                    val sha = CryptoUtils.toHex(md.digest())
                    expectedHashes["media/$mId"]?.let { expected ->
                        if (dataLen != expected.sizeBytes || sha != expected.sha256) {
                            throw CannotOpenFileException()
                        }
                    }
                    stagedMedia.add(mId to stageFile)
                }

                if (dis.read() != -1) {
                    throw CannotOpenFileException()
                }

                return StagedBackupData(manifest, stagedDiaries, stagedMedia)
            }
        } catch (e: CannotOpenFileException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw CannotOpenFileException("Cannot open this file", e)
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    /**
     * Streams a single diary + its media attachments into a v2 .likkho share container.
     */
    fun encodeShareContainerStreaming(
        outputStream: OutputStream,
        diary: DiaryRecord,
        mediaItems: List<Pair<String, Long>>,
        mediaOpener: (String) -> InputStream,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS
    ) {
        validatePassphrase(passphrase)
        val salt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val header = buildStreamingHeader(MAGIC_SHARE, salt, iterations)
        val key = CryptoUtils.deriveKeyPbkdf2(passphrase, salt, iterations, 256)

        try {
            outputStream.write(header)
            val aead = createStreamingAead(key)
            val encStream = aead.newEncryptingStream(nonClosingOutputStream(outputStream), header)
            val dos = DataOutputStream(BufferedOutputStream(encStream, STREAMING_BUFFER_BYTES))
            val buffer = ByteArray(STREAMING_BUFFER_BYTES)

            val diaryJsonBytes = json.encodeToString(diary).toByteArray(Charsets.UTF_8)
            dos.writeInt(diaryJsonBytes.size)
            dos.write(diaryJsonBytes)

            dos.writeInt(mediaItems.size)
            for ((mId, dataLen) in mediaItems) {
                writeUtf8String(dos, mId)
                dos.writeLong(dataLen)
                mediaOpener(mId).use { mediaIn ->
                    streamExactly(mediaIn, dos, dataLen, buffer)
                }
            }

            dos.flush()
            dos.close()
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    /**
     * Verifies a .likkho share container by streaming decryption to EOF without buffering payloads.
     */
    fun verifyShareContainerStreaming(shareFile: File, passphrase: CharArray) {
        validatePassphrase(passphrase)
        val (_, version) = readHeaderPrefix(shareFile, MAGIC_SHARE)
        if (version == CURRENT_VERSION) {
            decodeShareContainer(shareFile.readBytes(), passphrase)
            return
        }
        if (version != STREAMING_VERSION) throw CannotOpenFileException()

        var key: ByteArray? = null
        try {
            FileInputStream(shareFile).use { fis ->
                val header = parseStreamingHeader(fis, MAGIC_SHARE)
                key = CryptoUtils.deriveKeyPbkdf2(passphrase, header.salt, header.iterations, 256)
                val aead = createStreamingAead(key!!)
                val dis = DataInputStream(BufferedInputStream(aead.newDecryptingStream(fis, header.rawHeaderBytes), STREAMING_BUFFER_BYTES))
                val buffer = ByteArray(STREAMING_BUFFER_BYTES)

                val diaryJsonBytes = readLengthPrefixedBytes(dis)
                json.decodeFromString<DiaryRecord>(diaryJsonBytes.toString(Charsets.UTF_8))

                val mediaCount = dis.readInt()
                if (mediaCount < 0) throw CannotOpenFileException()
                for (i in 0 until mediaCount) {
                    readUtf8String(dis)
                    val dataLen = dis.readLong()
                    copyExactBytes(dis, null, dataLen, null, buffer)
                }

                if (dis.read() != -1) {
                    throw CannotOpenFileException()
                }
            }
        } catch (e: CannotOpenFileException) {
            throw e
        } catch (e: Exception) {
            throw CannotOpenFileException("Cannot open this file", e)
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    /**
     * Stream-decrypts a .likkho share container into [stagingDir] on disk.
     */
    fun decodeShareContainerToStaging(
        shareFile: File,
        passphrase: CharArray,
        stagingDir: File
    ): StagedShareData {
        validatePassphrase(passphrase)
        val (_, version) = readHeaderPrefix(shareFile, MAGIC_SHARE)
        val mediaStageDir = File(stagingDir, "media").apply { mkdirs() }

        if (version == CURRENT_VERSION) {
            val bundle = decodeShareContainer(shareFile.readBytes(), passphrase)
            val stagedMedia = bundle.mediaAttachments.mapIndexed { idx, (mId, bytes) ->
                val f = File(mediaStageDir, "m_$idx.bin")
                f.writeBytes(bytes)
                mId to f
            }
            return StagedShareData(bundle.diary, stagedMedia)
        }
        if (version != STREAMING_VERSION) throw CannotOpenFileException()

        var key: ByteArray? = null
        try {
            FileInputStream(shareFile).use { fis ->
                val header = parseStreamingHeader(fis, MAGIC_SHARE)
                key = CryptoUtils.deriveKeyPbkdf2(passphrase, header.salt, header.iterations, 256)
                val aead = createStreamingAead(key!!)
                val dis = DataInputStream(BufferedInputStream(aead.newDecryptingStream(fis, header.rawHeaderBytes), STREAMING_BUFFER_BYTES))
                val buffer = ByteArray(STREAMING_BUFFER_BYTES)

                val diaryJsonBytes = readLengthPrefixedBytes(dis)
                val diary = json.decodeFromString<DiaryRecord>(diaryJsonBytes.toString(Charsets.UTF_8))

                val mediaCount = dis.readInt()
                if (mediaCount < 0) throw CannotOpenFileException()
                val stagedMedia = ArrayList<Pair<String, File>>(mediaCount.coerceAtMost(1024))
                for (i in 0 until mediaCount) {
                    val mId = readUtf8String(dis)
                    val dataLen = dis.readLong()
                    val stageFile = File(mediaStageDir, "m_$i.bin")
                    FileOutputStream(stageFile).use { stageOut ->
                        copyExactBytes(dis, stageOut, dataLen, null, buffer)
                        stageOut.flush()
                    }
                    stagedMedia.add(mId to stageFile)
                }

                if (dis.read() != -1) {
                    throw CannotOpenFileException()
                }

                return StagedShareData(diary, stagedMedia)
            }
        } catch (e: CannotOpenFileException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw CannotOpenFileException("Cannot open this file", e)
        } finally {
            CryptoUtils.zeroize(key)
        }
    }

    internal fun encodeDiaryRecordBytes(record: DiaryRecord): ByteArray {
        return json.encodeToString(record).toByteArray(Charsets.UTF_8)
    }

    internal fun parseDiaryRecordJson(file: File): DiaryRecord {
        return json.decodeFromString(file.readText(Charsets.UTF_8))
    }
}
