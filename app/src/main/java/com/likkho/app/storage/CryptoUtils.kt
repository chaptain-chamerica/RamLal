package com.likkho.app.storage

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Standard cryptographic utilities based strictly on javax.crypto (AES-256-GCM, PBKDF2WithHmacSHA256, HMAC-SHA256).
 * Complies with STORAGE_SPEC Section 2 & 10 (no invented crypto, constant-time checks, memory zeroizing).
 */
object CryptoUtils {

    const val AES_KEY_SIZE_BYTES = 32 // 256-bit AES
    const val GCM_IV_SIZE_BYTES = 12  // 96-bit recommended for AES-GCM
    const val GCM_TAG_SIZE_BITS = 128 // 16-byte authentication tag
    const val DEFAULT_PBKDF2_ITERATIONS = 600_000
    const val SALT_SIZE_BYTES = 16

    private val secureRandom = SecureRandom()

    fun secureRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    fun zeroize(bytes: ByteArray?) {
        if (bytes != null) {
            Arrays.fill(bytes, 0.toByte())
        }
    }

    fun zeroize(chars: CharArray?) {
        if (chars != null) {
            Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Derives a 256-bit key from PIN using PBKDF2-HMAC-SHA256.
     * Iterations are stored in the envelope header so it can be increased in future migrations.
     */
    fun deriveKeyPbkdf2(
        pin: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
        keyLengthBits: Int = 256
    ): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /**
     * Encrypts plaintext using AES-256-GCM with optional Additional Authenticated Data (AAD).
     * Output format: [12-byte IV] + [ciphertext + 16-byte GCM tag]
     */
    fun aesGcmEncrypt(
        key: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray? = null,
        iv: ByteArray = secureRandomBytes(GCM_IV_SIZE_BYTES)
    ): ByteArray {
        require(key.size == AES_KEY_SIZE_BYTES) { "AES-256 requires exactly 32-byte key" }
        require(iv.size == GCM_IV_SIZE_BYTES) { "GCM requires 12-byte IV" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        val ciphertext = cipher.doFinal(plaintext)

        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    /**
     * Decrypts [ivAndCiphertext] using AES-256-GCM and verifies AAD and authentication tag.
     * Throws [javax.crypto.AEADBadTagException] if key is incorrect or data/AAD was tampered.
     */
    fun aesGcmDecrypt(
        key: ByteArray,
        ivAndCiphertext: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        require(key.size == AES_KEY_SIZE_BYTES) { "AES-256 requires exactly 32-byte key" }
        require(ivAndCiphertext.size >= GCM_IV_SIZE_BYTES + (GCM_TAG_SIZE_BITS / 8)) {
            "Ciphertext too short for GCM IV + tag"
        }

        val iv = ByteArray(GCM_IV_SIZE_BYTES)
        System.arraycopy(ivAndCiphertext, 0, iv, 0, GCM_IV_SIZE_BYTES)

        val ciphertextLen = ivAndCiphertext.size - GCM_IV_SIZE_BYTES
        val ciphertext = ByteArray(ciphertextLen)
        System.arraycopy(ivAndCiphertext, GCM_IV_SIZE_BYTES, ciphertext, 0, ciphertextLen)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_SIZE_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        if (aad != null && aad.isNotEmpty()) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(ciphertext)
    }

    /**
     * Computes HMAC-SHA256 over [data] with [key].
     */
    fun computeHmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Computes SHA-256 digest over [data].
     */
    fun sha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data)
    }

    fun sha256Hex(data: ByteArray): String {
        return sha256(data).joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(stream: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: java.io.File): String {
        return java.io.FileInputStream(file).use { sha256Hex(it) }
    }

    /**
     * Constant-time comparison to prevent timing side-channel attacks.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        return MessageDigest.isEqual(a, b)
    }

    fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun fromHex(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
        }
        return data
    }

    fun randomBytes(size: Int): ByteArray = secureRandomBytes(size)

    fun pbkdf2Sha256(pin: CharArray, salt: ByteArray, iterations: Int = DEFAULT_PBKDF2_ITERATIONS): ByteArray =
        deriveKeyPbkdf2(pin, salt, iterations)

    fun randomId(prefix: String = ""): String =
        prefix + toHex(secureRandomBytes(8))
}
