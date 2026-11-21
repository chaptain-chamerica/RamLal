package com.likkho.app.storage.model

import kotlinx.serialization.Serializable

@Serializable
data class DiaryLockRecord(
    val saltHex: String,
    val hashHex: String
)

@Serializable
data class PfpRecord(
    val sourceMediaId: String? = null,
    val text: String? = null,
    val bgColor: Long = 0xFF3366CC,
    val textColor: Long = 0xFFFFFFFF,
    val textScale: Float = 1f
)

@Serializable
data class BackgroundRecord(
    val mediaId: String? = null,
    val opacity: Float = 0.35f
)

@Serializable
data class CanvasImageRecord(
    val mediaId: String,
    val x: Float = 24f,
    val y: Float = 120f,
    val width: Float = 160f,
    val height: Float = 120f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val behindText: Boolean = false,
    val sizeBytes: Long = 0
)

@Serializable
data class CanvasAudioRecord(
    val mediaId: String,
    val name: String,
    val durationSec: Int = 1,
    val x: Float = 24f,
    val y: Float = 120f,
    val width: Float = 270f,
    val height: Float = 56f,
    val rotation: Float = 0f,
    val rate: Float = 1f,
    val behindText: Boolean = false
)

@Serializable
data class CanvasVideoRecord(
    val mediaId: String,
    val name: String = "",
    val durationSec: Int = 1,
    val x: Float = 24f,
    val y: Float = 140f,
    val width: Float = 220f,
    val height: Float = 150f,
    val rotation: Float = 0f,
    val behindText: Boolean = false,
    val sizeBytes: Long = 0
)

@Serializable
data class SpanStyleRecord(
    val start: Int,
    val end: Int,
    val style: String,
    val value: String? = null
)

@Serializable
data class ContentBlockRecord(
    val type: String,
    val text: String = "",
    val spans: List<SpanStyleRecord> = emptyList(),
    val alignment: String = "start",
    val indent: Int = 0,
    val checked: Boolean = false,
    val id: String? = null,
    val passcodeHash: String? = null
)

@Serializable
data class DiaryRecord(
    val id: String,
    val heading: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val reminderAt: Long? = null,
    val diaryLock: DiaryLockRecord? = null,
    val pfp: PfpRecord? = null,
    val background: BackgroundRecord? = null,
    val images: List<CanvasImageRecord> = emptyList(),
    val audios: List<CanvasAudioRecord> = emptyList(),
    val videos: List<CanvasVideoRecord> = emptyList(),
    val content: List<ContentBlockRecord> = emptyList(),
    val plainPreview: String = "",
    val contentHtml: String = ""
)

@Serializable
data class IndexEntryRecord(
    val id: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val flags: Int = 0,
    val plainPreview: String = "",
    val heading: String = "",
    val mediaIds: List<String> = emptyList(),
    val hasTrackedMediaIds: Boolean = false,
    val reminderAt: Long? = null,
    val pfp: PfpRecord? = null
)

@Serializable
data class VaultIndexRecord(
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
    val entries: List<IndexEntryRecord> = emptyList()
)
