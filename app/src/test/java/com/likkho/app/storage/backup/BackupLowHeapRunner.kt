package com.likkho.app.storage.backup

import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.DiaryRepository
import com.likkho.app.storage.EncryptedMediaRepository
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.model.CanvasImageRecord
import com.likkho.app.storage.model.DiaryRecord
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Standalone entry point executed under a strict -Xmx64m JVM heap limit by [BackupEngineTest]
 * to prove backup, inspect, restore, and .likkho share stream in constant small RAM on a 200+ MB vault.
 */
object BackupLowHeapRunner {

    private class DeterministicPatternInputStream(
        private val totalBytes: Long,
        private val seed: Byte
    ) : InputStream() {
        private var bytesSent = 0L
        private val digest = MessageDigest.getInstance("SHA-256")
        private val patternChunk = ByteArray(64 * 1024) { idx ->
            ((idx * 131 + seed.toInt()) and 0xFF).toByte()
        }

        val sha256Hex: String
            get() = CryptoUtils.toHex(digest.digest())

        override fun read(): Int {
            val single = ByteArray(1)
            val r = read(single, 0, 1)
            return if (r == -1) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesSent >= totalBytes) return -1
            val remaining = totalBytes - bytesSent
            val toCopy = minOf(len.toLong(), remaining, patternChunk.size.toLong()).toInt()
            val chunkOffset = (bytesSent % patternChunk.size).toInt()
            val firstPart = minOf(toCopy, patternChunk.size - chunkOffset)
            System.arraycopy(patternChunk, chunkOffset, b, off, firstPart)
            if (firstPart < toCopy) {
                System.arraycopy(patternChunk, 0, b, off + firstPart, toCopy - firstPart)
            }
            digest.update(b, off, toCopy)
            bytesSent += toCopy
            return toCopy
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Work dir path required" }
        val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0)
        require(maxHeapMb <= 72.0) {
            "Expected -Xmx64m heap limit, got ${"%.1f".format(maxHeapMb)} MB"
        }

        val workRoot = File(args[0])
        val srcVaultDir = File(workRoot, "src_vault").apply { mkdirs() }
        val dstVaultDir = File(workRoot, "dst_vault").apply { mkdirs() }
        val tempDir = File(workRoot, "temp").apply { mkdirs() }
        val backupFile = File(workRoot, "vault_200mb.lkb")
        val shareFile = File(workRoot, "large_entry.likkho")

        val srcDek = CryptoUtils.secureRandomBytes(32)
        val dstDek = CryptoUtils.secureRandomBytes(32)
        val passphrase = "LowHeapStreamingPassword#2026".toCharArray()
        val iterations = 1_000

        val srcDiaryRepo = DiaryRepository(VaultId.PRIMARY, srcVaultDir).apply { ensureDirs() }
        val srcMediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, srcVaultDir).apply { ensureDirs() }
        val backupEngine = BackupEngine(tempDir = tempDir, defaultIterations = iterations)

        // 3 media items x 70 MB = 210 MB total plaintext payload (> 200 MB under 64 MB JVM heap)
        val mediaSizeBytes = 70L * 1024L * 1024L
        val expectedHashes = mutableMapOf<String, String>()

        for (i in 1..3) {
            val mediaId = "large_media_$i"
            val stream = DeterministicPatternInputStream(mediaSizeBytes, (i * 17).toByte())
            srcMediaRepo.importMedia(mediaId, stream, srcDek, estimatedSizeBytes = mediaSizeBytes)
            expectedHashes[mediaId] = stream.sha256Hex
        }

        val diary1 = DiaryRecord(
            id = "diary_heavy_1",
            heading = "Heavy Entry 1",
            images = listOf(
                CanvasImageRecord(mediaId = "large_media_1"),
                CanvasImageRecord(mediaId = "large_media_2")
            ),
            plainPreview = "140 MB media entry"
        )
        val diary2 = DiaryRecord(
            id = "diary_heavy_2",
            heading = "Heavy Entry 2",
            images = listOf(CanvasImageRecord(mediaId = "large_media_3")),
            plainPreview = "70 MB media entry"
        )
        srcDiaryRepo.saveDiary(diary1, srcDek)
        srcDiaryRepo.saveDiary(diary2, srcDek)

        // 1. Streaming backup of 210 MB vault
        val backupResult = backupEngine.createBackup(
            targetFile = backupFile,
            passphrase = passphrase,
            diaryRepo = srcDiaryRepo,
            mediaRepo = srcMediaRepo,
            vaultDek = srcDek,
            iterations = iterations
        )
        require(backupResult.success) { "Backup failed" }
        require(backupResult.diaryCount == 2) { "Expected 2 diaries" }
        require(backupResult.mediaCount == 3) { "Expected 3 media files" }
        require(backupFile.length() >= 210L * 1024L * 1024L) { "Backup file smaller than 210 MB" }

        // 2. Streaming inspect (manifest + diaries only, skips 210 MB media)
        val contents = backupEngine.readBackupContents(backupFile, passphrase)
        require(contents.diaries.size == 2) { "Inspect diary count mismatch" }
        require(contents.mediaIds.size == 3) { "Inspect media count mismatch" }

        // 3. Streaming restore into fresh vault via disk staging
        val dstDiaryRepo = DiaryRepository(VaultId.PRIMARY, dstVaultDir).apply { ensureDirs() }
        val dstMediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, dstVaultDir).apply { ensureDirs() }

        val restoreResult = backupEngine.restoreBackup(
            backupFile = backupFile,
            passphrase = passphrase,
            diaryRepo = dstDiaryRepo,
            mediaRepo = dstMediaRepo,
            vaultDek = dstDek
        )
        require(restoreResult.restoredDiaries == 2) { "Restore diary count mismatch" }
        require(restoreResult.importedMedia == 3) { "Restore media count mismatch" }

        // 4. Verify streaming SHA-256 of all 210 MB restored media
        for ((mediaId, expectedHash) in expectedHashes) {
            val actualHash = dstMediaRepo.openDecryptedStream(mediaId, dstDek).use { CryptoUtils.sha256Hex(it) }
            require(actualHash == expectedHash) { "Restored SHA-256 mismatch for $mediaId" }
        }

        // Delete 210 MB backup before testing .likkho share to keep temp disk footprint small
        backupFile.delete()
        val stagingLeftovers = tempDir.listFiles() ?: emptyArray()
        require(stagingLeftovers.isEmpty()) { "Staging temp files leaked: ${stagingLeftovers.map { it.name }}" }

        // 5. Streaming .likkho single-diary share with 140 MB media
        backupEngine.createShareDiaryFile(
            targetFile = shareFile,
            diaryId = "diary_heavy_1",
            passphrase = passphrase,
            diaryRepo = srcDiaryRepo,
            mediaRepo = srcMediaRepo,
            vaultDek = srcDek,
            iterations = iterations
        )
        val shareTargetVault = File(workRoot, "share_dst_vault").apply { mkdirs() }
        val shareDiaryRepo = DiaryRepository(VaultId.PRIMARY, shareTargetVault).apply { ensureDirs() }
        val shareMediaRepo = EncryptedMediaRepository(VaultId.PRIMARY, shareTargetVault).apply { ensureDirs() }
        val shareDek = CryptoUtils.secureRandomBytes(32)

        val shareRestore = backupEngine.restoreShareDiaryFile(
            shareFile = shareFile,
            passphrase = passphrase,
            diaryRepo = shareDiaryRepo,
            mediaRepo = shareMediaRepo,
            vaultDek = shareDek
        )
        require(shareRestore.restoredDiaries == 1) { "Share restore diary mismatch" }
        require(shareRestore.importedMedia == 2) { "Share restore media mismatch" }

        val h1 = shareMediaRepo.openDecryptedStream("large_media_1", shareDek).use { CryptoUtils.sha256Hex(it) }
        require(h1 == expectedHashes["large_media_1"]) { "Shared media SHA-256 mismatch" }
        require((tempDir.listFiles() ?: emptyArray()).isEmpty()) { "Temp dir not cleaned after share restore" }

        println("LOW_HEAP_OK maxHeapMB=${"%.1f".format(maxHeapMb)}")
    }
}
