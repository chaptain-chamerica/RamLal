package com.likkho.app.storage.model

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class BackupItemHash(
    val path: String,
    val sha256: String,
    val sizeBytes: Long
)

@Serializable
data class BackupManifest(
    val schemaVersion: Int = 1,
    val appVersion: String = "1.0.0",
    val createdAt: Long = System.currentTimeMillis(),
    val diaryCount: Int = 0,
    val mediaCount: Int = 0,
    val items: List<BackupItemHash> = emptyList()
)

data class BackupResult(
    val success: Boolean,
    val diaryCount: Int,
    val mediaCount: Int,
    val failedMediaCount: Int,
    val backupFile: File? = null
)

data class RestoreResult(
    val restoredDiaries: Int,
    val conflictCopies: Int,
    val importedMedia: Int,
    val skippedMediaCount: Int
)

data class BackupDiarySummary(
    val id: String,
    val heading: String,
    val createdAt: Long,
    val updatedAt: Long,
    val plainPreview: String
)

data class BackupContents(
    val manifest: BackupManifest,
    val diaries: List<BackupDiarySummary>,
    val mediaIds: List<String>
)
