package com.likkho.app.storage.migration

import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.DiaryFileCodec
import com.likkho.app.storage.ReadOnlyDiaryException
import com.likkho.app.storage.SafeStorageWriter
import com.likkho.app.storage.model.DiaryRecord
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException

interface MigrationStep {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(rawJson: String): String
}

/**
 * Migration engine per STORAGE_SPEC Section 9:
 * - Every diary file has schemaVersion in plain header.
 * - Migrator chain v1 -> v2 -> ... vN; each step is a pure function.
 * - Creates a backup copy before migration; verifies new format before deleting old copy.
 * - Rejects files with newer schema version than supported (read-only rule).
 */
class DiaryMigrator(
    val targetVersion: Int = DiaryFileCodec.CURRENT_SCHEMA_VERSION,
    private val steps: List<MigrationStep> = defaultSteps()
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun defaultSteps(): List<MigrationStep> {
            // Built-in steps for schema evolution. Currently starting at v1.
            return emptyList()
        }
    }

    fun canMigrate(fromVersion: Int): Boolean {
        if (fromVersion == targetVersion) return true
        if (fromVersion > targetVersion) return false
        var cur = fromVersion
        while (cur < targetVersion) {
            val step = steps.find { it.fromVersion == cur } ?: return false
            cur = step.toVersion
        }
        return cur == targetVersion
    }

    /**
     * Migrates raw JSON string sequentially through pure function steps.
     */
    fun migrateJson(fromVersion: Int, initialJson: String): String {
        if (fromVersion == targetVersion) return initialJson
        if (fromVersion > targetVersion) {
            throw ReadOnlyDiaryException("unknown", fromVersion)
        }

        var currentJson = initialJson
        var currentVersion = fromVersion

        while (currentVersion < targetVersion) {
            val step = steps.find { it.fromVersion == currentVersion }
                ?: throw IllegalStateException("Missing migration step from version $currentVersion to $targetVersion")
            currentJson = step.migrate(currentJson)
            currentVersion = step.toVersion
        }

        return currentJson
    }

    /**
     * Safely migrates a .lkd file on disk to [targetVersion].
     * Creates a .migration_bak backup before writing, verifies read-back, then removes the backup.
     */
    @Synchronized
    fun migrateFile(file: File, dek: ByteArray, diaryId: String): Int {
        if (!file.exists()) throw IOException("Diary file not found: ${file.name}")

        val bytes = file.readBytes()
        if (bytes.size < DiaryFileCodec.HEADER_SIZE) {
            throw IOException("File too short for LKD header: ${file.name}")
        }

        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        if (magic != DiaryFileCodec.MAGIC) {
            throw IOException("Invalid diary file magic: 0x${Integer.toHexString(magic)}")
        }

        val fileVersion = dis.readInt()
        if (fileVersion == targetVersion) {
            return targetVersion // Already up to date
        }
        if (fileVersion > targetVersion) {
            throw ReadOnlyDiaryException(diaryId, fileVersion)
        }

        if (!canMigrate(fileVersion)) {
            throw IllegalStateException("No migration path available from version $fileVersion to $targetVersion")
        }

        // 1. Create safety backup copy before modifying
        val bakFile = File(file.parentFile, "${file.name}.migration_bak")
        file.copyTo(bakFile, overwrite = true)

        try {
            val iv = ByteArray(CryptoUtils.GCM_IV_SIZE_BYTES)
            dis.readFully(iv)
            val ciphertext = ByteArray(dis.available())
            dis.readFully(ciphertext)

            val ivAndCiphertext = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.size)
            System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.size, ciphertext.size)

            val aad = DiaryFileCodec.buildAad(fileVersion, diaryId)
            val plaintext = CryptoUtils.aesGcmDecrypt(dek, ivAndCiphertext, aad)
            val rawJson = plaintext.toString(Charsets.UTF_8)

            // 2. Run pure function migration chain
            val migratedJson = migrateJson(fileVersion, rawJson)

            // 3. Verify that migrated JSON parses into valid DiaryRecord
            val verifiedRecord = json.decodeFromString<DiaryRecord>(migratedJson)

            // 4. Encode with targetVersion
            val newBytes = DiaryFileCodec.encode(verifiedRecord, dek, schemaVersion = targetVersion)

            // 5. Safe write and verify
            SafeStorageWriter.safeWrite(file, newBytes) { tmpFile ->
                val readBack = DiaryFileCodec.decode(tmpFile.readBytes(), dek, diaryId)
                if (readBack.schemaVersion != targetVersion) {
                    throw IOException("Migrated file schema version mismatch: expected $targetVersion got ${readBack.schemaVersion}")
                }
            }

            // 6. Migration succeeded and verified, delete backup copy
            if (bakFile.exists()) {
                bakFile.delete()
            }

            return targetVersion
        } catch (e: Exception) {
            // Rollback from backup if file was corrupted during migration
            if (bakFile.exists()) {
                bakFile.copyTo(file, overwrite = true)
                bakFile.delete()
            }
            throw e
        }
    }
}
