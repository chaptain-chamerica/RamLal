package com.likkho.app.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.crypto.AEADBadTagException

/**
 * Manages two-layer DEK wrapping and persistence per STORAGE_SPEC Section 1 & 2:
 * - 2-layer DEK wrap: PIN (PBKDF2-HMAC-SHA256) + Android Keystore (AES-GCM).
 * - Passcode-less mode: DEK wrapped by Keystore alone.
 * - Biometric: separate Keystore biometric-bound key wrapping a copy of DEK.
 * - PIN verification: detected solely via AES-GCM authentication tag failure.
 */
class VaultEnvelope(
    val vaultId: VaultId,
    private val vaultDir: File,
    private val keyStoreProvider: KeyStoreProvider,
    private val pbkdf2Iterations: Int = CryptoUtils.DEFAULT_PBKDF2_ITERATIONS
) {
    private val metaFile = File(vaultDir, META_FILE_NAME)
    private val tmpMetaFile = File(vaultDir, "$META_FILE_NAME.tmp")
    private val keystoreAlias = "likkho_ks_${vaultId.keyAliasSuffix}"
    private val biometricKeystoreAlias = "likkho_ks_bio_${vaultId.keyAliasSuffix}"

    companion object {
        const val META_FILE_NAME = "meta.dat"
        private const val MAGIC = 0x4C4B5631 // "LKV1"
        private const val VERSION = 1
        private const val FLAG_HAS_PASSCODE = 1
        private const val FLAG_HAS_BIOMETRIC = 2
    }

    data class EnvelopeData(
        val salt: ByteArray,
        val iterations: Int,
        val hasPasscode: Boolean,
        val wrappedDek: ByteArray,
        val biometricWrappedDek: ByteArray?
    )

    fun exists(): Boolean = metaFile.exists()

    /**
     * Initializes a brand-new vault with a freshly generated 256-bit DEK.
     * If [pin] is null, vault is secured with Keystore only.
     * If [pin] is non-null, DEK is 2-layer wrapped (Keystore + PIN).
     */
    fun createVault(pin: CharArray?): ByteArray {
        vaultDir.mkdirs()
        val dek = CryptoUtils.secureRandomBytes(CryptoUtils.AES_KEY_SIZE_BYTES)
        try {
            wrapAndSave(dek, pin, biometricEnabled = false)
            val returnDek = ByteArray(dek.size)
            System.arraycopy(dek, 0, returnDek, 0, dek.size)
            return returnDek
        } finally {
            CryptoUtils.zeroize(dek)
        }
    }

    /**
     * Unwraps the DEK using the provided [pin].
     * Throws [AEADBadTagException] if the PIN is incorrect.
     */
    fun unwrapWithPin(pin: CharArray): ByteArray {
        val env = readEnvelope()
        check(env.hasPasscode) { "Vault ${vaultId.dirName} does not have a passcode configured" }

        val aadOuter = createAadOuter(env.iterations)
        val aadInner = createAadInner()

        // 1. Derive PIN key
        val pinKey = CryptoUtils.deriveKeyPbkdf2(pin, env.salt, env.iterations)
        try {
            // 2. Decrypt outer layer with PIN key. Throws AEADBadTagException if wrong PIN
            val innerCiphertext = CryptoUtils.aesGcmDecrypt(pinKey, env.wrappedDek, aadOuter)

            // 3. Decrypt inner layer with Keystore key
            return keyStoreProvider.decrypt(keystoreAlias, innerCiphertext, aadInner)
        } finally {
            CryptoUtils.zeroize(pinKey)
        }
    }

    /**
     * Unwraps the DEK when no passcode is configured (Keystore-only mode).
     */
    fun unwrapNoPasscode(): ByteArray {
        val env = readEnvelope()
        check(!env.hasPasscode) { "Vault ${vaultId.dirName} requires a PIN passcode" }
        val aadInner = createAadInner()
        return keyStoreProvider.decrypt(keystoreAlias, env.wrappedDek, aadInner)
    }

    /**
     * Unwraps the DEK using the biometric key.
     */
    fun unwrapWithBiometric(): ByteArray {
        val env = readEnvelope()
        val bioWrapped = checkNotNull(env.biometricWrappedDek) { "Biometrics not enabled on vault" }
        val aad = createAadInner()
        return keyStoreProvider.decrypt(biometricKeystoreAlias, bioWrapped, aad)
    }

    /**
     * Re-wraps existing DEK with a new PIN (or removes PIN if null).
     * Does NOT touch diary files.
     */
    fun rewrapDek(currentDek: ByteArray, newPin: CharArray?, keepBiometrics: Boolean = false) {
        val env = if (exists()) readEnvelope() else null
        wrapAndSave(currentDek, newPin, biometricEnabled = keepBiometrics && env?.biometricWrappedDek != null)
    }

    /**
     * Enables biometric unlock by wrapping a copy of the DEK with biometric Keystore key.
     */
    fun enableBiometric(currentDek: ByteArray, pin: CharArray?) {
        wrapAndSave(currentDek, pin, biometricEnabled = true)
    }

    /**
     * Disables biometric unlock and purges the biometric key and wrapped copy.
     */
    fun disableBiometric(currentDek: ByteArray, pin: CharArray?) {
        keyStoreProvider.deleteKey(biometricKeystoreAlias)
        wrapAndSave(currentDek, pin, biometricEnabled = false)
    }

    fun hasPasscode(): Boolean {
        if (!exists()) return false
        return readEnvelope().hasPasscode
    }

    fun hasBiometric(): Boolean {
        if (!exists()) return false
        return readEnvelope().biometricWrappedDek != null
    }

    private fun wrapAndSave(dek: ByteArray, pin: CharArray?, biometricEnabled: Boolean) {
        val salt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val iterations = pbkdf2Iterations
        val hasPasscode = pin != null && pin.isNotEmpty()

        val aadInner = createAadInner()
        val aadOuter = createAadOuter(iterations)

        // Inner layer: Keystore encryption
        val innerCiphertext = keyStoreProvider.encrypt(keystoreAlias, dek, aadInner)

        val finalWrappedDek = if (hasPasscode) {
            val pinKey = CryptoUtils.deriveKeyPbkdf2(pin!!, salt, iterations)
            try {
                CryptoUtils.aesGcmEncrypt(pinKey, innerCiphertext, aadOuter)
            } finally {
                CryptoUtils.zeroize(pinKey)
            }
        } else {
            innerCiphertext
        }

        val bioWrappedDek = if (biometricEnabled) {
            keyStoreProvider.encrypt(biometricKeystoreAlias, dek, aadInner)
        } else {
            keyStoreProvider.deleteKey(biometricKeystoreAlias)
            null
        }

        saveEnvelope(EnvelopeData(salt, iterations, hasPasscode, finalWrappedDek, bioWrappedDek))
    }

    private fun createAadInner(): ByteArray =
        "LKV1_${vaultId.dirName}_inner".toByteArray(Charsets.UTF_8)

    private fun createAadOuter(iter: Int): ByteArray =
        "LKV1_${vaultId.dirName}_outer_$iter".toByteArray(Charsets.UTF_8)

    private fun saveEnvelope(data: EnvelopeData) {
        vaultDir.mkdirs()
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(MAGIC)
            dos.writeInt(VERSION)
            var flags = 0
            if (data.hasPasscode) flags = flags or FLAG_HAS_PASSCODE
            if (data.biometricWrappedDek != null) flags = flags or FLAG_HAS_BIOMETRIC
            dos.writeInt(flags)
            dos.write(data.salt)
            dos.writeInt(data.iterations)
            dos.writeInt(data.wrappedDek.size)
            dos.write(data.wrappedDek)
            if (data.biometricWrappedDek != null) {
                dos.writeInt(data.biometricWrappedDek.size)
                dos.write(data.biometricWrappedDek)
            } else {
                dos.writeInt(0)
            }
        }

        val bytes = baos.toByteArray()
        FileOutputStream(tmpMetaFile).use { fos ->
            fos.write(bytes)
            fos.fd.sync()
        }
        if (metaFile.exists()) metaFile.delete()
        tmpMetaFile.renameTo(metaFile)
    }

    private fun readEnvelope(): EnvelopeData {
        val bytes = metaFile.readBytes()
        DataInputStream(ByteArrayInputStream(bytes)).use { dis ->
            val magic = dis.readInt()
            check(magic == MAGIC) { "Corrupt vault metadata magic" }
            val version = dis.readInt()
            check(version == VERSION) { "Unsupported vault metadata version: $version" }
            val flags = dis.readInt()
            val hasPasscode = (flags and FLAG_HAS_PASSCODE) != 0
            val hasBio = (flags and FLAG_HAS_BIOMETRIC) != 0

            val salt = ByteArray(CryptoUtils.SALT_SIZE_BYTES)
            dis.readFully(salt)
            val iterations = dis.readInt()

            val wrappedLen = dis.readInt()
            val wrappedDek = ByteArray(wrappedLen)
            dis.readFully(wrappedDek)

            val bioLen = dis.readInt()
            val bioWrappedDek = if (hasBio && bioLen > 0) {
                val b = ByteArray(bioLen)
                dis.readFully(b)
                b
            } else {
                null
            }

            return EnvelopeData(salt, iterations, hasPasscode, wrappedDek, bioWrappedDek)
        }
    }

    /**
     * Crypto-shreds all keys and metadata associated with this vault.
     */
    fun shred() {
        keyStoreProvider.deleteKey(keystoreAlias)
        keyStoreProvider.deleteKey(biometricKeystoreAlias)
        if (metaFile.exists()) metaFile.delete()
        if (tmpMetaFile.exists()) tmpMetaFile.delete()
        if (vaultDir.exists()) vaultDir.deleteRecursively()
    }
}
