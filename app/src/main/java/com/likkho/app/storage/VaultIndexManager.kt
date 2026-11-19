package com.likkho.app.storage

import com.likkho.app.storage.model.IndexEntryRecord
import com.likkho.app.storage.model.VaultIndexRecord
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

sealed class IndexReadResult {
    data class Loaded(val index: VaultIndexRecord) : IndexReadResult()
    object Missing : IndexReadResult()
    data class Corrupt(val error: Throwable) : IndexReadResult()
}

/**
 * Manages encrypted vault index file (index.lki) per STORAGE_SPEC Section 4:
 * - Har vault me ek encrypted index file (id, createdAt, updatedAt, pinned, flags, plainPreview, heading).
 * - Index kharab/missing ho to diary files se rebuild ho (self-healing). Source of truth hamesha diary files hain.
 * - Search unlock ke baad memory me.
 */
class VaultIndexManager internal constructor(
    val vaultId: VaultId,
    val vaultDir: File,
    private val customEntryScanner: ((File, ByteArray) -> List<IndexEntryRecord>)? = null
) {
    constructor(vaultId: VaultId, vaultDir: File) : this(vaultId, vaultDir, null)

    val indexFile = File(vaultDir, INDEX_FILE_NAME)

    companion object {
        const val INDEX_FILE_NAME = "index.lki"
        const val MAGIC = 0x4C4B4931 // "LKI1"
        const val CURRENT_VERSION = 1
        const val HEADER_SIZE = 4 + 4 + CryptoUtils.GCM_IV_SIZE_BYTES // 20 bytes

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun buildAad(version: Int, vaultId: VaultId): ByteArray {
            val dirBytes = vaultId.dirName.toByteArray(Charsets.UTF_8)
            val buf = ByteBuffer.allocate(4 + 4 + dirBytes.size)
            buf.putInt(MAGIC)
            buf.putInt(version)
            buf.put(dirBytes)
            return buf.array()
        }
    }

    /**
     * Reads and decrypts the index file using [dek], explicitly distinguishing Missing vs Corrupt vs Loaded.
     */
    fun readIndexStatus(dek: ByteArray): IndexReadResult {
        if (!indexFile.exists()) {
            return IndexReadResult.Missing
        }
        if (indexFile.length() < HEADER_SIZE + 16) {
            return IndexReadResult.Corrupt(
                IllegalArgumentException("Index file too small (${indexFile.length()} bytes)")
            )
        }

        return try {
            val rawBytes = indexFile.readBytes()
            DataInputStream(ByteArrayInputStream(rawBytes)).use { dis ->
                val magic = dis.readInt()
                if (magic != MAGIC) {
                    return IndexReadResult.Corrupt(
                        IllegalArgumentException("Invalid index magic: ${Integer.toHexString(magic)}")
                    )
                }
                val version = dis.readInt()
                val iv = ByteArray(CryptoUtils.GCM_IV_SIZE_BYTES)
                dis.readFully(iv)

                val ciphertext = ByteArray(rawBytes.size - HEADER_SIZE)
                dis.readFully(ciphertext)

                val ivAndCiphertext = ByteArray(iv.size + ciphertext.size)
                System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.size)
                System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.size, ciphertext.size)

                val aad = buildAad(version, vaultId)
                val decrypted = CryptoUtils.aesGcmDecrypt(dek, ivAndCiphertext, aad)
                val jsonString = String(decrypted, Charsets.UTF_8)
                val parsed = json.decodeFromString(VaultIndexRecord.serializer(), jsonString)
                IndexReadResult.Loaded(parsed)
            }
        } catch (e: Exception) {
            IndexReadResult.Corrupt(e)
        }
    }

    /**
     * Reads and decrypts the index file using [dek].
     * Returns null when missing or corrupted; use [readIndexStatus] to distinguish causes.
     */
    fun readIndex(dek: ByteArray): VaultIndexRecord? {
        return when (val status = readIndexStatus(dek)) {
            is IndexReadResult.Loaded -> status.index
            is IndexReadResult.Missing, is IndexReadResult.Corrupt -> null
        }
    }

    /**
     * Encrypts and safely writes the index record using [SafeStorageWriter].
     */
    fun writeIndex(indexRecord: VaultIndexRecord, dek: ByteArray) {
        val jsonString = json.encodeToString(VaultIndexRecord.serializer(), indexRecord)
        val plaintext = jsonString.toByteArray(Charsets.UTF_8)

        val iv = CryptoUtils.secureRandomBytes(CryptoUtils.GCM_IV_SIZE_BYTES)
        val aad = buildAad(CURRENT_VERSION, vaultId)

        val encryptedWithIv = CryptoUtils.aesGcmEncrypt(dek, plaintext, aad, iv)
        val ciphertextLen = encryptedWithIv.size - CryptoUtils.GCM_IV_SIZE_BYTES
        val ciphertext = ByteArray(ciphertextLen)
        System.arraycopy(encryptedWithIv, CryptoUtils.GCM_IV_SIZE_BYTES, ciphertext, 0, ciphertextLen)

        val baos = ByteArrayOutputStream(HEADER_SIZE + ciphertext.size)
        DataOutputStream(baos).use { dos ->
            dos.writeInt(MAGIC)
            dos.writeInt(CURRENT_VERSION)
            dos.write(iv)
            dos.write(ciphertext)
        }

        SafeStorageWriter.safeWrite(indexFile, baos.toByteArray())
    }

    /**
     * Updates an entry in the index or adds it if not present.
     * If index is missing, rebuilds from disk diary files first.
     * If index is corrupt, only writes rebuilt index if rebuilt entry count >= decodable .lkd files; otherwise throws.
     */
    fun upsertEntry(
        entry: IndexEntryRecord,
        dek: ByteArray,
        diariesDir: File = File(vaultDir, "diaries")
    ) {
        val current = when (val status = readIndexStatus(dek)) {
            is IndexReadResult.Loaded -> status.index
            is IndexReadResult.Missing -> rebuildFromDiaries(diariesDir, dek)
            is IndexReadResult.Corrupt -> rebuildFromDiariesVerified(diariesDir, dek)
        }
        val entries = current.entries.filter { it.id != entry.id }.toMutableList()
        entries.add(entry)
        val updated = current.copy(updatedAt = System.currentTimeMillis(), entries = entries)
        writeIndex(updated, dek)
    }

    /**
     * Removes an entry from the index.
     * If index is missing, rebuilds from disk diary files first.
     * If index is corrupt, only writes rebuilt index if rebuilt entry count >= decodable .lkd files; otherwise throws.
     */
    fun removeEntry(
        id: String,
        dek: ByteArray,
        diariesDir: File = File(vaultDir, "diaries")
    ) {
        val current = when (val status = readIndexStatus(dek)) {
            is IndexReadResult.Loaded -> status.index
            is IndexReadResult.Missing -> rebuildFromDiaries(diariesDir, dek)
            is IndexReadResult.Corrupt -> rebuildFromDiariesVerified(diariesDir, dek)
        }
        val filtered = current.entries.filter { it.id != id }
        val updated = current.copy(updatedAt = System.currentTimeMillis(), entries = filtered)
        writeIndex(updated, dek)
    }

    private fun scanDecodableDiaries(diariesDir: File, dek: ByteArray): Pair<Int, List<IndexEntryRecord>> {
        val entries = mutableListOf<IndexEntryRecord>()
        if (!diariesDir.exists()) return 0 to entries
        val lkdFiles = diariesDir.listFiles { f -> f.isFile && f.name.endsWith(".lkd") } ?: emptyArray()
        for (file in lkdFiles) {
            val diaryId = file.name.removeSuffix(".lkd")
            try {
                val decoded = DiaryFileCodec.decode(file.readBytes(), dek, diaryId)
                val r = decoded.record
                entries.add(
                    IndexEntryRecord(
                        id = r.id,
                        createdAt = r.createdAt,
                        updatedAt = r.updatedAt,
                        pinned = r.pinned,
                        flags = (if (r.diaryLock != null) 1 else 0) or
                                (if (r.audios.isNotEmpty()) 2 else 0) or
                                (if (r.images.isNotEmpty()) 4 else 0) or
                                (if (r.videos.isNotEmpty()) 8 else 0),
                        plainPreview = r.plainPreview,
                        heading = r.heading
                    )
                )
            } catch (_: Exception) {
                // Unreadable/corrupt file stays omitted from index; quarantine & error surfacing handled in recovery/load
            }
        }
        return lkdFiles.size to entries
    }

    private fun rebuildFromDiariesVerified(diariesDir: File, dek: ByteArray): VaultIndexRecord {
        val (totalLkdFiles, decodableEntries) = scanDecodableDiaries(diariesDir, dek)
        val decodableCount = decodableEntries.size
        val entries = customEntryScanner?.invoke(diariesDir, dek) ?: decodableEntries

        if (totalLkdFiles > 0 && entries.isEmpty()) {
            throw StorageVerificationException(
                "Refusing to overwrite corrupt index: $totalLkdFiles .lkd files exist on disk but 0 entries decoded."
            )
        }
        if (entries.size < decodableCount) {
            throw StorageVerificationException(
                "Refusing to overwrite corrupt index: rebuilt ${entries.size} entries, fewer than $decodableCount decodable .lkd files."
            )
        }

        val rebuilt = VaultIndexRecord(
            version = CURRENT_VERSION,
            updatedAt = System.currentTimeMillis(),
            entries = entries
        )
        writeIndex(rebuilt, dek)
        return rebuilt
    }

    /**
     * Self-heals the index by scanning all .lkd files in [diariesDir] and rebuilding index.lki.
     */
    fun rebuildFromDiaries(diariesDir: File, dek: ByteArray): VaultIndexRecord {
        val (_, decodableEntries) = scanDecodableDiaries(diariesDir, dek)
        val entries = customEntryScanner?.invoke(diariesDir, dek) ?: decodableEntries

        val rebuilt = VaultIndexRecord(
            version = CURRENT_VERSION,
            updatedAt = System.currentTimeMillis(),
            entries = entries
        )
        writeIndex(rebuilt, dek)
        return rebuilt
    }
}
