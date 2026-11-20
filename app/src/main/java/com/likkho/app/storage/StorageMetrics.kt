package com.likkho.app.storage

import android.util.Log
import java.io.File
import java.util.Locale

data class VaultStorageMetrics(
    val vaultDirName: String,
    val mediaBytes: Long,
    val diariesBytes: Long,
    val quarantineBytes: Long
) {
    val mediaMb: Double get() = bytesToMb(mediaBytes)
    val diariesMb: Double get() = bytesToMb(diariesBytes)
    val quarantineMb: Double get() = bytesToMb(quarantineBytes)
}

data class StorageBreakdownReport(
    val filesDirBytes: Long,
    val backupsBytes: Long,
    val exportsBytes: Long,
    val sharesBytes: Long,
    val vaultTmpBytes: Long,
    val cacheExportsBytes: Long,
    val cacheSharesBytes: Long,
    val vaults: List<VaultStorageMetrics>
) {
    val filesDirMb: Double get() = bytesToMb(filesDirBytes)
    val backupsMb: Double get() = bytesToMb(backupsBytes)
    val exportsMb: Double get() = bytesToMb(exportsBytes)
    val sharesMb: Double get() = bytesToMb(sharesBytes)
    val vaultTmpMb: Double get() = bytesToMb(vaultTmpBytes)
    val cacheExportsMb: Double get() = bytesToMb(cacheExportsBytes)
    val cacheSharesMb: Double get() = bytesToMb(cacheSharesBytes)

    fun categorySizesBytes(): Map<String, Long> {
        val map = linkedMapOf<String, Long>()
        vaults.forEach { v ->
            map["vaults/${v.vaultDirName}/media"] = v.mediaBytes
            map["vaults/${v.vaultDirName}/diaries"] = v.diariesBytes
            map["vaults/${v.vaultDirName}/quarantine"] = v.quarantineBytes
        }
        map["backups/"] = backupsBytes
        map["exports/"] = exportsBytes
        if (sharesBytes > 0L) map["shares/"] = sharesBytes
        map["vaults/tmp"] = vaultTmpBytes
        if (cacheExportsBytes > 0L) map["cache/exports"] = cacheExportsBytes
        if (cacheSharesBytes > 0L) map["cache/shares"] = cacheSharesBytes
        return map
    }

    fun largestCategory(): Pair<String, Long> {
        return categorySizesBytes().maxByOrNull { it.value }?.toPair() ?: ("none" to 0L)
    }

    fun formatReport(): String = buildString {
        appendLine("=== Likkho Storage Breakdown (MB) ===")
        for (v in vaults) {
            appendLine("vaults/${v.vaultDirName}/media: ${fmtMb(v.mediaBytes)} MB (${v.mediaBytes} B)")
            appendLine("vaults/${v.vaultDirName}/diaries: ${fmtMb(v.diariesBytes)} MB (${v.diariesBytes} B)")
            appendLine("vaults/${v.vaultDirName}/quarantine: ${fmtMb(v.quarantineBytes)} MB (${v.quarantineBytes} B)")
        }
        appendLine("backups/: ${fmtMb(backupsBytes)} MB ($backupsBytes B)")
        appendLine("exports/: ${fmtMb(exportsBytes)} MB ($exportsBytes B)")
        appendLine("shares/: ${fmtMb(sharesBytes)} MB ($sharesBytes B)")
        appendLine("vaults/tmp: ${fmtMb(vaultTmpBytes)} MB ($vaultTmpBytes B)")
        appendLine("cache/exports: ${fmtMb(cacheExportsBytes)} MB ($cacheExportsBytes B)")
        appendLine("cache/shares: ${fmtMb(cacheSharesBytes)} MB ($cacheSharesBytes B)")
        appendLine("total filesDir: ${fmtMb(filesDirBytes)} MB ($filesDirBytes B)")
        val (topName, topBytes) = largestCategory()
        append("largest: $topName (${fmtMb(topBytes)} MB)")
    }
}

internal fun bytesToMb(bytes: Long): Double = bytes.toDouble() / (1024.0 * 1024.0)

internal fun fmtMb(bytes: Long): String = String.format(Locale.US, "%.2f", bytesToMb(bytes))

object StorageMetrics {

    fun directorySizeBytes(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isFile) {
                    total += child.length()
                } else if (child.isDirectory) {
                    stack.addLast(child)
                }
            }
        }
        return total
    }

    fun measureStorageBreakdown(
        filesDir: File,
        vaultsDir: File = File(filesDir, "vaults"),
        cacheDir: File? = null
    ): StorageBreakdownReport {
        val vaultMetrics = mutableListOf<VaultStorageMetrics>()
        val vaultCandidates = (vaultsDir.listFiles { f ->
            f.isDirectory && f.name != "tmp"
        } ?: emptyArray()).sortedBy { it.name }

        for (vDir in vaultCandidates) {
            vaultMetrics.add(
                VaultStorageMetrics(
                    vaultDirName = vDir.name,
                    mediaBytes = directorySizeBytes(File(vDir, "media")),
                    diariesBytes = directorySizeBytes(File(vDir, "diaries")),
                    quarantineBytes = directorySizeBytes(File(vDir, "quarantine"))
                )
            )
        }

        return StorageBreakdownReport(
            filesDirBytes = directorySizeBytes(filesDir),
            backupsBytes = directorySizeBytes(File(filesDir, "backups")),
            exportsBytes = directorySizeBytes(File(filesDir, "exports")),
            sharesBytes = directorySizeBytes(File(filesDir, "shares")),
            vaultTmpBytes = directorySizeBytes(File(vaultsDir, "tmp")),
            cacheExportsBytes = directorySizeBytes(cacheDir?.let { File(it, "exports") }),
            cacheSharesBytes = directorySizeBytes(cacheDir?.let { File(it, "shares") }),
            vaults = vaultMetrics
        )
    }

    fun logStorageBreakdown(
        filesDir: File,
        vaultsDir: File = File(filesDir, "vaults"),
        cacheDir: File? = null,
        tag: String = "LikkhoStorage"
    ): StorageBreakdownReport {
        val report = measureStorageBreakdown(filesDir, vaultsDir, cacheDir)
        val formatted = report.formatReport()
        runCatching { Log.d(tag, formatted) }
        return report
    }
}
