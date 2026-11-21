package com.likkho.app.storage

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BiometricKeyInvalidatedException(cause: Throwable? = null) :
    RuntimeException("Biometrics changed. Unlock with PIN and enable again.", cause)

fun isBiometricInvalidationError(t: Throwable?): Boolean {
    var cur = t
    while (cur != null) {
        if (cur is BiometricKeyInvalidatedException) return true
        val name = cur.javaClass.simpleName
        if (name == "KeyPermanentlyInvalidatedException") return true
        cur = cur.cause
    }
    return false
}

/**
 * Abstraction over Hardware/Keystore encryption keys.
 * Enables Android Keystore (with StrongBox fallback) in production and
 * in-memory software keys in local JVM test environments.
 */
interface KeyStoreProvider {
    fun getOrCreateAesGcmKey(alias: String, requireBiometric: Boolean = false): SecretKey
    fun deleteKey(alias: String)
    fun hasKey(alias: String): Boolean
    fun encrypt(alias: String, plaintext: ByteArray, aad: ByteArray? = null): ByteArray
    fun decrypt(alias: String, ivAndCiphertext: ByteArray, aad: ByteArray? = null): ByteArray
    fun getOrCreateHmacKey(alias: String): SecretKey
    fun computeHmac(alias: String, data: ByteArray): ByteArray
    fun verifyHmac(alias: String, data: ByteArray, expectedHmac: ByteArray): Boolean
    fun getBiometricCipher(alias: String, mode: Int, iv: ByteArray? = null): Cipher
}

/**
 * Hardware-backed Android Keystore implementation.
 * Attempts StrongBox when available, falling back to standard TEE AndroidKeyStore.
 */
class AndroidKeyStoreProvider : KeyStoreProvider {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    override fun hasKey(alias: String): Boolean = runCatching { keyStore.containsAlias(alias) }.getOrDefault(false)

    override fun deleteKey(alias: String) {
        runCatching {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        }
    }

    override fun getOrCreateAesGcmKey(alias: String, requireBiometric: Boolean): SecretKey {
        val existing = runCatching { keyStore.getKey(alias, null) as? SecretKey }.getOrNull()
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT

        fun buildSpec(useStrongBox: Boolean): KeyGenParameterSpec {
            val builder = KeyGenParameterSpec.Builder(alias, purposes)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)

            if (requireBiometric) {
                builder.setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setInvalidatedByBiometricEnrollment(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG
                    )
                }
            }

            if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(true)
            }
            return builder.build()
        }

        return try {
            keyGenerator.init(buildSpec(useStrongBox = true))
            keyGenerator.generateKey()
        } catch (e: Exception) {
            // Fallback gracefully to non-StrongBox standard AndroidKeyStore
            keyGenerator.init(buildSpec(useStrongBox = false))
            keyGenerator.generateKey()
        }
    }

    override fun encrypt(alias: String, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val key = getOrCreateAesGcmKey(alias)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv

        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    override fun decrypt(alias: String, ivAndCiphertext: ByteArray, aad: ByteArray?): ByteArray {
        val key = (keyStore.getKey(alias, null) as? SecretKey)
            ?: throw IllegalStateException("KeyStore key $alias not found")

        require(ivAndCiphertext.size >= CryptoUtils.GCM_IV_SIZE_BYTES + 16) { "Ciphertext too short" }
        val iv = ByteArray(CryptoUtils.GCM_IV_SIZE_BYTES)
        System.arraycopy(ivAndCiphertext, 0, iv, 0, CryptoUtils.GCM_IV_SIZE_BYTES)

        val ciphertextLen = ivAndCiphertext.size - CryptoUtils.GCM_IV_SIZE_BYTES
        val ciphertext = ByteArray(ciphertextLen)
        System.arraycopy(ivAndCiphertext, CryptoUtils.GCM_IV_SIZE_BYTES, ciphertext, 0, ciphertextLen)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(CryptoUtils.GCM_TAG_SIZE_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    override fun getOrCreateHmacKey(alias: String): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun computeHmac(alias: String, data: ByteArray): ByteArray {
        val key = getOrCreateHmacKey(alias)
        val mac = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256)
        mac.init(key)
        return mac.doFinal(data)
    }

    override fun verifyHmac(alias: String, data: ByteArray, expectedHmac: ByteArray): Boolean {
        val computed = computeHmac(alias, data)
        return CryptoUtils.constantTimeEquals(computed, expectedHmac)
    }

    override fun getBiometricCipher(alias: String, mode: Int, iv: ByteArray?): Cipher {
        return try {
            val key = if (mode == Cipher.ENCRYPT_MODE) {
                deleteKey(alias)
                getOrCreateAesGcmKey(alias, requireBiometric = true)
            } else {
                (keyStore.getKey(alias, null) as? SecretKey)
                    ?: throw BiometricKeyInvalidatedException()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (mode == Cipher.ENCRYPT_MODE) {
                cipher.init(Cipher.ENCRYPT_MODE, key)
            } else {
                requireNotNull(iv) { "IV required for decryption" }
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(CryptoUtils.GCM_TAG_SIZE_BITS, iv))
            }
            cipher
        } catch (t: Throwable) {
            if (isBiometricInvalidationError(t)) {
                throw BiometricKeyInvalidatedException(t)
            }
            throw t
        }
    }

    companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}

/**
 * In-memory provider for deterministic JVM testing without hardware AndroidKeyStore dependency.
 * Enforces biometric alias isolation: keys created with requireBiometric=true cannot be used via direct
 * [encrypt]/[decrypt] calls and must go through [getBiometricCipher] + BiometricPrompt authentication.
 */
class InMemoryKeyStoreProvider : KeyStoreProvider {
    private val keys = mutableMapOf<String, ByteArray>()
    private val hmacKeys = mutableMapOf<String, ByteArray>()
    private val biometricAliases = mutableSetOf<String>()
    private val invalidatedAliases = mutableSetOf<String>()

    override fun hasKey(alias: String): Boolean = keys.containsKey(alias) || hmacKeys.containsKey(alias)

    override fun deleteKey(alias: String) {
        keys.remove(alias)?.let { CryptoUtils.zeroize(it) }
        hmacKeys.remove(alias)?.let { CryptoUtils.zeroize(it) }
        biometricAliases.remove(alias)
        invalidatedAliases.remove(alias)
    }

    fun invalidateBiometricKey(alias: String) {
        invalidatedAliases.add(alias)
    }

    override fun getOrCreateAesGcmKey(alias: String, requireBiometric: Boolean): SecretKey {
        if (requireBiometric) {
            biometricAliases.add(alias)
        }
        val raw = keys.getOrPut(alias) { CryptoUtils.secureRandomBytes(32) }
        return SecretKeySpec(raw, "AES")
    }

    override fun encrypt(alias: String, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        if (alias in biometricAliases) {
            throw SecurityException("Biometric key $alias cannot be used directly without BiometricPrompt CryptoObject")
        }
        val rawKey = keys.getOrPut(alias) { CryptoUtils.secureRandomBytes(32) }
        return CryptoUtils.aesGcmEncrypt(rawKey, plaintext, aad)
    }

    override fun decrypt(alias: String, ivAndCiphertext: ByteArray, aad: ByteArray?): ByteArray {
        if (alias in biometricAliases) {
            throw SecurityException("Biometric key $alias cannot be decrypted directly without BiometricPrompt CryptoObject")
        }
        val rawKey = keys[alias] ?: throw IllegalStateException("Key $alias not found")
        return CryptoUtils.aesGcmDecrypt(rawKey, ivAndCiphertext, aad)
    }

    override fun getOrCreateHmacKey(alias: String): SecretKey {
        val raw = hmacKeys.getOrPut(alias) { CryptoUtils.secureRandomBytes(32) }
        return SecretKeySpec(raw, "HmacSHA256")
    }

    override fun computeHmac(alias: String, data: ByteArray): ByteArray {
        val raw = hmacKeys.getOrPut(alias) { CryptoUtils.secureRandomBytes(32) }
        return CryptoUtils.computeHmacSha256(raw, data)
    }

    override fun verifyHmac(alias: String, data: ByteArray, expectedHmac: ByteArray): Boolean {
        val computed = computeHmac(alias, data)
        return CryptoUtils.constantTimeEquals(computed, expectedHmac)
    }

    override fun getBiometricCipher(alias: String, mode: Int, iv: ByteArray?): Cipher {
        if (mode == Cipher.DECRYPT_MODE && (alias in invalidatedAliases || !keys.containsKey(alias))) {
            throw BiometricKeyInvalidatedException()
        }
        val key = if (mode == Cipher.ENCRYPT_MODE) {
            invalidatedAliases.remove(alias)
            keys.remove(alias)?.let { CryptoUtils.zeroize(it) }
            getOrCreateAesGcmKey(alias, requireBiometric = true)
        } else {
            biometricAliases.add(alias)
            val raw = keys[alias] ?: throw BiometricKeyInvalidatedException()
            SecretKeySpec(raw, "AES")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (mode == Cipher.ENCRYPT_MODE) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
        } else {
            requireNotNull(iv) { "IV required for decryption" }
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(CryptoUtils.GCM_TAG_SIZE_BITS, iv))
        }
        return cipher
    }
}
