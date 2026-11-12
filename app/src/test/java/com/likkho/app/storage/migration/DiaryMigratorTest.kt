package com.likkho.app.storage.migration

import com.likkho.app.storage.CryptoUtils
import com.likkho.app.storage.DiaryFileCodec
import com.likkho.app.storage.ReadOnlyDiaryException
import com.likkho.app.storage.model.DiaryRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiaryMigratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testDir: File
    private val testDek = CryptoUtils.secureRandomBytes(32)

    private val sampleStepV1ToV2 = object : MigrationStep {
        override val fromVersion: Int = 1
        override val toVersion: Int = 2
        override fun migrate(rawJson: String): String {
            // Pure function: Appends " [v2 Migrated]" to heading
            val element = Json.parseToJsonElement(rawJson).jsonObject
            val map = element.toMutableMap()
            val oldHeading = map["heading"]?.jsonPrimitive?.content ?: ""
            map["heading"] = kotlinx.serialization.json.JsonPrimitive("$oldHeading [v2 Migrated]")
            return Json.encodeToString(map)
        }
    }

    @Before
    fun setUp() {
        testDir = tempFolder.newFolder("migration_test")
    }

    @Test
    fun migrateJson_runsPureFunctionChain() {
        val migrator = DiaryMigrator(targetVersion = 2, steps = listOf(sampleStepV1ToV2))
        val initialRecord = DiaryRecord(id = "diary_m1", heading = "Old Entry")
        val initialJson = Json.encodeToString(initialRecord)

        val migratedJson = migrator.migrateJson(1, initialJson)
        val decoded = Json.decodeFromString<DiaryRecord>(migratedJson)
        assertEquals("Old Entry [v2 Migrated]", decoded.heading)
    }

    @Test
    fun migrateFile_createsBackup_verifiesAndDeletesBackupOnSuccess() {
        val migrator = DiaryMigrator(targetVersion = 2, steps = listOf(sampleStepV1ToV2))
        val diary = DiaryRecord(id = "diary_file_1", heading = "Original Title")
        val v1Bytes = DiaryFileCodec.encode(diary, testDek, schemaVersion = 1)

        val targetFile = File(testDir, "${diary.id}.lkd")
        targetFile.writeBytes(v1Bytes)

        val newVersion = migrator.migrateFile(targetFile, testDek, diary.id)
        assertEquals(2, newVersion)

        // Verify file is readable with v2
        val decoded = DiaryFileCodec.decode(targetFile.readBytes(), testDek, diary.id)
        assertEquals(2, decoded.schemaVersion)
        assertEquals("Original Title [v2 Migrated]", decoded.record.heading)

        // Safety backup must be removed on success
        val bakFile = File(testDir, "${diary.id}.lkd.migration_bak")
        assertFalse(bakFile.exists())
    }

    @Test(expected = ReadOnlyDiaryException::class)
    fun migrateFile_rejectsFutureSchemaVersion() {
        val migrator = DiaryMigrator(targetVersion = 1, steps = emptyList())
        val diary = DiaryRecord(id = "future_diary", heading = "Future")
        val futureBytes = DiaryFileCodec.encode(diary, testDek, schemaVersion = 99)

        val targetFile = File(testDir, "${diary.id}.lkd")
        targetFile.writeBytes(futureBytes)

        migrator.migrateFile(targetFile, testDek, diary.id)
    }
}
