package com.likkho.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.VaultId
import com.likkho.app.storage.VaultManager
import com.likkho.app.storage.model.BackgroundRecord
import com.likkho.app.storage.model.CanvasAudioRecord
import com.likkho.app.storage.model.CanvasImageRecord
import com.likkho.app.storage.model.CanvasVideoRecord
import com.likkho.app.storage.model.DiaryLockRecord
import com.likkho.app.storage.model.DiaryRecord
import com.likkho.app.storage.model.PfpRecord
import com.likkho.app.storage.model.SavedPasswordRecord
import com.likkho.app.storage.model.WordAnalysisStorageRecord
import com.likkho.app.storage.model.WordDefinitionStorageRecord
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * RealFeatureHooks connects UI actions to real local cryptographic storage per STORAGE_SPEC Section 11.
 * - Granular diary operations (saveDiary, deleteDiary, loadAll)
 * - Encrypted password storage via PasswordRepository
 * - Encrypted word analysis storage via WordAnalysisRepository
 * - Encrypted media import via EncryptedMediaRepository (Tink streaming AEAD)
 * - Backup (.lkb) and share (.likkho) creation and restore via BackupEngine
 * - Crypto-shredding on clearAllData
 */
class RealFeatureHooks(
    private val context: Context? = null,
    val vaultManager: VaultManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
    val baseStorageDir: File = context?.filesDir ?: File(vaultManager.baseDir, "app_files"),
    val cacheStorageDir: File = context?.cacheDir ?: File(vaultManager.baseDir, "app_cache")
) : FeatureHooks {

    private val writeMutex = Mutex()
    val backupRootDir = File(baseStorageDir, "backups").apply { mkdirs() }
    val shareCacheDir = File(cacheStorageDir, "shares")
    val exportCacheDir = File(cacheStorageDir, "exports")
    private val legacyShareDir = File(baseStorageDir, "shares")
    private val legacyExportDir = File(baseStorageDir, "exports")

    fun backupDirFor(vaultId: VaultId): File = File(backupRootDir, vaultId.backupDirName)

    private fun activeBackupDir(): File {
        val vaultId = vaultManager.currentVault ?: VaultId.PRIMARY
        return backupDirFor(vaultId)
    }

    fun listLegacySharedBackupFiles(): List<File> {
        if (!backupRootDir.exists()) return emptyList()
        return (backupRootDir.listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
    }

    @Volatile
    private var lastUsedPassphrase: String? = null

    // =========================================================================
    //  App Update & External Links
    // =========================================================================

    override suspend fun checkForUpdate(manual: Boolean): UpdateCheck = withContext(dispatcher) {
        UpdateCheck.UpToDate("1.5.0")
    }

    override fun downloadApk(info: AppUpdateInfo) {
        info.apkDownloadUrl?.let { openUrl(it) }
    }

    override fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context?.startActivity(intent)
        }
    }

    // =========================================================================
    //  Home: 3-dot Menu (Share & Export)
    // =========================================================================

    private fun clearDirectoryContents(dir: File): Long {
        if (!dir.exists()) return 0L
        var freed = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            freed += com.likkho.app.storage.StorageMetrics.directorySizeBytes(child)
            child.deleteRecursively()
        }
        return freed
    }

    fun cleanupExportAndShareCaches(): Long {
        var freed = 0L
        freed += clearDirectoryContents(exportCacheDir)
        freed += clearDirectoryContents(shareCacheDir)
        freed += clearDirectoryContents(legacyExportDir)
        freed += clearDirectoryContents(legacyShareDir)
        if (legacyExportDir.exists() && (legacyExportDir.listFiles()?.isEmpty() == true)) {
            legacyExportDir.delete()
        }
        if (legacyShareDir.exists() && (legacyShareDir.listFiles()?.isEmpty() == true)) {
            legacyShareDir.delete()
        }
        return freed
    }

    @Volatile
    private var startupOrphanCleanupDone = false

    private fun measureManagedStorageBytes(): Long {
        val basePath = runCatching { baseStorageDir.canonicalPath }.getOrDefault(baseStorageDir.absolutePath)
        val vaultPath = runCatching { vaultManager.baseDir.canonicalPath }.getOrDefault(vaultManager.baseDir.absolutePath)
        val exportPath = runCatching { exportCacheDir.canonicalPath }.getOrDefault(exportCacheDir.absolutePath)
        val sharePath = runCatching { shareCacheDir.canonicalPath }.getOrDefault(shareCacheDir.absolutePath)

        var total = com.likkho.app.storage.StorageMetrics.directorySizeBytes(baseStorageDir)
        if (!vaultPath.startsWith(basePath)) {
            total += com.likkho.app.storage.StorageMetrics.directorySizeBytes(vaultManager.baseDir)
        }
        if (!exportPath.startsWith(basePath) && !exportPath.startsWith(vaultPath)) {
            total += com.likkho.app.storage.StorageMetrics.directorySizeBytes(exportCacheDir)
        }
        if (!sharePath.startsWith(basePath) && !sharePath.startsWith(vaultPath)) {
            total += com.likkho.app.storage.StorageMetrics.directorySizeBytes(shareCacheDir)
        }
        return total
    }

    override suspend fun runStartupStorageCleanup(): Long = withContext(dispatcher) {
        var freed = cleanupExportAndShareCaches()
        freed += vaultManager.cleanupStaleTempDirs()
        if (!startupOrphanCleanupDone && vaultManager.isUnlocked) {
            startupOrphanCleanupDone = true
            runCatching {
                vaultManager.withActiveDek { dek ->
                    val countBefore = measureManagedStorageBytes()
                    vaultManager.cleanupActiveVaultOrphans(dek)
                    val countAfter = measureManagedStorageBytes()
                    freed += (countBefore - countAfter).coerceAtLeast(0L)
                }
            }
        }
        freed
    }

    override suspend fun freeUpSpace(): Long {
        val preview = previewFreeUpSpace()
        return executeFreeUpSpace(preview)
    }

    private fun canonicalPathKey(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun collectFilesRecursively(dir: File, out: MutableList<File>) {
        if (!dir.exists()) return
        if (dir.isFile) {
            out.add(dir)
            return
        }
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isFile) {
                out.add(child)
            } else if (child.isDirectory) {
                collectFilesRecursively(child, out)
            }
        }
    }

    private fun pruneEmptySubdirectories(root: File) {
        if (!root.exists() || !root.isDirectory) return
        val children = root.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                pruneEmptySubdirectories(child)
                if (child.listFiles()?.isEmpty() == true) {
                    child.delete()
                }
            }
        }
    }

    private fun collectTemporaryPreviewItems(vaultId: VaultId?): List<FreeUpPreviewItem> {
        val items = mutableListOf<FreeUpPreviewItem>()
        val seenPaths = HashSet<String>()

        fun addTempFile(file: File, label: String) {
            if (!file.exists() || !file.isFile) return
            val key = canonicalPathKey(file)
            if (!seenPaths.add(key)) return
            items.add(
                FreeUpPreviewItem(
                    id = key,
                    name = file.name,
                    categoryLabel = label,
                    type = FreeUpItemType.TEMPORARY_FILE,
                    sizeBytes = file.length(),
                    modifiedAtMs = file.lastModified(),
                    filePath = file.absolutePath
                )
            )
        }

        val cacheRoots = listOf(
            exportCacheDir to "Export cache",
            shareCacheDir to "Share cache",
            legacyExportDir to "Legacy export",
            legacyShareDir to "Legacy share"
        )
        for ((dir, label) in cacheRoots) {
            val files = mutableListOf<File>()
            collectFilesRecursively(dir, files)
            for (f in files) addTempFile(f, label)
        }

        if (!vaultManager.backupEngine.isOperationActive()) {
            val tmpFiles = mutableListOf<File>()
            collectFilesRecursively(vaultManager.backupEngine.tempDir, tmpFiles)
            for (f in tmpFiles) addTempFile(f, "Temp stage")
        }

        if (vaultId != null) {
            val bDir = backupDirFor(vaultId)
            if (bDir.exists() && !vaultManager.backupEngine.isOperationActive()) {
                (bDir.listFiles { f ->
                    f.isFile && (f.name.endsWith(".tmp") || f.name.endsWith(".bak"))
                } ?: emptyArray()).forEach { addTempFile(it, "Incomplete backup") }
            }

            val mediaRepo = vaultManager.getMediaRepository(vaultId)
            if (mediaRepo.mediaDir.exists()) {
                (mediaRepo.mediaDir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") } ?: emptyArray())
                    .forEach { tmp ->
                        val tmpId = tmp.name
                            .removeSuffix(".${com.likkho.app.storage.EncryptedMediaRepository.MEDIA_EXTENSION}.tmp")
                            .removeSuffix(".tmp")
                        if (!mediaRepo.isImportActive(tmpId)) {
                            addTempFile(tmp, "Incomplete import")
                        }
                    }
            }

            val diaryRepo = vaultManager.getDiaryRepository(vaultId)
            if (diaryRepo.diariesDir.exists()) {
                (diaryRepo.diariesDir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") } ?: emptyArray())
                    .forEach { addTempFile(it, "Temp diary") }
            }
            if (diaryRepo.quarantineDir.exists()) {
                (diaryRepo.quarantineDir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") } ?: emptyArray())
                    .forEach { addTempFile(it, "Temp file") }
            }
        }

        return items.sortedByDescending { it.modifiedAtMs }
    }

    private fun buildFreeUpPreview(
        items: List<FreeUpPreviewItem>,
        keptBackupName: String?
    ): FreeUpPreview {
        val oldBackups = items.filter { it.type == FreeUpItemType.OLD_BACKUP }
        val unusedMedia = items.filter { it.type == FreeUpItemType.UNUSED_MEDIA }
        val tempFiles = items.filter { it.type == FreeUpItemType.TEMPORARY_FILE }

        val categories = buildList {
            if (oldBackups.isNotEmpty()) {
                add(
                    FreeUpCategorySummary(
                        type = FreeUpItemType.OLD_BACKUP,
                        title = "Old backups",
                        count = oldBackups.size,
                        bytes = oldBackups.sumOf { it.sizeBytes },
                        items = oldBackups
                    )
                )
            }
            if (unusedMedia.isNotEmpty()) {
                add(
                    FreeUpCategorySummary(
                        type = FreeUpItemType.UNUSED_MEDIA,
                        title = "Unused media",
                        count = unusedMedia.size,
                        bytes = unusedMedia.sumOf { it.sizeBytes },
                        items = unusedMedia
                    )
                )
            }
            if (tempFiles.isNotEmpty()) {
                add(
                    FreeUpCategorySummary(
                        type = FreeUpItemType.TEMPORARY_FILE,
                        title = "Temporary files",
                        count = tempFiles.size,
                        bytes = tempFiles.sumOf { it.sizeBytes },
                        items = tempFiles
                    )
                )
            }
        }

        return FreeUpPreview(
            items = items,
            categories = categories,
            deletedBackupNames = oldBackups.map { it.name },
            keptBackupName = keptBackupName,
            unusedMediaCount = unusedMedia.size,
            unusedMediaBytes = unusedMedia.sumOf { it.sizeBytes },
            temporaryFilesCount = tempFiles.size,
            temporaryFilesBytes = tempFiles.sumOf { it.sizeBytes },
            totalBytes = items.sumOf { it.sizeBytes }
        )
    }

    private data class EligibleFreeUpSnapshot(
        val eligibleItems: List<FreeUpPreviewItem>,
        val keptBackupName: String?,
        val keptBackupPath: String?,
        val referencedIds: Set<String>,
        val protectedIds: Set<String>,
        val hasUndecodableOrQuarantined: Boolean,
        val orphanCandidateIds: Set<String>
    )

    private fun collectEligibleFreeUpSnapshot(vaultId: VaultId?, dek: ByteArray?): EligibleFreeUpSnapshot {
        val eligibleItems = mutableListOf<FreeUpPreviewItem>()
        var keptBackupName: String? = null
        var keptBackupPath: String? = null
        var referencedIds: Set<String> = emptySet()
        var protectedIds: Set<String> = emptySet()
        var hasUndecodable = false
        var candidateIds: Set<String> = emptySet()

        if (vaultId != null && dek != null) {
            val vBackupDir = backupDirFor(vaultId)
            val lkbFiles = if (vBackupDir.exists()) {
                (vBackupDir.listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray())
                    .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            } else {
                emptyList()
            }
            val latest = lkbFiles.firstOrNull()
            keptBackupName = latest?.name
            keptBackupPath = latest?.let { canonicalPathKey(it) }
            if (!vaultManager.backupEngine.isOperationActive()) {
                for (oldFile in lkbFiles.drop(1)) {
                    eligibleItems.add(
                        FreeUpPreviewItem(
                            id = oldFile.name,
                            name = oldFile.name,
                            categoryLabel = "Old backup",
                            type = FreeUpItemType.OLD_BACKUP,
                            sizeBytes = oldFile.length(),
                            modifiedAtMs = oldFile.lastModified(),
                            filePath = oldFile.absolutePath
                        )
                    )
                }
            }

            val diaryRepo = vaultManager.getDiaryRepository(vaultId)
            val mediaRepo = vaultManager.getMediaRepository(vaultId)
            val scan = diaryRepo.scanReferencedMediaIds(dek)
            referencedIds = scan.referencedIds
            hasUndecodable = scan.hasUndecodableOrQuarantined
            protectedIds = vaultManager.currentSessionProtectedMediaIds()
            candidateIds = mediaRepo.loadOrphanCandidates().keys

            val mediaFiles = if (mediaRepo.mediaDir.exists()) {
                (mediaRepo.mediaDir.listFiles { f ->
                    f.isFile && f.name.endsWith(".${com.likkho.app.storage.EncryptedMediaRepository.MEDIA_EXTENSION}")
                } ?: emptyArray()).sortedByDescending { it.lastModified() }
            } else {
                emptyList()
            }

            for (mFile in mediaFiles) {
                val mId = mFile.name.removeSuffix(".${com.likkho.app.storage.EncryptedMediaRepository.MEDIA_EXTENSION}")
                val isEligible = !referencedIds.contains(mId) &&
                    !protectedIds.contains(mId) &&
                    !mediaRepo.isImportActive(mId) &&
                    (!hasUndecodable || candidateIds.contains(mId))
                if (isEligible) {
                    eligibleItems.add(
                        FreeUpPreviewItem(
                            id = mId,
                            name = "Unused media (${mId.take(10)})",
                            categoryLabel = "Unused media",
                            type = FreeUpItemType.UNUSED_MEDIA,
                            sizeBytes = mFile.length(),
                            modifiedAtMs = mFile.lastModified(),
                            filePath = mFile.absolutePath
                        )
                    )
                }
            }
        }

        eligibleItems.addAll(collectTemporaryPreviewItems(vaultId))
        return EligibleFreeUpSnapshot(
            eligibleItems = eligibleItems,
            keptBackupName = keptBackupName,
            keptBackupPath = keptBackupPath,
            referencedIds = referencedIds,
            protectedIds = protectedIds,
            hasUndecodableOrQuarantined = hasUndecodable,
            orphanCandidateIds = candidateIds
        )
    }

    private fun runFreeUpPlan(preview: FreeUpPreview?, dryRun: Boolean): Pair<FreeUpPreview, Long> {
        val vaultId = vaultManager.currentVault
        val snapshot = if (vaultId != null && vaultManager.isUnlocked) {
            vaultManager.withActiveDek { dek ->
                collectEligibleFreeUpSnapshot(vaultId, dek)
            }
        } else {
            collectEligibleFreeUpSnapshot(null, null)
        }

        if (dryRun) {
            return buildFreeUpPreview(snapshot.eligibleItems, snapshot.keptBackupName) to 0L
        }

        val targetPreview = preview ?: buildFreeUpPreview(snapshot.eligibleItems, snapshot.keptBackupName)
        val eligibleByPath = snapshot.eligibleItems.associateBy { canonicalPathKey(File(it.filePath)) }
        val mediaRepo = vaultId?.let { vaultManager.getMediaRepository(it) }
        var freedBytes = 0L

        for (item in targetPreview.items) {
            val file = File(item.filePath)
            if (!file.exists() || !file.isFile) continue
            val pathKey = canonicalPathKey(file)
            if (!eligibleByPath.containsKey(pathKey)) continue

            when (item.type) {
                FreeUpItemType.OLD_BACKUP -> {
                    if (vaultManager.backupEngine.isOperationActive()) continue
                    if (pathKey == snapshot.keptBackupPath) continue
                    val len = file.length()
                    if (file.delete()) {
                        freedBytes += len
                    }
                }
                FreeUpItemType.UNUSED_MEDIA -> {
                    val repo = mediaRepo ?: continue
                    val liveProtected = vaultManager.currentSessionProtectedMediaIds()
                    val stillEligible = !snapshot.referencedIds.contains(item.id) &&
                        !liveProtected.contains(item.id) &&
                        !repo.isImportActive(item.id) &&
                        (!snapshot.hasUndecodableOrQuarantined || snapshot.orphanCandidateIds.contains(item.id))
                    if (!stillEligible) continue
                    val len = file.length()
                    if (repo.deleteMedia(item.id)) {
                        freedBytes += len
                    }
                }
                FreeUpItemType.TEMPORARY_FILE -> {
                    if (pathKey.startsWith(canonicalPathKey(vaultManager.backupEngine.tempDir)) &&
                        vaultManager.backupEngine.isOperationActive()
                    ) {
                        continue
                    }
                    val len = file.length()
                    if (file.delete()) {
                        freedBytes += len
                    }
                }
            }
        }

        // Prune now-empty temporary directories without touching active vault media/backups
        pruneEmptySubdirectories(exportCacheDir)
        pruneEmptySubdirectories(shareCacheDir)
        pruneEmptySubdirectories(legacyExportDir)
        if (legacyExportDir.exists() && legacyExportDir.listFiles()?.isEmpty() == true) legacyExportDir.delete()
        pruneEmptySubdirectories(legacyShareDir)
        if (legacyShareDir.exists() && legacyShareDir.listFiles()?.isEmpty() == true) legacyShareDir.delete()
        if (!vaultManager.backupEngine.isOperationActive()) {
            pruneEmptySubdirectories(vaultManager.backupEngine.tempDir)
        }

        return targetPreview to freedBytes
    }

    override suspend fun previewFreeUpSpace(): FreeUpPreview = withContext(dispatcher) {
        writeMutex.withLock {
            runFreeUpPlan(preview = null, dryRun = true).first
        }
    }

    override suspend fun executeFreeUpSpace(preview: FreeUpPreview): Long = withContext(dispatcher) {
        writeMutex.withLock {
            runFreeUpPlan(preview = preview, dryRun = false).second
        }
    }

    override suspend fun deleteDamagedCopies(): Long = withContext(dispatcher) {
        writeMutex.withLock {
            val vaultId = vaultManager.currentVault ?: return@withLock 0L
            if (!vaultManager.isUnlocked) return@withLock 0L
            val qDir = vaultManager.getDiaryRepository(vaultId).quarantineDir
            if (!qDir.exists()) return@withLock 0L
            var freed = 0L
            val children = qDir.listFiles() ?: return@withLock 0L
            for (child in children) {
                val sz = com.likkho.app.storage.StorageMetrics.directorySizeBytes(child)
                if (child.deleteRecursively()) {
                    freed += sz
                }
            }
            freed
        }
    }

    override suspend fun deleteOlderSharedBackups(fileNames: List<String>): Long = withContext(dispatcher) {
        if (fileNames.isEmpty()) return@withContext 0L
        writeMutex.withLock {
            if (!backupRootDir.exists()) return@withLock 0L
            val allowedNames = fileNames.toSet()
            var freed = 0L
            val rootFiles = backupRootDir.listFiles { f ->
                f.isFile && f.name.endsWith(".lkb") && allowedNames.contains(f.name)
            } ?: return@withLock 0L
            for (f in rootFiles) {
                val len = f.length()
                if (f.delete()) {
                    freed += len
                }
            }
            freed
        }
    }

    override suspend fun getStorageReport(): StorageReport = withContext(dispatcher) {
        val vaultId = vaultManager.currentVault
        if (vaultId == null || !vaultManager.isUnlocked) {
            return@withContext StorageReport()
        }

        vaultManager.withActiveDek { dek ->
            val diaryRepo = vaultManager.activeDiaryRepository()
            val mediaRepo = vaultManager.activeMediaRepository()
            val scan = diaryRepo.scanReferencedMediaIds(dek)
            val protectedIds = vaultManager.currentSessionProtectedMediaIds()
            val candidateIds = mediaRepo.loadOrphanCandidates().keys

            val diaryItems = mutableListOf<StorageItemInfo>()
            val photoItems = mutableListOf<StorageItemInfo>()
            val audioItems = mutableListOf<StorageItemInfo>()
            val videoItems = mutableListOf<StorageItemInfo>()
            val seenMediaIds = HashSet<String>()
            var unreadableCount = 0

            val lkdFiles = if (diaryRepo.diariesDir.exists()) {
                (diaryRepo.diariesDir.listFiles { f -> f.isFile && f.name.endsWith(".lkd") } ?: emptyArray())
                    .sortedByDescending { it.lastModified() }
            } else {
                emptyList()
            }

            for (lkdFile in lkdFiles) {
                val diaryId = lkdFile.name.removeSuffix(".lkd")
                val decodedRecord = runCatching {
                    com.likkho.app.storage.DiaryFileCodec.decode(lkdFile.readBytes(), dek, diaryId).record
                }.getOrNull()

                if (decodedRecord == null) {
                    unreadableCount++
                    diaryItems.add(
                        StorageItemInfo(
                            id = diaryId,
                            name = "Unreadable Diary",
                            category = StorageCategory.DIARIES,
                            sizeBytes = lkdFile.length(),
                            modifiedAtMs = lkdFile.lastModified()
                        )
                    )
                    continue
                }

                val diaryTs = decodedRecord.updatedAt.takeIf { it > 0L } ?: lkdFile.lastModified()
                diaryItems.add(
                    StorageItemInfo(
                        id = decodedRecord.id,
                        name = decodedRecord.heading.ifBlank { "Untitled Diary" },
                        category = StorageCategory.DIARIES,
                        sizeBytes = lkdFile.length(),
                        modifiedAtMs = diaryTs
                    )
                )

                fun addMedia(mediaId: String?, category: StorageCategory, label: String, itemTs: Long) {
                    if (mediaId.isNullOrBlank() || !seenMediaIds.add(mediaId)) return
                    val mFile = mediaRepo.getMediaFile(mediaId)
                    if (!mFile.exists() || !mFile.isFile) return
                    val item = StorageItemInfo(
                        id = mediaId,
                        name = label,
                        category = category,
                        sizeBytes = mFile.length(),
                        modifiedAtMs = mFile.lastModified().takeIf { it > 0L } ?: itemTs
                    )
                    when (category) {
                        StorageCategory.PHOTOS -> photoItems.add(item)
                        StorageCategory.AUDIO -> audioItems.add(item)
                        StorageCategory.VIDEOS -> videoItems.add(item)
                        else -> Unit
                    }
                }

                for (img in decodedRecord.images) {
                    addMedia(img.mediaId, StorageCategory.PHOTOS, "Photo", diaryTs)
                }
                addMedia(decodedRecord.pfp?.sourceMediaId, StorageCategory.PHOTOS, "Profile Photo", diaryTs)
                addMedia(decodedRecord.background?.mediaId, StorageCategory.PHOTOS, "Background Photo", diaryTs)
                for (aud in decodedRecord.audios) {
                    addMedia(aud.mediaId, StorageCategory.AUDIO, aud.name.ifBlank { "Audio" }, diaryTs)
                }
                for (vid in decodedRecord.videos) {
                    addMedia(vid.mediaId, StorageCategory.VIDEOS, vid.name.ifBlank { "Video" }, diaryTs)
                }
            }

            // Unused media (orphans belonging ONLY to active vault)
            val unusedItems = mutableListOf<StorageItemInfo>()
            val allMediaFiles = if (mediaRepo.mediaDir.exists()) {
                (mediaRepo.mediaDir.listFiles { f ->
                    f.isFile && f.name.endsWith(".${com.likkho.app.storage.EncryptedMediaRepository.MEDIA_EXTENSION}")
                } ?: emptyArray()).sortedByDescending { it.lastModified() }
            } else {
                emptyList()
            }
            for (mFile in allMediaFiles) {
                val mId = mFile.name.removeSuffix(".${com.likkho.app.storage.EncryptedMediaRepository.MEDIA_EXTENSION}")
                if (seenMediaIds.contains(mId) || scan.referencedIds.contains(mId)) continue
                if (protectedIds.contains(mId) || mediaRepo.isImportActive(mId)) continue
                if (scan.hasUndecodableOrQuarantined && !candidateIds.contains(mId)) continue
                unusedItems.add(
                    StorageItemInfo(
                        id = mId,
                        name = "Unused media (${mId.take(10)})",
                        category = StorageCategory.UNUSED_MEDIA,
                        sizeBytes = mFile.length(),
                        modifiedAtMs = mFile.lastModified()
                    )
                )
            }

            // Backups in active vault's folder ONLY
            val vBackupDir = backupDirFor(vaultId)
            val vaultBackups = if (vBackupDir.exists()) {
                (vBackupDir.listFiles { f -> f.isFile && f.name.endsWith(".lkb") } ?: emptyArray())
                    .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
                    .map { f ->
                        StorageItemInfo(
                            id = f.name,
                            name = f.name,
                            category = StorageCategory.BACKUPS,
                            sizeBytes = f.length(),
                            modifiedAtMs = f.lastModified()
                        )
                    }
            } else {
                emptyList()
            }

            // Legacy shared root backups (read-only notice; never auto-deleted)
            val olderBackupItems = listLegacySharedBackupFiles().map { f ->
                StorageItemInfo(
                    id = f.name,
                    name = f.name,
                    category = StorageCategory.OLDER_BACKUPS,
                    sizeBytes = f.length(),
                    modifiedAtMs = f.lastModified()
                )
            }

            // Temporary files (global export/share caches + vaults/tmp + active vault temp)
            val tempItems = collectTemporaryPreviewItems(vaultId).map { t ->
                StorageItemInfo(
                    id = t.id,
                    name = t.name,
                    category = StorageCategory.TEMPORARY,
                    sizeBytes = t.sizeBytes,
                    modifiedAtMs = t.modifiedAtMs,
                    detail = t.categoryLabel
                )
            }

            // Damaged diary copies in active vault quarantine/ ONLY
            val quarantineItems = if (diaryRepo.quarantineDir.exists()) {
                (diaryRepo.quarantineDir.listFiles { f -> f.isFile && f.name.endsWith(".lkd") } ?: emptyArray())
                    .sortedByDescending { it.lastModified() }
                    .map { f ->
                        StorageItemInfo(
                            id = f.name,
                            name = "Damaged copy (${f.name.removeSuffix(".lkd")})",
                            category = StorageCategory.DAMAGED_COPIES,
                            sizeBytes = f.length(),
                            modifiedAtMs = f.lastModified()
                        )
                    }
            } else {
                emptyList()
            }

            StorageReport(
                diaries = StorageSection(
                    category = StorageCategory.DIARIES,
                    title = "Diaries",
                    count = diaryItems.size,
                    bytes = diaryItems.sumOf { it.sizeBytes },
                    latestDateMs = diaryItems.maxOfOrNull { it.modifiedAtMs },
                    items = diaryItems
                ),
                photos = StorageSection(
                    category = StorageCategory.PHOTOS,
                    title = "Photos",
                    count = photoItems.size,
                    bytes = photoItems.sumOf { it.sizeBytes },
                    latestDateMs = photoItems.maxOfOrNull { it.modifiedAtMs },
                    items = photoItems
                ),
                audio = StorageSection(
                    category = StorageCategory.AUDIO,
                    title = "Audio",
                    count = audioItems.size,
                    bytes = audioItems.sumOf { it.sizeBytes },
                    latestDateMs = audioItems.maxOfOrNull { it.modifiedAtMs },
                    items = audioItems
                ),
                videos = StorageSection(
                    category = StorageCategory.VIDEOS,
                    title = "Videos",
                    count = videoItems.size,
                    bytes = videoItems.sumOf { it.sizeBytes },
                    latestDateMs = videoItems.maxOfOrNull { it.modifiedAtMs },
                    items = videoItems
                ),
                backups = StorageSection(
                    category = StorageCategory.BACKUPS,
                    title = "Backups in app storage",
                    count = vaultBackups.size,
                    bytes = vaultBackups.sumOf { it.sizeBytes },
                    latestDateMs = vaultBackups.maxOfOrNull { it.modifiedAtMs },
                    items = vaultBackups
                ),
                olderBackups = StorageSection(
                    category = StorageCategory.OLDER_BACKUPS,
                    title = "Older backups",
                    count = olderBackupItems.size,
                    bytes = olderBackupItems.sumOf { it.sizeBytes },
                    latestDateMs = olderBackupItems.maxOfOrNull { it.modifiedAtMs },
                    items = olderBackupItems
                ),
                temporaryFiles = StorageSection(
                    category = StorageCategory.TEMPORARY,
                    title = "Temporary files",
                    count = tempItems.size,
                    bytes = tempItems.sumOf { it.sizeBytes },
                    latestDateMs = tempItems.maxOfOrNull { it.modifiedAtMs },
                    items = tempItems
                ),
                unusedMedia = StorageSection(
                    category = StorageCategory.UNUSED_MEDIA,
                    title = "Unused media",
                    count = unusedItems.size,
                    bytes = unusedItems.sumOf { it.sizeBytes },
                    latestDateMs = unusedItems.maxOfOrNull { it.modifiedAtMs },
                    items = unusedItems
                ),
                damagedCopies = StorageSection(
                    category = StorageCategory.DAMAGED_COPIES,
                    title = "Damaged diary copies",
                    count = quarantineItems.size,
                    bytes = quarantineItems.sumOf { it.sizeBytes },
                    latestDateMs = quarantineItems.maxOfOrNull { it.modifiedAtMs },
                    items = quarantineItems
                ),
                unreadableDiaryCount = unreadableCount
            )
        }
    }

    private fun shareViaFileProvider(file: File, mimeType: String, chooserTitle: String) {
        val ctx = context ?: return
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, chooserTitle).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
        }
    }

    private fun mimeTypeForExportFormat(format: ExportFormat): String = when (format.ext.lowercase()) {
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    override suspend fun shareDiaryAsArchive(log: DiaryLog, passphrase: String): String =
        withContext(dispatcher) {
            if (!vaultManager.isUnlocked) {
                return@withContext "Cannot open this file"
            }
            if (passphrase.length < 8) {
                throw IllegalArgumentException("Passphrase must be at least 8 characters.")
            }
            clearDirectoryContents(shareCacheDir)
            clearDirectoryContents(legacyShareDir)
            shareCacheDir.mkdirs()
            vaultManager.withActiveDek { dek ->
                val diaryRepo = vaultManager.activeDiaryRepository()
                val mediaRepo = vaultManager.activeMediaRepository()
                val record = if (log.isContentLoaded) {
                    log.toDiaryRecord().also { diaryRepo.saveDiary(it, dek) }
                } else {
                    diaryRepo.loadDiary(log.id, dek).record
                }
                val targetFile = File(shareCacheDir, "${record.id}.likkho")
                vaultManager.backupEngine.createShareDiaryFile(
                    targetFile = targetFile,
                    diaryId = record.id,
                    passphrase = passphrase.toCharArray(),
                    diaryRepo = diaryRepo,
                    mediaRepo = mediaRepo,
                    vaultDek = dek
                )
                shareViaFileProvider(targetFile, "application/octet-stream", "Share \"${log.heading.ifBlank { "Diary" }}\"")
                "Shared \"${log.heading.ifBlank { "Diary" }}\" as encrypted .likkho archive."
            }
        }

    override suspend fun exportDiary(log: DiaryLog, format: ExportFormat): String =
        withContext(dispatcher) {
            clearDirectoryContents(exportCacheDir)
            clearDirectoryContents(legacyExportDir)
            exportCacheDir.mkdirs()
            val fullLog = if (!log.isContentLoaded && vaultManager.isUnlocked) {
                runCatching {
                    vaultManager.withActiveDek { dek ->
                        vaultManager.activeDiaryRepository().loadDiary(log.id, dek).record.toDiaryLog()
                    }
                }.getOrDefault(log)
            } else {
                log
            }
            val safeName = fullLog.heading.filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '_').ifBlank { "diary_${fullLog.id}" }
            val file = File(exportCacheDir, "$safeName.${format.ext}")
            val textContent = buildString {
                appendLine(fullLog.heading)
                appendLine("Created: ${fullLog.dateStamp} ${fullLog.timeStamp}")
                appendLine("Updated: ${fullLog.updatedDateStamp} ${fullLog.updatedTimeStamp}")
                appendLine("----------------------------------------")
                appendLine(if (fullLog.contentHtml.isNotBlank()) fullLog.contentHtml else fullLog.plainPreview)
            }
            file.writeText(textContent, Charsets.UTF_8)
            shareViaFileProvider(file, mimeTypeForExportFormat(format), "Export \"${fullLog.heading.ifBlank { "Diary" }}\"")
            "Exported \"${fullLog.heading.ifBlank { "Diary" }}\" as ${format.label}."
        }

    // =========================================================================
    //  Biometrics & Settings
    // =========================================================================

    override fun authenticateBiometric(forUnlock: Boolean, onResult: (Boolean, String?) -> Unit) {
        if (forUnlock) {
            scope.launch {
                val res = vaultManager.unlockWithBiometric()
                withContext(Dispatchers.Main) {
                    when (res) {
                        is com.likkho.app.storage.UnlockResult.Success -> onResult(true, null)
                        is com.likkho.app.storage.UnlockResult.InCooldown -> onResult(false, "Too many wrong attempts. Wait ${res.remainingSeconds}s.")
                        is com.likkho.app.storage.UnlockResult.Error -> onResult(false, res.message)
                        else -> onResult(false, "Use your PIN to unlock.")
                    }
                }
            }
        } else {
            onResult(true, null)
        }
    }

    override fun cancelBiometricPrompt() {}

    override suspend fun exportAllDataZip(): String = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) {
            return@withContext "Vault is locked."
        }
        clearDirectoryContents(exportCacheDir)
        clearDirectoryContents(legacyExportDir)
        exportCacheDir.mkdirs()
        val zipFile = File(exportCacheDir, "Likkho_Export_${System.currentTimeMillis()}.zip")
        vaultManager.withActiveDek { dek ->
            val diaryRepo = vaultManager.activeDiaryRepository()
            val indexEntries = diaryRepo.listDiaries(dek)
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (entry in indexEntries) {
                    val diary = runCatching { diaryRepo.loadDiary(entry.id, dek).record }.getOrNull() ?: continue
                    val entryName = "diaries/${diary.id}_${diary.heading.filter { it.isLetterOrDigit() }.take(20)}.txt"
                    zos.putNextEntry(ZipEntry(entryName))
                    val content = "${diary.heading}\n\n${diary.contentHtml.ifBlank { diary.plainPreview }}"
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
            }
        }
        shareViaFileProvider(zipFile, "application/zip", "Export All Diaries (ZIP)")
        "All data exported (ZIP)."
    }

    override suspend fun clearAllData() {
        withContext(dispatcher) {
            vaultManager.clearAllData()
            backupRootDir.deleteRecursively(); backupRootDir.mkdirs()
            cleanupExportAndShareCaches()
        }
    }

    // =========================================================================
    //  Saved Passwords (Storage Spec Section 7)
    // =========================================================================

    override fun loadPasswords(vault: VaultMode): List<SavedPassword> = runBlocking(dispatcher) {
        if (!vaultManager.isUnlocked) return@runBlocking emptyList()
        val vaultId = if (vault == VaultMode.PRIMARY) VaultId.PRIMARY else VaultId.DECOY
        vaultManager.withActiveDek { dek ->
            vaultManager.getPasswordRepository(vaultId).loadPasswords(dek).map {
                SavedPassword(it.id, it.name, it.password, it.createdAt)
            }
        }
    }

    override fun persistPasswords(vault: VaultMode, items: List<SavedPassword>) {
        scope.launch(dispatcher) {
            if (!vaultManager.isUnlocked) return@launch
            val vaultId = if (vault == VaultMode.PRIMARY) VaultId.PRIMARY else VaultId.DECOY
            vaultManager.withActiveDek { dek ->
                val records = items.map { SavedPasswordRecord(it.id, it.name, it.password, it.createdAt) }
                vaultManager.getPasswordRepository(vaultId).savePasswords(records, dek)
            }
        }
    }

    // =========================================================================
    //  Saved Word Analyses (Storage Spec Section 7)
    // =========================================================================

    override fun loadSavedWords(vault: VaultMode): List<WordAnalysisRecord> = runBlocking(dispatcher) {
        if (!vaultManager.isUnlocked) return@runBlocking emptyList()
        val vaultId = if (vault == VaultMode.PRIMARY) VaultId.PRIMARY else VaultId.DECOY
        vaultManager.withActiveDek { dek ->
            vaultManager.getWordAnalysisRepository(vaultId).loadWords(dek).map { it.toDomain() }
        }
    }

    override fun deleteSavedWord(word: String, vault: VaultMode): List<WordAnalysisRecord> =
        runBlocking(dispatcher) {
            if (!vaultManager.isUnlocked) return@runBlocking emptyList()
            val vaultId = if (vault == VaultMode.PRIMARY) VaultId.PRIMARY else VaultId.DECOY
            vaultManager.withActiveDek { dek ->
                vaultManager.getWordAnalysisRepository(vaultId).deleteWord(word, dek).map { it.toDomain() }
            }
        }

    // =========================================================================
    //  Backup / Restore (Storage Spec Sections 8 & 9)
    // =========================================================================

    override suspend fun hashPassphrase(passphrase: String): String = withContext(Dispatchers.Default) {
        CryptoUtils.sha256Hex(passphrase.toByteArray(Charsets.UTF_8))
    }

    override suspend fun performBackup(passphrase: String): BackupResult = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext BackupResult(0)
        lastUsedPassphrase = passphrase
        vaultManager.withActiveDek { dek ->
            val diaryRepo = vaultManager.activeDiaryRepository()
            val mediaRepo = vaultManager.activeMediaRepository()
            val vaultBackupDir = activeBackupDir().apply { mkdirs() }
            var targetFile = File(vaultBackupDir, "backup_${System.currentTimeMillis()}.lkb")
            if (targetFile.exists()) {
                targetFile = File(vaultBackupDir, "backup_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(4)}.lkb")
            }
            val res = vaultManager.backupEngine.createBackup(
                targetFile = targetFile,
                passphrase = passphrase.toCharArray(),
                diaryRepo = diaryRepo,
                mediaRepo = mediaRepo,
                vaultDek = dek
            )
            // Delete older backups ONLY in the active vault folder AFTER verification passes.
            vaultManager.backupEngine.pruneOldBackups(vaultBackupDir, keepCount = 1, protectedFile = targetFile)
            BackupResult(failedMediaCount = res.failedMediaCount)
        }
    }

    override suspend fun performBackupToStream(
        passphrase: String,
        openOutput: () -> java.io.OutputStream
    ): BackupResult = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext BackupResult(0)
        lastUsedPassphrase = passphrase
        val stageFile = File(
            vaultManager.backupEngine.tempDir,
            "saf_backup_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(4)}.lkb"
        )
        try {
            val res = vaultManager.withActiveDek { dek ->
                val diaryRepo = vaultManager.activeDiaryRepository()
                val mediaRepo = vaultManager.activeMediaRepository()
                vaultManager.backupEngine.createBackup(
                    targetFile = stageFile,
                    passphrase = passphrase.toCharArray(),
                    diaryRepo = diaryRepo,
                    mediaRepo = mediaRepo,
                    vaultDek = dek
                )
            }
            openOutput().use { os ->
                java.io.FileInputStream(stageFile).use { fis ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        os.write(buffer, 0, read)
                    }
                    os.flush()
                }
            }
            BackupResult(failedMediaCount = res.failedMediaCount)
        } finally {
            stageFile.delete()
            File(stageFile.parentFile, "${stageFile.name}.tmp").delete()
            File(stageFile.parentFile, "${stageFile.name}.bak").delete()
        }
    }

    override suspend fun performRestore(passphrase: String): RestoreResult = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext RestoreResult(emptyList(), 0)
        val backupFile = findLatestBackupFile() ?: throw IncorrectKeyException()
        lastUsedPassphrase = passphrase
        try {
            vaultManager.withActiveDek { dek ->
                val diaryRepo = vaultManager.activeDiaryRepository()
                val mediaRepo = vaultManager.activeMediaRepository()
                val res = vaultManager.backupEngine.restoreBackup(
                    backupFile = backupFile,
                    passphrase = passphrase.toCharArray(),
                    diaryRepo = diaryRepo,
                    mediaRepo = mediaRepo,
                    vaultDek = dek
                )
                val (allLogs, failed) = diaryRepo.loadAllDiariesMapped(dek) { it.toSummaryDiaryLog() }
                RestoreResult(
                    logs = allLogs,
                    skippedMediaCount = res.skippedMediaCount,
                    failedDiaries = failed
                )
            }
        } catch (e: Exception) {
            throw IncorrectKeyException()
        }
    }

    override suspend fun openBackupVault(passphrase: String): List<DiaryLog> = withContext(dispatcher) {
        val backupFile = findLatestBackupFile() ?: throw IncorrectKeyException()
        lastUsedPassphrase = passphrase
        try {
            val contents = vaultManager.backupEngine.readBackupContents(backupFile, passphrase.toCharArray())
            contents.diaries.map { item ->
                DiaryLog(
                    id = item.id,
                    heading = item.heading,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt,
                    plainPreview = item.plainPreview
                )
            }
        } catch (e: Exception) {
            throw IncorrectKeyException()
        }
    }

    override suspend fun deleteFromBackup(log: DiaryLog) {
        withContext(dispatcher) {
            val backupFile = findLatestBackupFile() ?: return@withContext
            val pass = lastUsedPassphrase ?: return@withContext
            runCatching {
                vaultManager.backupEngine.deleteDiaryFromBackup(backupFile, pass.toCharArray(), log.id)
            }
        }
    }

    private fun findLatestBackupFile(): File? {
        val vaultBackupDir = activeBackupDir()
        if (!vaultBackupDir.exists()) return null
        return vaultBackupDir.listFiles { file -> file.isFile && file.extension == "lkb" }?.maxByOrNull { it.lastModified() }
    }

    // =========================================================================
    //  Workspace / Canvas Storage (Sections 3, 4, 5 Granular Ops)
    // =========================================================================

    override fun persistLogs(
        logs: List<DiaryLog>,
        obsoleteMediaIds: Set<String>,
        onResult: (Result<Unit>) -> Unit
    ) {
        val boundVaultId = vaultManager.currentVault
        if (boundVaultId == null || !vaultManager.isUnlocked) {
            onResult(Result.failure(IllegalStateException("Vault is locked. Cannot save diaries.")))
            return
        }
        val snapshot = logs.toList()
        val cleanupSnapshot = obsoleteMediaIds.toSet()
        scope.launch(dispatcher) {
            val result = runCatching {
                writeMutex.withLock {
                    if (!vaultManager.isUnlocked) {
                        throw IllegalStateException("Vault is locked. Save aborted.")
                    }
                    if (vaultManager.currentVault != boundVaultId) {
                        throw IllegalStateException("Active vault changed before save completed.")
                    }
                    vaultManager.withActiveDek { dek ->
                        val repo = vaultManager.getDiaryRepository(boundVaultId)
                        val mediaRepo = vaultManager.getMediaRepository(boundVaultId)

                        val currentIndex = repo.loadIndex(dek)
                        val indexMap = currentIndex.entries.associateBy { it.id }
                        val savedMediaIds = mutableSetOf<String>()
                        for (log in snapshot) {
                            val existing = indexMap[log.id]
                            if (log.isContentLoaded) {
                                savedMediaIds.addAll(
                                    com.likkho.app.storage.DiaryRepository.extractRecordMediaIds(log.toDiaryRecord())
                                )
                                val expectedFlags = (if (log.diaryLockPin != null) 1 else 0) or
                                    (if (log.audioAttachments.isNotEmpty()) 2 else 0) or
                                    (if (log.canvasImages.isNotEmpty()) 4 else 0) or
                                    (if (log.videoAttachments.isNotEmpty()) 8 else 0)
                                val changed = existing == null ||
                                    existing.updatedAt != log.updatedAt ||
                                    existing.pinned != log.pinned ||
                                    existing.flags != expectedFlags ||
                                    existing.heading != log.heading ||
                                    existing.plainPreview != log.plainPreview
                                if (changed) {
                                    repo.saveDiary(log.toDiaryRecord(), dek)
                                }
                            } else {
                                existing?.mediaIds?.let { savedMediaIds.addAll(it) }
                                val lockBit = if (log.diaryLockPin != null) 1 else 0
                                val metaChanged = existing == null ||
                                    existing.updatedAt != log.updatedAt ||
                                    existing.pinned != log.pinned ||
                                    existing.heading != log.heading ||
                                    existing.plainPreview != log.plainPreview ||
                                    (existing.flags and 1) != lockBit
                                if (metaChanged) {
                                    val diskFile = File(repo.diariesDir, "${log.id}.lkd")
                                    val recordToSave = if (diskFile.exists()) {
                                        repo.loadDiary(log.id, dek).record.copy(
                                            heading = log.heading,
                                            updatedAt = log.updatedAt,
                                            pinned = log.pinned,
                                            reminderAt = log.reminderAt,
                                            plainPreview = log.plainPreview
                                        )
                                    } else {
                                        log.toDiaryRecord()
                                    }
                                    repo.saveDiary(recordToSave, dek)
                                }
                            }
                        }

                        if (savedMediaIds.isNotEmpty()) {
                            vaultManager.clearSavedSessionMedia(savedMediaIds, boundVaultId)
                        }

                        if (cleanupSnapshot.isNotEmpty()) {
                            vaultManager.unprotectSessionMedia(cleanupSnapshot)
                            val scan = repo.scanReferencedMediaIds(dek)
                            if (!scan.hasUndecodableOrQuarantined) {
                                val delRes = mediaRepo.deleteUnreferencedMediaForDiaryChecked(cleanupSnapshot, scan.referencedIds)
                                val cleared = cleanupSnapshot - delRes.failedIds.toSet()
                                if (cleared.isNotEmpty()) {
                                    mediaRepo.clearPendingSessionImports(cleared)
                                }
                                if (delRes.failedIds.isNotEmpty()) {
                                    throw java.io.IOException("Failed to delete unused media: ${delRes.failedIds.joinToString()}")
                                }
                            }
                        }
                    }
                }
            }
            onResult(result)
        }
    }

    override fun deleteDiary(id: String, onResult: (Result<Unit>) -> Unit) {
        val boundVaultId = vaultManager.currentVault
        if (boundVaultId == null || !vaultManager.isUnlocked) {
            onResult(Result.failure(IllegalStateException("Vault is locked. Cannot delete diary.")))
            return
        }
        scope.launch(dispatcher) {
            val result = runCatching {
                writeMutex.withLock {
                    if (!vaultManager.isUnlocked) {
                        throw IllegalStateException("Vault is locked. Delete aborted.")
                    }
                    if (vaultManager.currentVault != boundVaultId) {
                        throw IllegalStateException("Active vault changed before delete completed.")
                    }
                    vaultManager.withActiveDek { dek ->
                        val repo = vaultManager.getDiaryRepository(boundVaultId)
                        val mediaRepo = vaultManager.getMediaRepository(boundVaultId)
                        repo.deleteDiary(id, dek, mediaRepo)
                    }
                }
            }
            onResult(result)
        }
    }

    override fun deleteUnreferencedMedia(candidateIds: Set<String>, onResult: (Result<Unit>) -> Unit) {
        if (candidateIds.isEmpty()) {
            onResult(Result.success(Unit))
            return
        }
        val boundVaultId = vaultManager.currentVault
        if (boundVaultId == null || !vaultManager.isUnlocked) {
            onResult(Result.failure(IllegalStateException("Vault is locked. Cannot clean up unused media.")))
            return
        }
        val snapshot = candidateIds.toSet()
        scope.launch(dispatcher) {
            val result = runCatching {
                writeMutex.withLock {
                    if (!vaultManager.isUnlocked) {
                        throw IllegalStateException("Vault is locked. Media cleanup aborted.")
                    }
                    if (vaultManager.currentVault != boundVaultId) {
                        throw IllegalStateException("Active vault changed before media cleanup completed.")
                    }
                    vaultManager.withActiveDek { dek ->
                        val repo = vaultManager.getDiaryRepository(boundVaultId)
                        val mediaRepo = vaultManager.getMediaRepository(boundVaultId)
                        vaultManager.unprotectSessionMedia(snapshot)
                        val scan = repo.scanReferencedMediaIds(dek)
                        if (!scan.hasUndecodableOrQuarantined) {
                            val delRes = mediaRepo.deleteUnreferencedMediaForDiaryChecked(snapshot, scan.referencedIds)
                            val cleared = snapshot - delRes.failedIds.toSet()
                            if (cleared.isNotEmpty()) {
                                mediaRepo.clearPendingSessionImports(cleared)
                            }
                            if (delRes.failedIds.isNotEmpty()) {
                                throw java.io.IOException("Failed to delete unused media: ${delRes.failedIds.joinToString()}")
                            }
                        }
                    }
                }
            }
            onResult(result)
        }
    }

    override fun scheduleReminder(log: DiaryLog, at: Long?, onResult: (Result<Unit>) -> Unit) {
        val boundVaultId = vaultManager.currentVault
        if (boundVaultId == null || !vaultManager.isUnlocked) {
            onResult(Result.failure(IllegalStateException("Vault is locked. Cannot update reminder.")))
            return
        }
        scope.launch(dispatcher) {
            val result = runCatching {
                writeMutex.withLock {
                    if (!vaultManager.isUnlocked) {
                        throw IllegalStateException("Vault is locked. Reminder update aborted.")
                    }
                    if (vaultManager.currentVault != boundVaultId) {
                        throw IllegalStateException("Active vault changed before reminder update completed.")
                    }
                    vaultManager.withActiveDek { dek ->
                        val repo = vaultManager.getDiaryRepository(boundVaultId)
                        val diaryFile = File(repo.diariesDir, "${log.id}.lkd")
                        if (!diaryFile.exists()) {
                            throw FileNotFoundException("Diary ${log.id} does not exist; cannot update reminder.")
                        }
                        val existing = repo.loadDiary(log.id, dek).record
                        val record = existing.copy(reminderAt = at)
                        repo.saveDiary(record, dek)
                    }
                }
            }
            onResult(result)
        }
    }

    override fun cancelReminder(diaryId: String) {
        runCatching {
            val nm = context?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(diaryId.hashCode())
        }
    }

    // =========================================================================
    //  Media Encrypted Import (Section 6)
    // =========================================================================

    override suspend fun pickImage(): CanvasImage? = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext null
        val sample = "Likkho Encrypted Image Payload".toByteArray(Charsets.UTF_8)
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
        vaultManager.recordSessionImportedMedia(mediaId)
        CanvasImage(
            id = mediaId,
            source = mediaId,
            x = 40f,
            y = 160f,
            width = 160f,
            height = 120f,
            sizeBytes = sample.size.toLong()
        )
    }

    override suspend fun pickAudio(): CanvasAudio? = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext null
        val sample = "Likkho Encrypted Audio Payload".toByteArray(Charsets.UTF_8)
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
        vaultManager.recordSessionImportedMedia(mediaId)
        CanvasAudio(
            id = mediaId,
            name = "Audio_${mediaId.take(6)}.wav",
            audioUri = mediaId,
            durationSec = 60,
            y = 220f
        )
    }

    override suspend fun pickPfpImage(): String? = null

    override suspend fun pickBackgroundImage(): String? = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext null
        val sample = "BG_IMAGE".toByteArray(Charsets.UTF_8)
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
        vaultManager.recordSessionImportedMedia(mediaId)
        mediaId
    }

    override suspend fun pickFont(lang: String): CustomFont? = withContext(dispatcher) {
        if (!vaultManager.isUnlocked) return@withContext null
        val sample = "TTF_FONT".toByteArray(Charsets.UTF_8)
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
        vaultManager.recordSessionImportedMedia(mediaId)
        CustomFont(mediaId, if (lang == "hi") "Custom Hindi Font" else "Custom English Font", lang)
    }

    override suspend fun importDocument(): String = withContext(dispatcher) {
        "Document imported to canvas."
    }

    override fun startVoiceRecording() {}

    override fun stopVoiceRecording(): CanvasAudio? = runBlocking(dispatcher) {
        if (!vaultManager.isUnlocked) return@runBlocking null
        val sample = "VOICE_RECORDING".toByteArray(Charsets.UTF_8)
        val mediaId = vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(ByteArrayInputStream(sample), dek)
        }
        vaultManager.recordSessionImportedMedia(mediaId)
        CanvasAudio(
            id = mediaId,
            name = "Voice (${fmtDateTimeShort(System.currentTimeMillis())}).wav",
            audioUri = mediaId,
            durationSec = 15,
            y = 200f
        )
    }

    override suspend fun wikipediaSuggest(query: String, lang: String): List<WikiSuggestion> =
        withContext(Dispatchers.Default) {
            if (query.isBlank()) return@withContext emptyList()
            listOf(
                WikiSuggestion(query.trim().replaceFirstChar { it.uppercase() }, "Wikipedia summary"),
                WikiSuggestion("${query.trim().replaceFirstChar { it.uppercase() }} (context)", "Wikipedia article"),
                WikiSuggestion("History of ${query.trim()}", "Historical background")
            )
        }

    override suspend fun wikipediaInsert(title: String, lang: String, withImages: Boolean): String =
        withContext(Dispatchers.Default) {
            "Inserted \"$title\" from Wikipedia."
        }

    override suspend fun analyzeWord(word: String): WordAnalysisRecord = withContext(dispatcher) {
        val record = WordAnalysisRecord(
            word = word,
            phoneticTranscription = "/${word.lowercase()}/",
            syllableMetrics = "${word.length / 3 + 1} syllables",
            definitions = listOf(
                WordDefinition("noun", "Meaning and analysis for \"$word\".")
            ),
            synonyms = listOf("term", "expression"),
            partOfSpeech = listOf("noun")
        )
        if (vaultManager.isUnlocked) {
            vaultManager.withActiveDek { dek ->
                vaultManager.activeWordAnalysisRepository().saveWord(record.toStorage(), dek)
            }
        }
        record
    }

    // Rich text canvas formatting pass-throughs
    override fun applyFormat(action: FormatAction) {}
    override fun applyTextColor(hex: String) {}
    override fun applyHighlight(hex: String) {}
    override fun applyFontSizePx(px: Int?) {}
    override fun applyFont(fontId: String?) {}
    override fun applyLink(url: String) {}
    override fun applySpoiler(passcode: String) {}
    override fun insertLatex(latex: String) {}
    override fun updateLatex(latex: String?) {}
}

// =============================================================================
//  Domain <-> Storage Model Mappers
// =============================================================================

fun DiaryLog.toDiaryRecord(): DiaryRecord {
    return DiaryRecord(
        id = id,
        heading = heading,
        createdAt = createdAt,
        updatedAt = updatedAt,
        pinned = pinned,
        reminderAt = reminderAt,
        diaryLock = diaryLockPin?.let { pin ->
            if (pin.contains(":") && pin.length >= 32) {
                val parts = pin.split(":", limit = 2)
                DiaryLockRecord(parts[0], parts[1])
            } else {
                val salt = CryptoUtils.randomBytes(16)
                val hash = CryptoUtils.pbkdf2Sha256(pin.toCharArray(), salt, 100_000)
                DiaryLockRecord(CryptoUtils.toHex(salt), CryptoUtils.toHex(hash))
            }
        },
        pfp = pfp?.let { PfpRecord(it.source, it.text, it.bgColor, it.textColor, it.textScale) },
        background = BackgroundRecord(canvasBgSource, canvasBgOpacity),
        images = canvasImages.map {
            CanvasImageRecord(
                mediaId = it.id,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                opacity = it.opacity,
                behindText = it.behindText,
                sizeBytes = it.sizeBytes
            )
        },
        audios = audioAttachments.map {
            CanvasAudioRecord(
                mediaId = it.id,
                name = it.name,
                durationSec = it.durationSec,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                rate = it.playbackRate,
                behindText = it.behindText
            )
        },
        videos = videoAttachments.map {
            CanvasVideoRecord(
                mediaId = it.id,
                name = it.name,
                durationSec = it.durationSec,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                behindText = it.behindText,
                sizeBytes = it.sizeBytes
            )
        },
        content = emptyList(),
        plainPreview = plainPreview,
        contentHtml = contentHtml
    )
}

fun DiaryRecord.toSummaryDiaryLog(): DiaryLog {
    return DiaryLog(
        id = id,
        heading = heading,
        contentHtml = "",
        plainPreview = plainPreview,
        pfp = pfp?.let { Pfp(it.sourceMediaId, it.text, it.bgColor, it.textColor, it.textScale) },
        createdAt = createdAt,
        updatedAt = updatedAt,
        reminderAt = reminderAt,
        pinned = pinned,
        diaryLockPin = diaryLock?.let { "${it.saltHex}:${it.hashHex}" },
        canvasBgSource = null,
        canvasImages = emptyList(),
        audioAttachments = emptyList(),
        videoAttachments = emptyList(),
        isContentLoaded = false
    )
}

fun DiaryRecord.toDiaryLog(): DiaryLog {
    return DiaryLog(
        id = id,
        heading = heading,
        contentHtml = contentHtml,
        plainPreview = plainPreview,
        pfp = pfp?.let { Pfp(it.sourceMediaId, it.text, it.bgColor, it.textColor, it.textScale) },
        createdAt = createdAt,
        updatedAt = updatedAt,
        reminderAt = reminderAt,
        pinned = pinned,
        diaryLockPin = diaryLock?.let { "${it.saltHex}:${it.hashHex}" },
        canvasBgSource = background?.mediaId,
        canvasBgOpacity = background?.opacity ?: 0.35f,
        canvasImages = images.map {
            CanvasImage(
                id = it.mediaId,
                source = it.mediaId,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                opacity = it.opacity,
                rotation = it.rotation,
                behindText = it.behindText,
                sizeBytes = it.sizeBytes
            )
        },
        audioAttachments = audios.map {
            CanvasAudio(
                id = it.mediaId,
                name = it.name,
                audioUri = it.mediaId,
                durationSec = it.durationSec,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                playbackRate = it.rate,
                behindText = it.behindText
            )
        },
        videoAttachments = videos.map {
            CanvasVideo(
                id = it.mediaId,
                name = it.name.ifBlank { "Video_${it.mediaId.take(6)}" },
                source = it.mediaId,
                durationSec = it.durationSec,
                x = it.x,
                y = it.y,
                width = it.width,
                height = it.height,
                rotation = it.rotation,
                behindText = it.behindText,
                sizeBytes = it.sizeBytes
            )
        }
    )
}

fun WordAnalysisStorageRecord.toDomain(): WordAnalysisRecord = WordAnalysisRecord(
    word = word,
    analyzedAt = analyzedAt,
    phoneticTranscription = phoneticTranscription,
    syllableMetrics = syllableMetrics,
    perfectRhymes = perfectRhymes,
    nearSlantRhymes = nearSlantRhymes,
    meterAndRhythmScore = meterAndRhythmScore,
    definitions = definitions.map { WordDefinition(it.partOfSpeech, it.definition, it.example) },
    synonyms = synonyms,
    antonyms = antonyms,
    hypernyms = hypernyms,
    hyponyms = hyponyms,
    collocations = collocations,
    partOfSpeech = partOfSpeech,
    lemmatizationAndRoots = lemmatizationAndRoots,
    affixBreakdown = affixBreakdown,
    etymologyAndOrigin = etymologyAndOrigin,
    grammaticalDependency = grammaticalDependency,
    contextualSentimentPolarity = contextualSentimentPolarity,
    emotionalValence = emotionalValence,
    formalityAndRegister = formalityAndRegister,
    connotationAndTone = connotationAndTone,
    namedEntityRecognition = namedEntityRecognition,
    entityDisambiguation = entityDisambiguation,
    externalKnowledgeLinks = externalKnowledgeLinks,
    styleAndGrammarNotes = styleAndGrammarNotes
)

fun WordAnalysisRecord.toStorage(): WordAnalysisStorageRecord = WordAnalysisStorageRecord(
    word = word,
    analyzedAt = analyzedAt,
    phoneticTranscription = phoneticTranscription,
    syllableMetrics = syllableMetrics,
    perfectRhymes = perfectRhymes,
    nearSlantRhymes = nearSlantRhymes,
    meterAndRhythmScore = meterAndRhythmScore,
    definitions = definitions.map { WordDefinitionStorageRecord(it.partOfSpeech, it.definition, it.example) },
    synonyms = synonyms,
    antonyms = antonyms,
    hypernyms = hypernyms,
    hyponyms = hyponyms,
    collocations = collocations,
    partOfSpeech = partOfSpeech,
    lemmatizationAndRoots = lemmatizationAndRoots,
    affixBreakdown = affixBreakdown,
    etymologyAndOrigin = etymologyAndOrigin,
    grammaticalDependency = grammaticalDependency,
    contextualSentimentPolarity = contextualSentimentPolarity,
    emotionalValence = emotionalValence,
    formalityAndRegister = formalityAndRegister,
    connotationAndTone = connotationAndTone,
    namedEntityRecognition = namedEntityRecognition,
    entityDisambiguation = entityDisambiguation,
    externalKnowledgeLinks = externalKnowledgeLinks,
    styleAndGrammarNotes = styleAndGrammarNotes
)
