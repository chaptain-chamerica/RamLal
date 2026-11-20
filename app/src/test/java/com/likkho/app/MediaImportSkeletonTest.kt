package com.likkho.app

import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.EncryptedMediaRepository
import com.likkho.app.storage.VaultId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MediaImportSkeletonTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun countingInputStream_countsBytesThrottlesAt100msAndFiresEofOnce() {
        val total = 100L * 1024L
        val payload = ByteArray(total.toInt()) { (it % 127).toByte() }
        var nowMs = 0L
        val progressEvents = mutableListOf<Pair<Long, Long>>()
        var eofCount = 0
        var firstReadCount = 0

        val stream = CountingInputStream(
            delegate = ByteArrayInputStream(payload),
            totalBytes = total,
            throttleMs = 100L,
            clockMs = { nowMs },
            onFirstRead = { firstReadCount++ },
            onProgress = { done, tot -> progressEvents.add(done to tot) },
            onEof = { eofCount++ }
        )

        val buf = ByteArray(8192)
        // Read chunk 1 at t=0 -> should emit once
        assertEquals(8192, stream.read(buf, 0, buf.size))
        // Read chunks within 100ms (t=10..90) -> must be throttled (no extra emissions)
        for (step in 1..9) {
            nowMs = step * 10L
            assertEquals(8192, stream.read(buf, 0, buf.size))
        }
        assertEquals(1, progressEvents.size)

        // Read at t=100 -> should emit 2nd progress event
        nowMs = 100L
        assertEquals(8192, stream.read(buf, 0, buf.size))
        assertEquals(2, progressEvents.size)
        assertEquals(11L * 8192L, progressEvents[1].first)
        assertEquals(total, progressEvents[1].second)

        // Drain rest of stream past EOF, and call single-byte read() to verify EOF fires once
        nowMs = 110L
        while (stream.read(buf, 0, buf.size) != -1) {
            nowMs += 5L
        }
        assertEquals(-1, stream.read())
        assertEquals(1, firstReadCount)
        assertEquals(1, eofCount)
        assertEquals(total, stream.bytesRead)
    }

    @Test
    fun countingInputStream_singleByteReads_countsCorrectly() {
        val payload = byteArrayOf(10, 20, 30, 40)
        var eofCount = 0
        val stream = CountingInputStream(
            delegate = ByteArrayInputStream(payload),
            totalBytes = payload.size.toLong(),
            onEof = { eofCount++ }
        )

        val readBytes = mutableListOf<Byte>()
        while (true) {
            val b = stream.read()
            if (b < 0) break
            readBytes.add(b.toByte())
        }
        assertEquals(payload.toList(), readBytes)
        assertEquals(4L, stream.bytesRead)
        assertEquals(1, eofCount)
    }

    @Test
    fun countingInputStream_cancelledThrowsIOExceptionWithImportCancelledMessage() {
        val payload = ByteArray(32 * 1024) { 1 }
        val cancelled = AtomicBoolean(false)
        val stream = CountingInputStream(
            delegate = ByteArrayInputStream(payload),
            totalBytes = payload.size.toLong(),
            isCancelled = { cancelled.get() }
        )

        val buf = ByteArray(8192)
        assertEquals(8192, stream.read(buf, 0, buf.size))

        cancelled.set(true)
        try {
            stream.read(buf, 0, buf.size)
            fail("Expected IOException(\"Import cancelled\")")
        } catch (e: IOException) {
            assertEquals("Import cancelled", e.message)
        }
    }

    @Test
    fun cancellingMidImport_forVideoImageAudio_leavesNoTmpAndNoLkmInMediaDir() {
        val testDek = CryptoUtils.secureRandomBytes(32)
        for (kind in ImportingMediaKind.entries) {
            val vaultDir = tempFolder.newFolder("vault_${kind.name.lowercase()}")
            val mediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, vaultDir)
            mediaRepo.ensureDirs()

            val cancelled = AtomicBoolean(false)
            val chunksServed = AtomicInteger(0)
            // Stream that serves 8KB chunks indefinitely until 3 chunks have been read, then flips cancelled = true
            val syntheticStream = object : InputStream() {
                override fun read(): Int = 1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val count = chunksServed.incrementAndGet()
                    if (count >= 4) {
                        cancelled.set(true)
                    }
                    b.fill(0x2A, off, off + len)
                    return len
                }
            }

            val phaseLog = mutableListOf<ImportPhase>()
            val mediaId = CryptoUtils.randomId("med_${kind.name.lowercase()}_")
            val counting = CountingInputStream(
                delegate = syntheticStream,
                totalBytes = 1024L * 1024L,
                isCancelled = { cancelled.get() },
                onFirstRead = { phaseLog.add(ImportPhase.ENCRYPTING) },
                onEof = { phaseLog.add(ImportPhase.VERIFYING) }
            )

            try {
                counting.use { s ->
                    mediaRepo.importMedia(mediaId, s, testDek, 1024L * 1024L)
                }
                fail("Expected cancellation exception for $kind")
            } catch (e: IOException) {
                assertEquals("Import cancelled", e.message)
            }

            val allFiles = mediaRepo.mediaDir.listFiles() ?: emptyArray()
            val tmpFiles = allFiles.filter { it.name.endsWith(".tmp") }
            val lkmFiles = allFiles.filter { it.name.endsWith(".lkm") }
            assertTrue("No .tmp files should remain after cancelling $kind: $tmpFiles", tmpFiles.isEmpty())
            assertTrue("No .lkm files should remain after cancelling $kind: $lkmFiles", lkmFiles.isEmpty())
            assertTrue("Should have entered ENCRYPTING phase before cancel for $kind", phaseLog.contains(ImportPhase.ENCRYPTING))
            assertFalse("Should not reach VERIFYING when cancelled mid-read for $kind", phaseLog.contains(ImportPhase.VERIFYING))
        }
    }

    @Test
    fun fastImportUnder300Ms_neverShowsSkeleton_whileSlowImportShowsAt300Ms() {
        val item = ImportingItem(
            kind = ImportingMediaKind.VIDEO,
            name = "clip.mp4",
            x = 24f,
            y = 140f,
            width = 240f,
            height = 150f,
            phase = ImportPhase.ENCRYPTING,
            bytesDone = 1024L,
            bytesTotal = 4096L,
        )

        assertFalse(shouldShowImportSkeleton(item, 0L))
        assertFalse(shouldShowImportSkeleton(item, 150L))
        assertFalse(shouldShowImportSkeleton(item, 299L))
        assertTrue(shouldShowImportSkeleton(item, 300L))
        assertTrue(shouldShowImportSkeleton(item, 1200L))
        assertFalse(shouldShowImportSkeleton(null, 5000L))
    }

    @Test
    fun saveButtonDisabledWhileImporting_reEnabledAfterCancelOrFinish() {
        assertTrue(canSaveDiary(null))

        val importing = ImportingItem(
            kind = ImportingMediaKind.IMAGE,
            name = "photo.jpg",
            x = 24f,
            y = 120f,
            width = 160f,
            height = 120f,
            phase = ImportPhase.PREPARING,
        )
        assertFalse(canSaveDiary(importing))
        assertFalse(canSaveDiary(importing.copy(phase = ImportPhase.ENCRYPTING)))
        assertFalse(canSaveDiary(importing.copy(phase = ImportPhase.VERIFYING)))

        // After cancel or failure clears importingItem -> null, Save is re-enabled
        assertTrue(canSaveDiary(null))
    }

    @Test
    fun statusTextFormatting_matchesPhasesAndTotalAvailability() {
        val base = ImportingItem(
            kind = ImportingMediaKind.VIDEO,
            name = "vacation.mp4",
            x = 24f,
            y = 140f,
            width = 240f,
            height = 150f,
            phase = ImportPhase.PREPARING,
            bytesDone = 0L,
            bytesTotal = 500L * 1024L * 1024L
        )
        assertEquals("Preparing\u2026", formatImportStatusText(base))
        assertEquals(
            "Encrypting\u2026 42% (210 / 500 MB)",
            formatImportStatusText(base.copy(phase = ImportPhase.ENCRYPTING, bytesDone = 210L * 1024L * 1024L))
        )
        assertEquals(
            "Encrypting\u2026",
            formatImportStatusText(base.copy(phase = ImportPhase.ENCRYPTING, bytesDone = 1024L, bytesTotal = 0L))
        )
        assertEquals("Verifying\u2026", formatImportStatusText(base.copy(phase = ImportPhase.VERIFYING)))
    }
}
