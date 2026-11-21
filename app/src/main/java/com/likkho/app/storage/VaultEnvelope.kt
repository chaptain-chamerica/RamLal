package com.likkho.app.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher

/**
 * Manages two-layer DEK wrapping and persistence per STORAGE_SPEC Section 1 & 2:
 * - 2-layer DEK wrap: PIN (PBKDF2-HMAC-SHA256) + Android Keystore (AES-GCM).
 * - Passcode-less mode: DEK wrapped by Keystore alone.
 * - Biometric: separate Keystore biometric-bound key wrapping a copy of DEK (CryptoObject required).
 * - PIN verification: detected solely via AES-GCM authentication tag failure.
 */
class VaultEnvelope(
    val vaultId: VaultId,
    private val vaultDir: File,
    private val keyStoreProvider: KeyStoreProvider,
    private val pbkdf2Iterations: Int? = null,
    private val targetIterationsProvider: () -> Int = { CryptoUtils.getCalibratedPbkdf2Iterations() }
) {
    private val metaFile = File(vaultDir, META_FILE_NAME)
    private val tmpMetaFile = File(vaultDir, "$META_FILE_NAME.tmp")
    private val bakMetaFile = File(vaultDir, "$META_FILE_NAME.bak")
    private val keystoreAlias = "likkho_ks_${vaultId.keyAliasSuffix}"
    val biometricKeystoreAlias = "likkho_ks_bio_${vaultId.keyAliasSuffix}"

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

    data class HeaderFlags(
        val hasPasscode: Boolean,
        val hasBiometric: Boolean
    )

    fun resolveTargetIterations(): Int {
        return pbkdf2Iterations ?: targetIterationsProvider().coerceIn(
            CryptoUtils.MIN_CALIBRATED_ITERATIONS,
            CryptoUtils.MAX_CALIBRATED_ITERATIONS
        )
    }

    fun exists(): Boolean = metaFile.exists() || bakMetaFile.exists()

    /**
     * Fast 12-byte header inspection with zero crypto or Keystore operations.
     */
    fun readHeaderFlags(): HeaderFlags? {
        val file = when {
            metaFile.exists() -> metaFile
            bakMetaFile.exists() -> bakMetaFile
            else -> return null
        }
        return runCatching {
            java.io.FileInputStream(file).use { fis ->
                val buf = ByteArray(12)
                var total = 0
                while (total < 12) {
                    val r = fis.read(buf, total, 12 - total)
                    if (r <= 0) return@runCatching null
                    total += r
                }
                DataInputStream(ByteArrayInputStream(buf)).use { dis ->
                    val magic = dis.readInt()
                    val version = dis.readInt()
                    if (magic != MAGIC || version != VERSION) return@runCatching null
                    val flags = dis.readInt()
                    HeaderFlags(
                        hasPasscode = (flags and FLAG_HAS_PASSCODE) != 0,
                        hasBiometric = (flags and FLAG_HAS_BIOMETRIC) != 0
                    )
                }
            }
        }.getOrNull()
    }

    fun storedIterations(): Int? {
        if (!exists()) return null
        return runCatching { readEnvelope().iterations }.getOrNull()
    }

    /**
     * Initializes a brand-new vault with a freshly generated 256-bit DEK.
     * If [pin] is null, vault is secured with Keystore only.
     * If [pin] is non-null, DEK is 2-layer wrapped (Keystore + PIN).
     */
    fun createVault(pin: CharArray?): ByteArray {
        vaultDir.mkdirs()
        val dek = CryptoUtils.secureRandomBytes(CryptoUtils.AES_KEY_SIZE_BYTES)
        try {
            wrapAndSave(dek, pin, existingBioWrappedDek = null)
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

        val tKdf0 = System.nanoTime()
        val pinKey = CryptoUtils.deriveKeyPbkdf2(pin, env.salt, env.iterations)
        val kdfMs = (System.nanoTime() - tKdf0) / 1_000_000.0
        try {
            val innerCiphertext = CryptoUtils.aesGcmDecrypt(pinKey, env.wrappedDek, aadOuter)
            val tKs0 = System.nanoTime()
            val dek = keyStoreProvider.decrypt(keystoreAlias, innerCiphertext, aadInner)
            val ksMs = (System.nanoTime() - tKs0) / 1_000_000.0
            CryptoUtils.logPerf(
                "unlock_unwrap iter=${env.iterations} pbkdf2Ms=${"%.2f".format(kdfMs)} keystoreMs=${"%.2f".format(ksMs)}"
            )
            return dek
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
        val t0 = System.nanoTime()
        val dek = keyStoreProvider.decrypt(keystoreAlias, env.wrappedDek, aadInner)
        val ksMs = (System.nanoTime() - t0) / 1_000_000.0
        CryptoUtils.logPerf("unlock_no_passcode keystoreMs=${"%.2f".format(ksMs)}")
        return dek
    }

    /**
     * Prepares a Keystore ENCRYPT Cipher bound to user biometric authentication (CryptoObject).
     */
    fun prepareBiometricEncryptCipher(): Cipher {
        return keyStoreProvider.getBiometricCipher(biometricKeystoreAlias, Cipher.ENCRYPT_MODE, null)
    }

    /**
     * Prepares a Keystore DECRYPT Cipher bound to user biometric authentication (CryptoObject).
     * Throws [BiometricKeyInvalidatedException] if the biometric key was invalidated or missing.
     */
    fun prepareBiometricDecryptCipher(): Cipher {
        val env = readEnvelope()
        val bioWrapped = env.biometricWrappedDek
            ?: throw BiometricKeyInvalidatedException()
        if (bioWrapped.size <= CryptoUtils.GCM_IV_SIZE_BYTES + 16) {
            throw BiometricKeyInvalidatedException()
        }
        val iv = ByteArray(CryptoUtils.GCM_IV_SIZE_BYTES)
        System.arraycopy(bioWrapped, 0, iv, 0, CryptoUtils.GCM_IV_SIZE_BYTES)
        return keyStoreProvider.getBiometricCipher(biometricKeystoreAlias, Cipher.DECRYPT_MODE, iv)
    }

    /**
     * Wraps [currentDek] using an already-authenticated [authedCipher] from BiometricPrompt.CryptoObject
     * and persists it atomically to meta.dat without needing PIN.
     */
    fun enableBiometricWithCipher(currentDek: ByteArray, authedCipher: Cipher) {
        require(exists()) { "Vault must exist to enable biometric" }
        val env = readEnvelope()
        check(env.hasPasscode) { "Passcode must be enabled before biometrics" }
        val aad = createAadInner()
        authedCipher.updateAAD(aad)
        val ciphertext = authedCipher.doFinal(currentDek)
        val iv = authedCipher.iv
        require(iv.size == CryptoUtils.GCM_IV_SIZE_BYTES) { "Unexpected GCM IV size: ${iv.size}" }
        val bioWrapped = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, bioWrapped, 0, iv.size)
        System.arraycopy(ciphertext, 0, bioWrapped, iv.size, ciphertext.size)

        val updated = env.copy(biometricWrappedDek = bioWrapped)
        saveEnvelopeAtomic(updated)
    }

    /**
     * Unwraps DEK strictly using an already-authenticated [authedCipher] from BiometricPrompt.CryptoObject.
     * Never performs PBKDF2 and never falls back to direct Keystore decrypt.
     */
    fun unwrapWithBiometricCipher(authedCipher: Cipher): ByteArray {
        val t0 = System.nanoTime()
        val env = readEnvelope()
        val bioWrapped = env.biometricWrappedDek
            ?: throw IllegalStateException("Biometrics not enabled on vault")
        require(bioWrapped.size > CryptoUtils.GCM_IV_SIZE_BYTES + 16) { "Invalid biometric ciphertext" }
        val cipherLen = bioWrapped.size - CryptoUtils.GCM_IV_SIZE_BYTES
        val ciphertext = ByteArray(cipherLen)
        System.arraycopy(bioWrapped, CryptoUtils.GCM_IV_SIZE_BYTES, ciphertext, 0, cipherLen)

        val aad = createAadInner()
        authedCipher.updateAAD(aad)
        val dek = authedCipher.doFinal(ciphertext)
        val bioMs = (System.nanoTime() - t0) / 1_000_000.0
        CryptoUtils.logPerf("unlock_biometric_cipher ms=${"%.2f".format(bioMs)}")
        return dek
    }

    /**
     * Deletes the biometric Keystore key and clears the wrapped DEK copy from meta.dat without needing PIN.
     */
    fun clearBiometricKeyAndEnvelope() {
        keyStoreProvider.deleteKey(biometricKeystoreAlias)
        if (!exists()) return
        runCatching {
            val env = readEnvelope()
            if (env.biometricWrappedDek != null) {
                saveEnvelopeAtomic(env.copy(biometricWrappedDek = null))
            }
        }
    }

    /**
     * Re-wraps existing DEK with a new PIN (or removes PIN if null).
     * Does NOT touch diary files.
     */
    fun rewrapDek(currentDek: ByteArray, newPin: CharArray?, keepBiometrics: Boolean = false) {
        val env = if (exists()) readEnvelope() else null
        val bioCopy = if (keepBiometrics) env?.biometricWrappedDek else {
            keyStoreProvider.deleteKey(biometricKeystoreAlias)
            null
        }
        wrapAndSave(currentDek, newPin, existingBioWrappedDek = bioCopy)
    }

    /**
     * Atomically migrates PIN wrap to [newIterations] without modifying DEK, Keystore keys, diaries, or media.
     * Writes to tmp file, verifies full read-back decryption matches [currentDek], and only then swaps over meta.dat.
     */
    fun rewrapIterationsAtomic(
        currentDek: ByteArray,
        pin: CharArray,
        newIterations: Int,
        beforeCommitHook: ((File) -> Unit)? = null
    ) {
        val existing = readEnvelope()
        check(existing.hasPasscode) { "Cannot rewrap iterations without passcode" }
        if (existing.iterations <= newIterations) return

        val newSalt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val aadInner = createAadInner()
        val aadOuter = createAadOuter(newIterations)
        val innerCiphertext = keyStoreProvider.encrypt(keystoreAlias, currentDek, aadInner)
        val pinKey = CryptoUtils.deriveKeyPbkdf2(pin, newSalt, newIterations)
        val finalWrappedDek = try {
            CryptoUtils.aesGcmEncrypt(pinKey, innerCiphertext, aadOuter)
        } finally {
            CryptoUtils.zeroize(pinKey)
        }

        val candidate = EnvelopeData(
            salt = newSalt,
            iterations = newIterations,
            hasPasscode = true,
            wrappedDek = finalWrappedDek,
            biometricWrappedDek = existing.biometricWrappedDek
        )

        saveEnvelopeAtomic(candidate, verifyWith = { writtenFile ->
            beforeCommitHook?.invoke(writtenFile)
            val parsed = parseEnvelopeBytes(writtenFile.readBytes())
            check(parsed.iterations == newIterations) { "Written iterations mismatch" }
            val checkKey = CryptoUtils.deriveKeyPbkdf2(pin, parsed.salt, parsed.iterations)
            val checkDek = try {
                val inner = CryptoUtils.aesGcmDecrypt(checkKey, parsed.wrappedDek, createAadOuter(parsed.iterations))
                keyStoreProvider.decrypt(keystoreAlias, inner, aadInner)
            } finally {
                CryptoUtils.zeroize(checkKey)
            }
            try {
                check(MessageDigest.isEqual(checkDek, currentDek)) { "Rewrap verification DEK mismatch" }
            } finally {
                CryptoUtils.zeroize(checkDek)
            }
        })
    }

    fun hasPasscode(): Boolean {
        val hdr = readHeaderFlags()
        if (hdr != null) return hdr.hasPasscode
        if (!exists()) return false
        return runCatching { readEnvelope().hasPasscode }.getOrDefault(false)
    }

    fun hasBiometric(): Boolean {
        val hdr = readHeaderFlags()
        if (hdr != null) return hdr.hasBiometric
        if (!exists()) return false
        return runCatching { readEnvelope().biometricWrappedDek != null }.getOrDefault(false)
    }

    private fun wrapAndSave(dek: ByteArray, pin: CharArray?, existingBioWrappedDek: ByteArray?) {
        val salt = CryptoUtils.secureRandomBytes(CryptoUtils.SALT_SIZE_BYTES)
        val iterations = resolveTargetIterations()
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

        saveEnvelopeAtomic(EnvelopeData(salt, iterations, hasPasscode, finalWrappedDek, existingBioWrappedDek))
    }

    private fun createAadInner(): ByteArray =
        "LKV1_${vaultId.dirName}_inner".toByteArray(Charsets.UTF_8)

    private fun createAadOuter(iter: Int): ByteArray =
        "LKV1_${vaultId.dirName}_outer_$iter".toByteArray(Charsets.UTF_8)

    private fun serializeEnvelope(data: EnvelopeData): ByteArray {
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
        return baos.toByteArray()
    }

    private fun saveEnvelopeAtomic(data: EnvelopeData, verifyWith: ((File) -> Unit)? = null) {
        vaultDir.mkdirs()
        val bytes = serializeEnvelope(data)
        try {
            if (tmpMetaFile.exists()) tmpMetaFile.delete()
            FileOutputStream(tmpMetaFile).use { fos ->
                fos.write(bytes)
                fos.flush()
                runCatching { if (fos.fd.valid()) fos.fd.sync() }
            }
            verifyWith?.invoke(tmpMetaFile)

            if (bakMetaFile.exists()) bakMetaFile.delete()
            if (metaFile.exists()) {
                if (!metaFile.renameTo(bakMetaFile)) {
                    throw IllegalStateException("Failed to back up existing meta.dat before commit")
                }
            }
            if (!tmpMetaFile.renameTo(metaFile)) {
                if (bakMetaFile.exists()) bakMetaFile.renameTo(metaFile)
                throw IllegalStateException("Failed to commit new meta.dat")
            }
            SafeStorageWriter.fsyncDirectory(vaultDir)
            if (bakMetaFile.exists()) bakMetaFile.delete()
        } catch (t: Throwable) {
            if (tmpMetaFile.exists()) tmpMetaFile.delete()
            if (!metaFile.exists() && bakMetaFile.exists()) {
                bakMetaFile.renameTo(metaFile)
            }
            throw t
        }
    }

    private fun readEnvelope(): EnvelopeData {
        if (!metaFile.exists() && bakMetaFile.exists()) {
            bakMetaFile.renameTo(metaFile)
        }
        val bytes = metaFile.readBytes()
        return parseEnvelopeBytes(bytes)
    }

    private fun parseEnvelopeBytes(bytes: ByteArray): EnvelopeData {
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
        if (bakMetaFile.exists()) bakMetaFile.delete()
        if (vaultDir.exists()) vaultDir.deleteRecursively()
    }
}
