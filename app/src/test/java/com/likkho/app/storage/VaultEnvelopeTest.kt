package com.likkho.app.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.AEADBadTagException

class VaultEnvelopeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var keyStoreProvider: KeyStoreProvider
    private lateinit var envelope: VaultEnvelope

    @Before
    fun setUp() {
        vaultDir = tempFolder.newFolder("v1")
        keyStoreProvider = InMemoryKeyStoreProvider()
        envelope = VaultEnvelope(
            VaultId.PRIMARY,
            vaultDir,
            keyStoreProvider,
            pbkdf2Iterations = 5000 // Fast for unit tests
        )
    }

    @Test
    fun createVault_withoutPasscode_unwrapsWithKeystoreAlone() {
        val dek = envelope.createVault(pin = null)
        assertTrue(envelope.exists())
        assertFalse(envelope.hasPasscode())
        assertEquals(32, dek.size)

        val unwrapped = envelope.unwrapNoPasscode()
        assertArrayEquals(dek, unwrapped)
    }

    @Test
    fun createVault_withPasscode_requiresPinAndUnwrapsCorrectly() {
        val pin = "1234".toCharArray()
        val dek = envelope.createVault(pin)
        assertTrue(envelope.exists())
        assertTrue(envelope.hasPasscode())

        val unwrapped = envelope.unwrapWithPin(pin)
        assertArrayEquals(dek, unwrapped)
    }

    @Test(expected = AEADBadTagException::class)
    fun unwrapWithWrongPin_throwsAEADBadTagException() {
        val pin = "1234".toCharArray()
        val wrongPin = "9999".toCharArray()
        envelope.createVault(pin)

        envelope.unwrapWithPin(wrongPin)
    }

    @Test
    fun rewrapDek_withNewPin_preservesOriginalDek() {
        val originalPin = "1111".toCharArray()
        val originalDek = envelope.createVault(originalPin)

        val newPin = "2222".toCharArray()
        envelope.rewrapDek(originalDek, newPin)

        val unwrappedWithNewPin = envelope.unwrapWithPin(newPin)
        assertArrayEquals(originalDek, unwrappedWithNewPin)

        // Old pin must now fail
        var oldPinFailed = false
        try {
            envelope.unwrapWithPin(originalPin)
        } catch (e: AEADBadTagException) {
            oldPinFailed = true
        }
        assertTrue("Old PIN should fail after re-wrap", oldPinFailed)
    }

    @Test
    fun removePasscode_switchesToKeystoreOnly_preservesDek() {
        val pin = "1234".toCharArray()
        val dek = envelope.createVault(pin)
        assertTrue(envelope.hasPasscode())

        envelope.rewrapDek(dek, newPin = null)
        assertFalse(envelope.hasPasscode())

        val unwrapped = envelope.unwrapNoPasscode()
        assertArrayEquals(dek, unwrapped)
    }

    @Test
    fun biometricWrap_allowsUnwrapViaCryptoObjectCipher_andRejectsDirectDecrypt() {
        val pin = "1234".toCharArray()
        val dek = envelope.createVault(pin)
        assertFalse(envelope.hasBiometric())

        val encCipher = envelope.prepareBiometricEncryptCipher()
        envelope.enableBiometricWithCipher(dek, encCipher)
        assertTrue(envelope.hasBiometric())

        // Direct KeyStore decrypt without CryptoObject must be blocked
        var directBlocked = false
        try {
            keyStoreProvider.decrypt(envelope.biometricKeystoreAlias, ByteArray(48), "aad".toByteArray())
        } catch (_: SecurityException) {
            directBlocked = true
        }
        assertTrue("Biometric Keystore key must forbid direct decrypt without BiometricPrompt", directBlocked)

        val decCipher = envelope.prepareBiometricDecryptCipher()
        val bioDek = envelope.unwrapWithBiometricCipher(decCipher)
        assertArrayEquals(dek, bioDek)

        envelope.clearBiometricKeyAndEnvelope()
        assertFalse(envelope.hasBiometric())
    }
}
