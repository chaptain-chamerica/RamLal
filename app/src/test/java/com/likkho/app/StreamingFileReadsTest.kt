package com.likkho.app

import com.likkho.app.editor.FontRegistry
import com.likkho.app.storage.CorruptedDiaryException
import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.DiaryFileCodec
import com.likkho.app.storage.DiaryRepository
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.model.DiaryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

class StreamingFileReadsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fontRegistry_streamValidatedFontToFile_streamsLargeFontWithoutBufferingWholeFile() {
        val dir = tempFolder.newFolder("fonts")
        val destFile = File(dir, "custom_hindi.ttf")

        // Generate a 2 MB valid-header font stream lazily on the fly (only 12-byte header + pattern)
        val totalSize = 2 * 1024 * 1024
        val ttfHeader = byteArrayOf(
            0x00, 0x01, 0x00, 0x00, // TrueType SFNT version 1.0
            0x00, 0x0A, 0x00, 0x80.toByte(),
            0x00, 0x03, 0x00, 0x20
        )
        val expectedDigest = MessageDigest.getInstance("SHA-256").apply {
            update(ttfHeader)
            val tail = ByteArray(totalSize - ttfHeader.size) { (it % 251).toByte() }
            update(tail)
        }.digest().joinToString("") { "%02x".format(it) }

        val lazyStream = object : InputStream() {
            private var pos = 0
            override fun read(): Int {
                if (pos >= totalSize) return -1
                val b = if (pos < ttfHeader.size) ttfHeader[pos].toInt() and 0xFF else ((pos - ttfHeader.size) % 251)
                pos++
                return b
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= totalSize) return -1
                val toRead = minOf(len, totalSize - pos)
                for (i in 0 until toRead) {
                    val idx = pos + i
                    b[off + i] = if (idx < ttfHeader.size) ttfHeader[idx] else ((idx - ttfHeader.size) % 251).toByte()
                }
                pos += toRead
                return toRead
            }
        }

        val ok = FontRegistry.streamValidatedFontToFile(lazyStream, destFile)
        assertTrue("Valid large TTF stream should succeed", ok)
        assertTrue(destFile.exists())
        assertEquals(totalSize.toLong(), destFile.length())
        assertEquals(expectedDigest, CryptoUtils.sha256Hex(destFile))
        assertFalse("Temp font file should be cleaned up", File(dir, "${destFile.name}.tmp").exists())
    }

    @Test
    fun fontRegistry_streamValidatedFontToFile_rejectsInvalidHeaderAndOversizedStream() {
        val dir = tempFolder.newFolder("fonts_reject")

        // 1. Invalid header rejected without writing destFile
        val invalidFile = File(dir, "bad.ttf")
        val badHeaderBytes = "NOT_A_FONT_HEADER_PAYLOAD".toByteArray(Charsets.UTF_8)
        assertFalse(FontRegistry.streamValidatedFontToFile(ByteArrayInputStream(badHeaderBytes), invalidFile))
        assertFalse(invalidFile.exists())
        assertFalse(File(dir, "${invalidFile.name}.tmp").exists())

        // 2. Oversized font aborted and .tmp removed
        val oversizedFile = File(dir, "huge.otf")
        val otfHeader = "OTTO".toByteArray(Charsets.US_ASCII) + ByteArray(8)
        val oversizedPayload = otfHeader + ByteArray(2048)
        assertFalse(
            FontRegistry.streamValidatedFontToFile(
                ByteArrayInputStream(oversizedPayload),
                oversizedFile,
                maxBytes = 1024L
            )
        )
        assertFalse(oversizedFile.exists())
        assertFalse(File(dir, "${oversizedFile.name}.tmp").exists())
    }

    @Test
    fun cryptoUtils_sha256HexFile_matchesByteArrayDigestAcrossMultipleChunks() {
        val file = tempFolder.newFile("chunked.bin")
        val data = ByteArray(200 * 1024) { (it * 31).toByte() } // > 3 chunks at 64 KB
        file.writeBytes(data)

        assertEquals(CryptoUtils.sha256Hex(data), CryptoUtils.sha256Hex(file))
    }

    @Test
    fun diaryRepository_streamingQuarantineCopy_deduplicatesAndCleansTmpFiles() {
        val vaultDir = tempFolder.newFolder("vault_q")
        val repo = DiaryRepository(VaultId.PRIMARY, vaultDir)
        val dek = CryptoUtils.randomBytes(CryptoUtils.AES_KEY_SIZE_BYTES)
        repo.ensureDirs()

        // Valid diary so isDekValidForVault confirms DEK is valid
        repo.saveDiary(DiaryRecord(id = "healthy_1", heading = "Healthy", createdAt = 1L, updatedAt = 1L), dek)

        // Corrupt large-ish .lkd file (> 128 KB so multiple 64 KB stream chunks are exercised)
        val corruptFile = File(repo.diariesDir, "corrupt_stream_1.lkd")
        FileOutputStream(corruptFile).use { fos ->
            val junk = ByteArray(150 * 1024) { (it % 199).toByte() }
            fos.write(junk)
        }
        // Dangling .tmp inside quarantine should also be cleaned by scanAndRecover
        val danglingQTmp = File(repo.quarantineDir, "corrupt_stream_1_stale.lkd.tmp").apply { writeText("stale") }

        val report = repo.scanAndRecover(dek, dekVerified = true)
        assertFalse("Dangling quarantine .tmp must be cleaned", danglingQTmp.exists())
        assertEquals(1, report.quarantinedFiles.size)

        // Loading the same corrupt diary again must reuse the existing quarantine file (streamed SHA-256 dedupe)
        val firstCount = repo.quarantineDir.listFiles { f -> f.name.endsWith(".lkd") }?.size ?: 0
        assertEquals(1, firstCount)

        var caught: CorruptedDiaryException? = null
        try {
            repo.loadDiary("corrupt_stream_1", dek, dekVerified = true)
        } catch (e: CorruptedDiaryException) {
            caught = e
        }
        assertTrue(caught != null)
        val secondCount = repo.quarantineDir.listFiles { f -> f.name.endsWith(".lkd") }?.size ?: 0
        assertEquals("Identical corrupt file must not duplicate in quarantine/", 1, secondCount)
        assertEquals(CryptoUtils.sha256Hex(corruptFile), CryptoUtils.sha256Hex(File(caught!!.quarantinedPath!!)))
        assertEquals(0, repo.quarantineDir.listFiles { f -> f.name.endsWith(".tmp") }?.size ?: 0)
    }
}
