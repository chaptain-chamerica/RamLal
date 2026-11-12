package com.likkho.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PinLengthsRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storageDir: File
    private lateinit var registry: PinLengthsRegistry

    @Before
    fun setUp() {
        storageDir = tempFolder.newFolder("pin_lengths_test")
        registry = PinLengthsRegistry(storageDir)
    }

    @Test
    fun registeredLengths_canAddAndRemoveAnonymously() {
        assertTrue(registry.getRegisteredLengths().isEmpty())

        registry.registerLength(4)
        assertEquals(setOf(4), registry.getRegisteredLengths())

        registry.registerLength(6)
        assertEquals(setOf(4, 6), registry.getRegisteredLengths())

        // File should not contain any vault names
        val rawContent = File(storageDir, PinLengthsRegistry.FILE_NAME).readText()
        assertFalse(rawContent.contains("v1"))
        assertFalse(rawContent.contains("v2"))
        assertFalse(rawContent.contains("primary"))
        assertFalse(rawContent.contains("decoy"))

        // Unregister
        registry.unregisterLength(4)
        assertEquals(setOf(6), registry.getRegisteredLengths())
    }

    @Test
    fun shouldAttemptAutoUnlock_checksRegisteredLengths() {
        registry.registerLength(4)
        registry.registerLength(6)

        assertFalse(registry.shouldAttemptAutoUnlock(3))
        assertTrue(registry.shouldAttemptAutoUnlock(4))
        assertFalse(registry.shouldAttemptAutoUnlock(5))
        assertTrue(registry.shouldAttemptAutoUnlock(6))
    }

    @Test
    fun isMaxRegisteredLength_identifiesBoundary() {
        registry.registerLength(4)
        registry.registerLength(6)

        assertFalse(registry.isMaxRegisteredLength(4))
        assertFalse(registry.isMaxRegisteredLength(5))
        assertTrue(registry.isMaxRegisteredLength(6))
        assertTrue(registry.isMaxRegisteredLength(7))
    }
}
