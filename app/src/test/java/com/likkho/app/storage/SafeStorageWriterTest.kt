package com.likkho.app.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SafeStorageWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var targetDir: File

    @Before
    fun setUp() {
        targetDir = tempFolder.newFolder("safe_writer_test")
    }

    @Test
    fun safeWrite_createsTargetFileAndRemovesTmpAndBak() {
        val targetFile = File(targetDir, "test_doc.dat")
        val data = "Important Content".toByteArray(Charsets.UTF_8)

        SafeStorageWriter.safeWrite(targetFile, data)

        assertTrue(targetFile.exists())
        assertArrayEquals(data, targetFile.readBytes())
        assertFalse(File(targetDir, "test_doc.dat.tmp").exists())
        assertFalse(File(targetDir, "test_doc.dat.bak").exists())
    }

    @Test
    fun safeWrite_overwritesExistingAtomically() {
        val targetFile = File(targetDir, "test_doc.dat")
        val initialData = "Version 1".toByteArray(Charsets.UTF_8)
        SafeStorageWriter.safeWrite(targetFile, initialData)

        val updatedData = "Version 2 (Updated)".toByteArray(Charsets.UTF_8)
        SafeStorageWriter.safeWrite(targetFile, updatedData)

        assertTrue(targetFile.exists())
        assertArrayEquals(updatedData, targetFile.readBytes())
        assertFalse(File(targetDir, "test_doc.dat.bak").exists())
    }

    @Test
    fun verificationFailure_abortsWriteAndPreservesOriginal() {
        val targetFile = File(targetDir, "important_vault.dat")
        val originalData = "Original Intact Data".toByteArray(Charsets.UTF_8)
        SafeStorageWriter.safeWrite(targetFile, originalData)

        val corruptedData = "Corrupt Write Attempt".toByteArray(Charsets.UTF_8)

        var failed = false
        try {
            SafeStorageWriter.safeWrite(targetFile, corruptedData) { _ ->
                // Simulate verification rejection (e.g. invalid checksum or unwrap failure)
                throw IllegalArgumentException("Data integrity check failed!")
            }
        } catch (e: StorageVerificationException) {
            failed = true
        }

        assertTrue("Verification rejection must throw StorageVerificationException", failed)
        // Original data must remain 100% intact
        assertArrayEquals(originalData, targetFile.readBytes())
        // Stale tmp file must be cleaned up
        assertFalse(File(targetDir, "important_vault.dat.tmp").exists())
    }
}
