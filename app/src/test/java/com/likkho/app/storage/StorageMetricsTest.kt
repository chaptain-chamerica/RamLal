package com.likkho.app.storage

import com.likkho.app.DiaryLog
import com.likkho.app.ExportFormat
import com.likkho.app.RealFeatureHooks
import com.likkho.app.CanvasImage
import com.likkho.app.storage.model.DiaryRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class StorageMetricsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun measureStorageBreakdown_reportsExactMbPerFolder_andIdentifiesLargest() = runTest {
        val filesDir = tempFolder.newFolder("files")
        val vaultsDir = File(filesDir, "vaults")
        val v1Media = File(vaultsDir, "v1/media").apply { mkdirs() }
        val v1Diaries = File(vaultsDir, "v1/diaries").apply { mkdirs() }
        val v1Quarantine = File(vaultsDir, "v1/quarantine").apply { mkdirs() }
        val tmpSnapshot = File(vaultsDir, "tmp/vault_snapshot_123/media").apply { mkdirs() }
        val backupsDir = File(filesDir, "backups").apply { mkdirs() }
        val exportsDir = File(filesDir, "exports").apply { mkdirs() }

        fun writeSized(file: File, mb: Int) {
            FileOutputStream(file).use { fos ->
                val chunk = ByteArray(1024 * 1024) { 0x2A }
                repeat(mb) { fos.write(chunk) }
            }
        }

        // Simulate realistic uncleaned storage bloat:
        // 3 backups (18 MB), active media (4 MB), orphan/stale snapshot (6 MB), exports (2 MB), diaries (1 MB), quarantine (1 MB)
        writeSized(File(v1Media, "med_1.lkm"), 4)
        writeSized(File(v1Diaries, "d_1.lkd"), 1)
        writeSized(File(v1Quarantine, "bad_1.lkd"), 1)
        writeSized(File(backupsDir, "backup_1.lkb"), 6)
        writeSized(File(backupsDir, "backup_2.lkb"), 6)
        writeSized(File(backupsDir, "backup_3.lkb"), 6)
        writeSized(File(exportsDir, "all_export.zip"), 2)
        writeSized(File(tmpSnapshot, "med_1.lkm"), 6)

        val report = StorageMetrics.measureStorageBreakdown(filesDir, vaultsDir)
        println(report.formatReport())

        assertEquals(1, report.vaults.size)
        assertEquals("v1", report.vaults[0].vaultDirName)
        assertEquals(4.0, report.vaults[0].mediaMb, 0.01)
        assertEquals(1.0, report.vaults[0].diariesMb, 0.01)
        assertEquals(1.0, report.vaults[0].quarantineMb, 0.01)
        assertEquals(18.0, report.backupsMb, 0.01)
        assertEquals(2.0, report.exportsMb, 0.01)
        assertEquals(6.0, report.vaultTmpMb, 0.01)
        assertEquals(32.0, report.filesDirMb, 0.01)
        assertEquals("backups/", report.largestCategory().first)
        assertTrue(report.formatReport().contains("largest: backups/"))
    }
}
