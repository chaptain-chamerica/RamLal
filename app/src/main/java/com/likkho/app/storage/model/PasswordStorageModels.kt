package com.likkho.app.storage.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedPasswordRecord(
    val id: String,
    val name: String,
    val password: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PasswordBundleRecord(
    val version: Int = 1,
    val passwords: List<SavedPasswordRecord> = emptyList()
)
