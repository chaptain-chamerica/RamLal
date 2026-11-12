package com.likkho.app.storage

import com.likkho.app.storage.model.PasswordBundleRecord
import com.likkho.app.storage.model.SavedPasswordRecord
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

/**
 * Manages encrypted storage of saved passwords per STORAGE_SPEC Section 7:
 * - Saved passwords: encrypted file inside the active vault directory (passwords are most sensitive).
 * - Format: magic "LKP1", version, IV (12 bytes), AES-256-GCM encrypted JSON payload bound to vault AAD.
 * - Safe writes via SafeStorageWriter (.tmp -> fsync -> verify -> .bak -> atomic rename).
 */
class PasswordRepository(
    val vaultId: VaultId,
    val vaultDir: File
) {
    val passwordsFile = File(vaultDir, PASSWORDS_FILE_NAME)

    companion object {
        const val PASSWORDS_FILE_NAME = "passwords.lkp"
        const val MAGIC = 0x4C4B5031 // "LKP1"
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
     * Loads and decrypts saved passwords from the vault.
     * Returns empty list if no passwords file exists yet.
     */
    @Synchronized
    fun loadPasswords(dek: ByteArray): List<SavedPasswordRecord> {
        if (!passwordsFile.exists() || passwordsFile.length() < HEADER_SIZE) {
            return emptyList()
        }

        val bytes = passwordsFile.readBytes()
        val dis = DataInputStream(ByteArrayInputStream(bytes))

        val magic = dis.readInt()
        if (magic != MAGIC) {
            throw IOException("Invalid password file magic: 0x${Integer.toHexString(magic)}")
        }

        val version = dis.readInt()
        val ivAndCiphertext = ByteArray(dis.available())
        dis.readFully(ivAndCiphertext)

        val aad = buildAad(version, vaultId)
        val plaintext = CryptoUtils.aesGcmDecrypt(dek, ivAndCiphertext, aad)

        val jsonString = plaintext.toString(Charsets.UTF_8)
        val bundle = json.decodeFromString<PasswordBundleRecord>(jsonString)
        return bundle.passwords
    }

    /**
     * Encrypts and safely writes the passwords list to passwords.lkp.
     */
    @Synchronized
    fun savePasswords(passwords: List<SavedPasswordRecord>, dek: ByteArray) {
        val bundle = PasswordBundleRecord(version = CURRENT_VERSION, passwords = passwords)
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

        SafeStorageWriter.safeWrite(passwordsFile, encodedBytes) { tmpFile ->
            // Verification hook
            val raw = tmpFile.readBytes()
            val verifyDis = DataInputStream(ByteArrayInputStream(raw))
            verifyDis.readInt() // magic
            val ver = verifyDis.readInt()
            val verifyCt = ByteArray(verifyDis.available())
            verifyDis.readFully(verifyCt)
            val verifyAad = buildAad(ver, vaultId)
            CryptoUtils.aesGcmDecrypt(dek, verifyCt, verifyAad)
        }
    }

    /**
     * Adds or updates a password in the vault.
     */
    @Synchronized
    fun upsertPassword(password: SavedPasswordRecord, dek: ByteArray): List<SavedPasswordRecord> {
        val current = loadPasswords(dek).toMutableList()
        val index = current.indexOfFirst { it.id == password.id }
        if (index >= 0) {
            current[index] = password
        } else {
            current.add(password)
        }
        savePasswords(current, dek)
        return current
    }

    /**
     * Removes a password by ID from the vault.
     */
    @Synchronized
    fun deletePassword(id: String, dek: ByteArray): List<SavedPasswordRecord> {
        val current = loadPasswords(dek).filterNot { it.id == id }
        savePasswords(current, dek)
        return current
    }
}
