package com.likkho.app.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class CryptoUtilsTest {

    @Test
    fun aesGcmRoundTrip_succeedsWithValidKeyAndAad() {
        val key = CryptoUtils.secureRandomBytes(32)
        val plaintext = "Hello Secret Diary!".toByteArray(Charsets.UTF_8)
        val aad = "LKD1_meta_1".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoUtils.aesGcmEncrypt(key, plaintext, aad)
        val decrypted = CryptoUtils.aesGcmDecrypt(key, encrypted, aad)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = AEADBadTagException::class)
    fun aesGcm_failsWhenCiphertextTampered() {
        val key = CryptoUtils.secureRandomBytes(32)
        val plaintext = "Sensitive Content".toByteArray(Charsets.UTF_8)
        val aad = "LKD1_aad".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoUtils.aesGcmEncrypt(key, plaintext, aad)
        // Tamper a byte in ciphertext
        encrypted[encrypted.size - 5] = (encrypted[encrypted.size - 5].toInt() xor 0xFF).toByte()

        CryptoUtils.aesGcmDecrypt(key, encrypted, aad)
    }

    @Test(expected = AEADBadTagException::class)
    fun aesGcm_failsWhenAadSwapped() {
        val key = CryptoUtils.secureRandomBytes(32)
        val plaintext = "Sensitive Content".toByteArray(Charsets.UTF_8)
        val originalAad = "diary_1".toByteArray(Charsets.UTF_8)
        val swappedAad = "diary_2".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoUtils.aesGcmEncrypt(key, plaintext, originalAad)

        CryptoUtils.aesGcmDecrypt(key, encrypted, swappedAad)
    }

    @Test(expected = AEADBadTagException::class)
    fun aesGcm_failsWithWrongKey() {
        val key1 = CryptoUtils.secureRandomBytes(32)
        val key2 = CryptoUtils.secureRandomBytes(32)
        val plaintext = "Top Secret".toByteArray(Charsets.UTF_8)

        val encrypted = CryptoUtils.aesGcmEncrypt(key1, plaintext)

        CryptoUtils.aesGcmDecrypt(key2, encrypted)
    }

    @Test
    fun pbkdf2_derivesDeterministicKey() {
        val pin = "1234".toCharArray()
        val salt = CryptoUtils.secureRandomBytes(16)
        val iterations = 5000 // Fast for unit test

        val key1 = CryptoUtils.deriveKeyPbkdf2(pin, salt, iterations)
        val key2 = CryptoUtils.deriveKeyPbkdf2(pin, salt, iterations)

        assertArrayEquals(key1, key2)
        assertEquals(32, key1.size)

        val wrongPin = "5678".toCharArray()
        val keyWrong = CryptoUtils.deriveKeyPbkdf2(wrongPin, salt, iterations)
        assertFalse(CryptoUtils.constantTimeEquals(key1, keyWrong))
    }

    @Test
    fun zeroize_clearsMemory() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        CryptoUtils.zeroize(bytes)
        assertTrue(bytes.all { it == 0.toByte() })

        val chars = charArrayOf('p', 'a', 's', 's')
        CryptoUtils.zeroize(chars)
        assertTrue(chars.all { it == '\u0000' })
    }
}
