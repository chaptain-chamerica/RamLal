package com.likkho.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

internal fun formatStorageMb(bytes: Long): String {
    val mb = bytes.coerceAtLeast(0L).toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.2f MB", mb)
}

internal fun storageFreeUpButtonLabel(preview: FreeUpPreview): String {
    return if (preview.isEmpty) "Nothing to clean"
    else "Free up space (${formatStorageMb(preview.totalBytes)})"
}

internal data class StorageBarSegment(
    val category: StorageCategory,
    val label: String,
    val bytes: Long,
    val color: Color,
)

internal fun buildStorageBarSegments(
    report: StorageReport,
    palette: StoragePalette,
): List<StorageBarSegment> {
    val all = listOf(
        StorageBarSegment(StorageCategory.DIARIES, "Diaries", report.diaries.bytes, palette.diaries),
        StorageBarSegment(StorageCategory.PHOTOS, "Photos", report.photos.bytes, palette.photos),
        StorageBarSegment(StorageCategory.AUDIO, "Audio", report.audio.bytes, palette.audio),
        StorageBarSegment(StorageCategory.VIDEOS, "Videos", report.videos.bytes, palette.videos),
        StorageBarSegment(StorageCategory.BACKUPS, "Backups", report.backups.bytes, palette.backups),
        StorageBarSegment(StorageCategory.TEMPORARY, "Temp", report.temporaryFiles.bytes, palette.temporary),
        StorageBarSegment(StorageCategory.UNUSED_MEDIA, "Unused", report.unusedMedia.bytes, palette.unused),
        StorageBarSegment(StorageCategory.DAMAGED_COPIES, "Damaged", report.damagedCopies.bytes, palette.damaged),
    )
    return all.filter { it.bytes > 0L }
}

@Composable
fun StoragePage(vm: AppViewModel, onBack: () -> Unit) {
    val w = wiki()
    val palette = storagePalette()
    val scope = rememberCoroutineScope()
    val hooks = vm.hooks

    var report by remember { mutableStateOf(StorageReport()) }
    var preview by remember { mutableStateOf(FreeUpPreview()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var expandedCategory by remember { mutableStateOf<StorageCategory?>(null) }
    var showFreeUpConfirm by remember { mutableStateOf(false) }
    var showQuarantineConfirm by remember { mutableStateOf(false) }
    var showOlderBackupsConfirm by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<Pair<BannerKind, String>?>(null) }

    fun showBanner(kind: BannerKind, msg: String) {
        banner = kind to msg
        scope.launch {
            delay(4000)
            if (banner?.second == msg) banner = null
        }
    }

    suspend fun refreshStorageData() {
        loading = true
        runCatching {
            val r = hooks.getStorageReport()
            val p = hooks.previewFreeUpSpace()
            report = r
            preview = p
        }.onFailure { err ->
            showBanner(BannerKind.ERROR, err.message ?: "Failed to load storage breakdown.")
        }
        loading = false
    }

    LaunchedEffect(vm.vaultMode) {
        refreshStorageData()
    }

    BackHandler { onBack() }

    val segments = remember(report, palette) { buildStorageBarSegments(report, palette) }

    Column(
        Modifier
            .fillMaxSize()
            .background(w.bg)
    ) {
        ScreenHeader(
            title = "Storage",
            onBack = onBack,
            subtitle = if (loading) "Calculating..." else "Total: ${formatStorageMb(report.activeVaultTotalBytes)}",
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            banner?.let { (kind, msg) ->
                StatusBanner(
                    text = msg,
                    kind = kind,
                    icon = if (kind == BannerKind.ERROR) Icons.Outlined.Warning else Icons.Outlined.CheckCircle,
                )
            }

            // 1. Stacked Bar Summary Card + Primary Free Up Space Button
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(w.surface)
                    .wikiBorder(w.border, 1.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Storage Breakdown", style = serifStyle(18.sp, w.text))
                    Text(
                        formatStorageMb(report.activeVaultTotalBytes),
                        style = monoStyle(13.sp, w.text, FontWeight.SemiBold),
                    )
                }

                // Square 1dp bordered stacked bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .background(w.bg)
                        .wikiBorder(w.border, 1.dp),
                ) {
                    if (segments.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(w.surface),
                        )
                    } else {
                        segments.forEachIndexed { idx, seg ->
                            Box(
                                Modifier
                                    .weight(seg.bytes.toFloat().coerceAtLeast(1f))
                                    .fillMaxHeight()
                                    .background(seg.color),
                            )
                            if (idx < segments.lastIndex) {
                                Box(
                                    Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(w.bg),
                                )
                            }
                        }
                    }
                }

                if (segments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        segments.chunked(2).forEach { pair ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                pair.forEach { seg ->
                                    Row(
                                        Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            Modifier
                                                .size(10.dp)
                                                .background(seg.color)
                                                .wikiBorder(w.border, 0.5.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            seg.label,
                                            style = sansStyle(11.sp, w.text, FontWeight.Medium),
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            formatStorageMb(seg.bytes),
                                            style = monoStyle(10.sp, w.muted),
                                        )
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    Text(
                        if (loading) "Measuring active vault storage..." else "No stored files yet.",
                        style = sansStyle(12.sp, w.muted, FontWeight.Normal),
                    )
                }

                WikiButton(
                    text = if (busy) "Cleaning..." else storageFreeUpButtonLabel(preview),
                    onClick = { showFreeUpConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("storage_free_up_button"),
                    kind = if (preview.isEmpty) BtnKind.OUTLINE else BtnKind.PRIMARY,
                    icon = Icons.Outlined.DeleteSweep,
                    enabled = !loading && !busy && !preview.isEmpty,
                    height = 44.dp,
                )
            }

            // 2. Category Breakdown List (tappable rows with inline file details)
            val sections = listOf(
                Triple(report.diaries, Icons.AutoMirrored.Outlined.MenuBook, palette.diaries),
                Triple(report.photos, Icons.Outlined.Image, palette.photos),
                Triple(report.audio, Icons.Outlined.AudioFile, palette.audio),
                Triple(report.videos, Icons.Outlined.VideoFile, palette.videos),
                Triple(report.backups, Icons.Outlined.Backup, palette.backups),
                Triple(report.temporaryFiles, Icons.Outlined.Schedule, palette.temporary),
                Triple(report.unusedMedia, Icons.Outlined.DeleteSweep, palette.unused),
                Triple(report.damagedCopies, Icons.Outlined.Warning, palette.damaged),
            )

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(w.bg)
                    .wikiBorder(w.border, 1.dp),
            ) {
                sections.forEachIndexed { idx, (sec, icon, tint) ->
                    if (idx > 0) HairlineDivider()
                    val isExpanded = expandedCategory == sec.category
                    StorageSectionBlock(
                        section = sec,
                        icon = icon,
                        tint = tint,
                        expanded = isExpanded,
                        onToggleExpand = {
                            expandedCategory = if (isExpanded) null else sec.category
                        },
                        subtitleOverride = when (sec.category) {
                            StorageCategory.BACKUPS -> buildString {
                                append("${sec.count} ${if (sec.count == 1) "file" else "files"}")
                                sec.latestDateMs?.let { append(" · Latest: ${fmtDate(it)}") }
                            }
                            StorageCategory.DAMAGED_COPIES ->
                                "${sec.count} ${if (sec.count == 1) "copy" else "copies"} · Safe from Free up space"
                            else -> "${sec.count} ${if (sec.count == 1) "item" else "items"}"
                        },
                        extraContent = if (sec.category == StorageCategory.DAMAGED_COPIES && (sec.count > 0 || sec.bytes > 0L)) {
                            {
                                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                    WikiButton(
                                        text = "Delete damaged copies",
                                        onClick = { showQuarantineConfirm = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("storage_delete_damaged_button"),
                                        kind = BtnKind.DANGER,
                                        icon = Icons.Outlined.Delete,
                                        enabled = !busy,
                                        height = 38.dp,
                                    )
                                }
                            }
                        } else null,
                    )
                }
            }

            // 3. Older shared backups section (only if legacy root backups/*.lkb exist)
            if (report.olderBackups.count > 0) {
                val older = report.olderBackups
                val isOlderExpanded = expandedCategory == StorageCategory.OLDER_BACKUPS
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(w.bg)
                        .wikiBorder(w.border, 1.dp),
                ) {
                    StorageSectionBlock(
                        section = older,
                        icon = Icons.Outlined.FolderOpen,
                        tint = w.muted,
                        expanded = isOlderExpanded,
                        onToggleExpand = {
                            expandedCategory = if (isOlderExpanded) null else StorageCategory.OLDER_BACKUPS
                        },
                        subtitleOverride = "${older.count} legacy .lkb ${if (older.count == 1) "file" else "files"} · Never auto-deleted",
                        extraContent = {
                            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                WikiButton(
                                    text = "Delete older backups",
                                    onClick = { showOlderBackupsConfirm = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("storage_delete_older_backups_button"),
                                    kind = BtnKind.OUTLINE,
                                    icon = Icons.Outlined.Delete,
                                    enabled = !busy,
                                    height = 38.dp,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    // ---------- Free Up Space Confirmation Dialog ----------
    if (showFreeUpConfirm) {
        val oldBackupBytes = preview.categories
            .firstOrNull { it.type == FreeUpItemType.OLD_BACKUP }
            ?.bytes ?: 0L

        WikiDialog(
            onDismiss = { if (!busy) showFreeUpConfirm = false },
            title = "Free up space?",
            titleIcon = Icons.Outlined.DeleteSweep,
            showClose = !busy,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Section 1: What will be deleted
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(w.danger.copy(alpha = 0.06f))
                        .wikiBorder(w.danger, 1.dp)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Ye delete hoga", style = serifStyle(15.sp, w.danger))
                    if (preview.deletedBackupNames.isNotEmpty()) {
                        Text(
                            "• Backups (${preview.deletedBackupNames.size}, ${formatStorageMb(oldBackupBytes)}): " +
                                "${preview.deletedBackupNames.joinToString(", ")} hategi — latest (${preview.keptBackupName ?: "—"}) bachegi",
                            style = sansStyle(12.sp, w.text, FontWeight.Normal),
                        )
                    }
                    if (preview.unusedMediaCount > 0) {
                        Text(
                            "• Unused media: ${preview.unusedMediaCount} ${if (preview.unusedMediaCount == 1) "file" else "files"} (${formatStorageMb(preview.unusedMediaBytes)})",
                            style = sansStyle(12.sp, w.text, FontWeight.Normal),
                        )
                    }
                    if (preview.temporaryFilesCount > 0) {
                        Text(
                            "• Temporary files: ${preview.temporaryFilesCount} ${if (preview.temporaryFilesCount == 1) "file" else "files"} (${formatStorageMb(preview.temporaryFilesBytes)})",
                            style = sansStyle(12.sp, w.text, FontWeight.Normal),
                        )
                    }
                }

                // Section 2: What stays safe
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(w.success.copy(alpha = 0.06f))
                        .wikiBorder(w.success, 1.dp)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Ye safe rahega", style = serifStyle(15.sp, w.success))
                    Text("• Saved diaries aur unki media", style = sansStyle(12.sp, w.text, FontWeight.Normal))
                    Text(
                        preview.keptBackupName?.let { "• Latest backup: $it" } ?: "• Latest backup",
                        style = sansStyle(12.sp, w.text, FontWeight.Normal),
                    )
                    Text("• SAF backups (user-selected folder)", style = sansStyle(12.sp, w.text, FontWeight.Normal))
                    Text("• Damaged diary copies (quarantine)", style = sansStyle(12.sp, w.text, FontWeight.Normal))
                    if (report.olderBackups.count > 0) {
                        Text("• Older backups (${report.olderBackups.count})", style = sansStyle(12.sp, w.text, FontWeight.Normal))
                    }
                }

                ConfirmButtons(
                    cancelText = "Cancel",
                    confirmText = if (busy) "Deleting..." else "Delete ${formatStorageMb(preview.totalBytes)}",
                    confirmIcon = Icons.Outlined.Delete,
                    confirmKind = BtnKind.DANGER,
                    busy = busy,
                    onCancel = { showFreeUpConfirm = false },
                    onConfirm = {
                        busy = true
                        scope.launch {
                            val res = runCatching { hooks.executeFreeUpSpace(preview) }
                            showFreeUpConfirm = false
                            busy = false
                            res.onSuccess { freed ->
                                showBanner(BannerKind.SUCCESS, "Freed ${formatStorageMb(freed)}")
                                refreshStorageData()
                            }.onFailure { err ->
                                showBanner(BannerKind.ERROR, "Cleanup failed: ${err.message ?: "Unknown error"}")
                            }
                        }
                    },
                )
            }
        }
    }

    // ---------- Damaged Copies Strong Confirmation Dialog ----------
    if (showQuarantineConfirm) {
        WikiDialog(
            onDismiss = { if (!busy) showQuarantineConfirm = false },
            title = "Delete damaged copies?",
            titleIcon = Icons.Outlined.Warning,
            titleIconTint = w.danger,
            showClose = !busy,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Ye copies kharab diaries ki hain, delete karne par wapas nahi aayengi",
                    style = sansStyle(13.sp, w.text, FontWeight.SemiBold),
                )
                Text(
                    "${report.damagedCopies.count} files (${formatStorageMb(report.damagedCopies.bytes)}) will be permanently deleted.",
                    style = monoStyle(11.sp, w.muted),
                )
                ConfirmButtons(
                    cancelText = "Cancel",
                    confirmText = if (busy) "Deleting..." else "Delete permanently",
                    confirmIcon = Icons.Outlined.Delete,
                    confirmKind = BtnKind.DANGER,
                    busy = busy,
                    onCancel = { showQuarantineConfirm = false },
                    onConfirm = {
                        busy = true
                        scope.launch {
                            val res = runCatching { hooks.deleteDamagedCopies() }
                            showQuarantineConfirm = false
                            busy = false
                            res.onSuccess { freed ->
                                showBanner(BannerKind.SUCCESS, "Freed ${formatStorageMb(freed)}")
                                refreshStorageData()
                            }.onFailure { err ->
                                showBanner(BannerKind.ERROR, "Delete failed: ${err.message ?: "Unknown error"}")
                            }
                        }
                    },
                )
            }
        }
    }

    // ---------- Older Shared Backups Confirmation Dialog ----------
    if (showOlderBackupsConfirm) {
        val names = report.olderBackups.items.map { it.name }
        WikiDialog(
            onDismiss = { if (!busy) showOlderBackupsConfirm = false },
            title = "Delete older backups?",
            titleIcon = Icons.Outlined.Warning,
            titleIconTint = w.danger,
            showClose = !busy,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Permanently delete ${names.size} legacy .lkb backup ${if (names.size == 1) "file" else "files"} (${formatStorageMb(report.olderBackups.bytes)}) from shared backups folder? This cannot be undone.",
                    style = sansStyle(12.sp, w.text, FontWeight.Normal),
                )
                ConfirmButtons(
                    cancelText = "Cancel",
                    confirmText = if (busy) "Deleting..." else "Delete ${formatStorageMb(report.olderBackups.bytes)}",
                    confirmIcon = Icons.Outlined.Delete,
                    confirmKind = BtnKind.DANGER,
                    busy = busy,
                    onCancel = { showOlderBackupsConfirm = false },
                    onConfirm = {
                        busy = true
                        scope.launch {
                            val res = runCatching { hooks.deleteOlderSharedBackups(names) }
                            showOlderBackupsConfirm = false
                            busy = false
                            res.onSuccess { freed ->
                                showBanner(BannerKind.SUCCESS, "Freed ${formatStorageMb(freed)}")
                                refreshStorageData()
                            }.onFailure { err ->
                                showBanner(BannerKind.ERROR, "Delete failed: ${err.message ?: "Unknown error"}")
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StorageSectionBlock(
    section: StorageSection,
    icon: ImageVector,
    tint: Color,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    subtitleOverride: String,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val w = wiki()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("storage_section_${section.category.name.lowercase()}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(w.surface)
                    .wikiBorder(w.border, 0.5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = section.title, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(section.title, style = serifStyle(16.sp, w.text))
                Text(subtitleOverride, style = sansStyle(11.sp, w.muted, FontWeight.Normal))
            }
            Spacer(Modifier.width(8.dp))
            Text(
                formatStorageMb(section.bytes),
                style = monoStyle(12.sp, w.text, FontWeight.SemiBold),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse ${section.title}" else "Expand ${section.title}",
                tint = w.muted,
                modifier = Modifier.size(18.dp),
            )
        }

        if (expanded) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.hairline))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(w.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (section.items.isEmpty()) {
                    Text("No files in this category.", style = sansStyle(11.sp, w.muted, FontWeight.Normal))
                } else {
                    section.items.forEachIndexed { idx, item ->
                        if (idx > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.hairline))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = sansStyle(12.sp, w.text, FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.detail.isNotBlank()) {
                                    Text(item.detail, style = sansStyle(10.sp, w.muted, FontWeight.Normal))
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    formatStorageMb(item.sizeBytes),
                                    style = monoStyle(11.sp, w.text, FontWeight.SemiBold),
                                )
                                Text(
                                    fmtDate(item.modifiedAtMs),
                                    style = monoStyle(10.sp, w.muted),
                                )
                            }
                        }
                    }
                }
            }
        }

        extraContent?.invoke()
    }
}
