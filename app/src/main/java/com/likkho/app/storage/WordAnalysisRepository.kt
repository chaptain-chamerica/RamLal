package com.likkho.app.storage

import com.likkho.app.storage.model.WordAnalysisBundleRecord
import com.likkho.app.storage.model.WordAnalysisStorageRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Manages encrypted storage of saved word analyses per STORAGE_SPEC Section 7:
 * - Saved word analyses: encrypted file inside the active vault directory.
 * - Format: magic "LKW1", version, IV (12 bytes), AES-256-GCM encrypted JSON payload bound to vault AAD.
 * - Safe writes via SafeStorageWriter (.tmp -> fsync -> verify -> .bak -> atomic rename).
 */
class WordAnalysisRepository(
    val vaultId: VaultId,
    val vaultDir: File
) {
    val wordsFile = File(vaultDir, WORDS_FILE_NAME)

    companion object {
        const val WORDS_FILE_NAME = "words.lkw"
        const val MAGIC = 0x4C4B5731 // "LKW1"
        const val CURRENT_VERSION = 1
        const val HEADER_SIZE = 8

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
     * Loads and decrypts saved word analyses from the vault.
     * Returns empty list if no words file exists yet.
     */
    @Synchronized
    fun loadWords(dek: ByteArray): List<WordAnalysisStorageRecord> {
        if (!wordsFile.exists() || wordsFile.length() < HEADER_SIZE) {
            return emptyList()
        }

        val bytes = wordsFile.readBytes()
        val dis = DataInputStream(ByteArrayInputStream(bytes))

        val magic = dis.readInt()
        if (magic != MAGIC) {
            throw IOException("Invalid words file magic: 0x${Integer.toHexString(magic)}")
        }

        val version = dis.readInt()
        val ivAndCiphertext = ByteArray(dis.available())
        dis.readFully(ivAndCiphertext)

        val aad = buildAad(version, vaultId)
        val plaintext = CryptoUtils.aesGcmDecrypt(dek, ivAndCiphertext, aad)

        val jsonString = plaintext.toString(Charsets.UTF_8)
        val bundle = json.decodeFromString<WordAnalysisBundleRecord>(jsonString)
        return bundle.words
    }

    /**
     * Encrypts and safely writes the word analyses list to words.lkw.
     */
    @Synchronized
    fun saveWords(words: List<WordAnalysisStorageRecord>, dek: ByteArray) {
        val bundle = WordAnalysisBundleRecord(version = CURRENT_VERSION, words = words)
        val jsonString = json.encodeToString(bundle)
        val plaintext = jsonString.toByteArray(Charsets.UTF_8)

        val aad = buildAad(CURRENT_VERSION, vaultId)
        val encryptedPayload = CryptoUtils.aesGcmEncrypt(dek, plaintext, aad)

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(MAGIC)
        dos.writeInt(CURRENT_VERSION)
        dos.write(encryptedPayload)
        dos.flush()

        val encodedBytes = baos.toByteArray()

        SafeStorageWriter.safeWrite(wordsFile, encodedBytes) { tmpFile ->
            // Verification hook
            val raw = tmpFile.readBytes()
            val verifyDis = DataInputStream(ByteArrayInputStream(raw))
            verifyDis.readInt()
            val ver = verifyDis.readInt()
            val verifyCt = ByteArray(verifyDis.available())
            verifyDis.readFully(verifyCt)
            val verifyAad = buildAad(ver, vaultId)
            CryptoUtils.aesGcmDecrypt(dek, verifyCt, verifyAad)
        }
    }

    /**
     * Upserts a word analysis record.
     */
    @Synchronized
    fun upsertWord(record: WordAnalysisStorageRecord, dek: ByteArray): List<WordAnalysisStorageRecord> {
        val current = loadWords(dek).toMutableList()
        val index = current.indexOfFirst { it.word.equals(record.word, ignoreCase = true) }
        if (index >= 0) {
            current[index] = record
        } else {
            current.add(record)
        }
        saveWords(current, dek)
        return current
    }

    /**
     * Deletes a word analysis record by word name (case-insensitive).
     */
    @Synchronized
    fun deleteWord(word: String, dek: ByteArray): List<WordAnalysisStorageRecord> {
        val current = loadWords(dek).filterNot { it.word.equals(word, ignoreCase = true) }
        saveWords(current, dek)
        return current
    }

    @Synchronized
    fun saveWord(record: WordAnalysisStorageRecord, dek: ByteArray) = upsertWord(record, dek)
}
