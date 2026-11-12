package com.likkho.app.storage

import com.likkho.app.storage.model.SavedPasswordRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.AEADBadTagException

class PasswordRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var passwordRepository: PasswordRepository
    private val testDek = CryptoUtils.secureRandomBytes(32)

    @Before
    fun setUp() {
        vaultDir = tempFolder.newFolder("v1_pwd_test")
        passwordRepository = PasswordRepository(VaultId.PRIMARY, vaultDir)
    }

    @Test
    fun nonExistentFile_returnsEmptyList() {
        val passwords = passwordRepository.loadPasswords(testDek)
        assertTrue(passwords.isEmpty())
    }

    @Test
    fun saveAndLoadPasswords_roundTrip() {
        val list = listOf(
            SavedPasswordRecord(id = "p1", name = "Email", password = "SecretPassword123!", createdAt = 1000L),
            SavedPasswordRecord(id = "p2", name = "Banking", password = "SuperSecurePin9988", createdAt = 2000L)
        )

        passwordRepository.savePasswords(list, testDek)
        assertTrue(passwordRepository.passwordsFile.exists())

        val loaded = passwordRepository.loadPasswords(testDek)
        assertEquals(2, loaded.size)
        assertEquals("Email", loaded[0].name)
        assertEquals("SecretPassword123!", loaded[0].password)
        assertEquals("Banking", loaded[1].name)
        assertEquals("SuperSecurePin9988", loaded[1].password)
    }

    @Test
    fun upsertPassword_addsAndUpdatesCorrectly() {
        val item1 = SavedPasswordRecord("pwd_1", "GitHub", "token_abc")
        passwordRepository.upsertPassword(item1, testDek)

        val item2 = SavedPasswordRecord("pwd_2", "AWS", "secret_xyz")
        passwordRepository.upsertPassword(item2, testDek)

        var loaded = passwordRepository.loadPasswords(testDek)
        assertEquals(2, loaded.size)

        // Update item 1
        val item1Updated = SavedPasswordRecord("pwd_1", "GitHub", "new_token_12345")
        passwordRepository.upsertPassword(item1Updated, testDek)

        loaded = passwordRepository.loadPasswords(testDek)
        assertEquals(2, loaded.size)
        assertEquals("new_token_12345", loaded.first { it.id == "pwd_1" }.password)
    }

    @Test
    fun deletePassword_removesCorrectItem() {
        val item1 = SavedPasswordRecord("pwd_1", "WiFi", "wifi_pass")
        val item2 = SavedPasswordRecord("pwd_2", "Router", "admin_pass")
        passwordRepository.savePasswords(listOf(item1, item2), testDek)

        passwordRepository.deletePassword("pwd_1", testDek)

        val loaded = passwordRepository.loadPasswords(testDek)
        assertEquals(1, loaded.size)
        assertEquals("pwd_2", loaded[0].id)
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongKey_failsDecryption() {
        val item = SavedPasswordRecord("pwd_1", "WiFi", "wifi_pass")
        passwordRepository.savePasswords(listOf(item), testDek)

        val wrongDek = CryptoUtils.secureRandomBytes(32)
        passwordRepository.loadPasswords(wrongDek)
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedFile_failsDecryption() {
        val item = SavedPasswordRecord("pwd_1", "WiFi", "wifi_pass")
        passwordRepository.savePasswords(listOf(item), testDek)

        val fileBytes = passwordRepository.passwordsFile.readBytes()
        fileBytes[fileBytes.size - 1] = (fileBytes[fileBytes.size - 1].toInt() xor 0xFF).toByte()
        passwordRepository.passwordsFile.writeBytes(fileBytes)

        passwordRepository.loadPasswords(testDek)
    }
}
