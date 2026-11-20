package com.likkho.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

enum class ImportingMediaKind { VIDEO, IMAGE, AUDIO }

enum class ImportPhase { PREPARING, ENCRYPTING, VERIFYING }

data class ImportingItem(
    val kind: ImportingMediaKind,
    val name: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val phase: ImportPhase,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
)

class ImportCancelledException : IOException("Import cancelled")

internal class CountingInputStream(
    private val delegate: InputStream,
    private val totalBytes: Long = 0L,
    private val throttleMs: Long = 100L,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val isCancelled: (() -> Boolean)? = null,
    private val onFirstRead: (() -> Unit)? = null,
    private val onProgress: ((bytesDone: Long, bytesTotal: Long) -> Unit)? = null,
    private val onEof: (() -> Unit)? = null,
) : InputStream() {
    var bytesRead: Long = 0L
        private set

    private var started = false
    private var eofNotified = false
    private var lastEmitMs = -1L

    private fun checkCancelled() {
        if (isCancelled?.invoke() == true) {
            throw ImportCancelledException()
        }
    }

    private fun onBytesCounted(count: Int) {
        if (count == -1) {
            if (!eofNotified) {
                eofNotified = true
                onEof?.invoke()
            }
            return
        }
        if (count > 0) {
            if (!started) {
                started = true
                onFirstRead?.invoke()
            }
            bytesRead += count
            val now = clockMs()
            if (lastEmitMs < 0L || now - lastEmitMs >= throttleMs) {
                lastEmitMs = now
                onProgress?.invoke(bytesRead, totalBytes)
            }
        }
    }

    override fun read(): Int {
        checkCancelled()
        val b = delegate.read()
        checkCancelled()
        if (b >= 0) onBytesCounted(1) else onBytesCounted(-1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkCancelled()
        val n = delegate.read(b, off, len)
        checkCancelled()
        onBytesCounted(n)
        return n
    }

    override fun close() = delegate.close()
}

internal const val IMPORT_SKELETON_DELAY_MS = 300L

internal fun shouldShowImportSkeleton(item: ImportingItem?, elapsedMs: Long): Boolean {
    return item != null && elapsedMs >= IMPORT_SKELETON_DELAY_MS
}

internal fun canSaveDiary(importingItem: ImportingItem?): Boolean {
    return importingItem == null
}

internal fun formatImportStatusText(item: ImportingItem): String = when (item.phase) {
    ImportPhase.PREPARING -> "Preparing\u2026"
    ImportPhase.VERIFYING -> "Verifying\u2026"
    ImportPhase.ENCRYPTING -> {
        val total = item.bytesTotal
        if (total <= 0L) {
            "Encrypting\u2026"
        } else {
            val done = item.bytesDone.coerceIn(0L, total)
            val pct = ((done.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            val ratioLabel = when {
                total >= 10L * 1024L * 1024L -> {
                    val doneMb = (done / (1024.0 * 1024.0)).roundToInt()
                    val totalMb = (total / (1024.0 * 1024.0)).roundToInt()
                    "$doneMb / $totalMb MB"
                }
                total >= 1024L * 1024L -> {
                    val doneMb = String.format(Locale.US, "%.1f", done / (1024.0 * 1024.0))
                    val totalMb = String.format(Locale.US, "%.1f", total / (1024.0 * 1024.0))
                    "$doneMb / $totalMb MB"
                }
                total >= 1024L -> {
                    "${done / 1024L} / ${total / 1024L} KB"
                }
                else -> "$done / $total B"
            }
            "Encrypting\u2026 $pct% ($ratioLabel)"
        }
    }
}

internal fun queryUriDisplayAndSize(context: Context, uri: Uri, fallbackName: String): Pair<String, Long> {
    var name: String? = null
    var size = 0L
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && !c.isNull(nameIdx)) {
                    name = c.getString(nameIdx)?.trim()?.takeIf { it.isNotEmpty() }
                }
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                    val s = c.getLong(sizeIdx)
                    if (s > 0L) size = s
                }
            }
        }
    }
    if (size <= 0L) {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0L) size = afd.length
            }
        }
    }
    val resolvedName = name
        ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallbackName
    return resolvedName to size.coerceAtLeast(0L)
}

@Composable
internal fun ImportSkeletonCard(
    item: ImportingItem,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val w = wiki()
    val infiniteTransition = rememberInfiniteTransition(label = "import_skeleton_shimmer")
    val shimmerAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "import_skeleton_alpha",
    )
    val kindIcon = when (item.kind) {
        ImportingMediaKind.VIDEO -> Icons.Outlined.VideoFile
        ImportingMediaKind.IMAGE -> Icons.Outlined.Image
        ImportingMediaKind.AUDIO -> Icons.Outlined.AudioFile
    }
    val statusText = formatImportStatusText(item)
    val determinate = item.phase == ImportPhase.ENCRYPTING && item.bytesTotal > 0L
    val fraction = if (determinate) {
        (item.bytesDone.toFloat() / item.bytesTotal.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = modifier
            .offset { IntOffset(with(this) { item.x.dp.roundToPx() }, with(this) { item.y.dp.roundToPx() }) }
            .size(item.width.dp, item.height.dp)
            .background(w.surface)
            .wikiBorder(w.border, 1.dp)
            .testTag("import_skeleton_card")
    ) {
        if (item.kind == ImportingMediaKind.AUDIO) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        kindIcon,
                        contentDescription = null,
                        tint = w.muted.copy(alpha = shimmerAlpha),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = sansStyle(11.sp, w.text, FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = statusText,
                            style = monoStyle(9.sp, w.muted),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(w.bg)
                            .wikiBorder(w.border, 0.5.dp)
                            .semantics { contentDescription = "Cancel import" }
                            .clickable(onClick = onCancel)
                            .testTag("cancel_import_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel import", tint = w.muted, modifier = Modifier.size(14.dp))
                    }
                }
                ImportProgressBar(
                    determinate = determinate,
                    fraction = fraction,
                    heightDp = 2f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(kindIcon, contentDescription = null, tint = w.muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = item.name,
                        style = sansStyle(11.sp, w.text, FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(w.bg)
                            .wikiBorder(w.border, 0.5.dp)
                            .semantics { contentDescription = "Cancel import" }
                            .clickable(onClick = onCancel)
                            .testTag("cancel_import_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel import", tint = w.muted, modifier = Modifier.size(14.dp))
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(w.hairline.copy(alpha = shimmerAlpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        kindIcon,
                        contentDescription = null,
                        tint = w.muted.copy(alpha = shimmerAlpha * 0.7f),
                        modifier = Modifier.size(24.dp),
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ImportProgressBar(
                        determinate = determinate,
                        fraction = fraction,
                        heightDp = 4f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = statusText,
                        style = monoStyle(9.sp, w.muted),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportProgressBar(
    determinate: Boolean,
    fraction: Float,
    heightDp: Float,
    modifier: Modifier = Modifier,
) {
    val w = wiki()
    if (determinate) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = modifier.height(heightDp.dp),
            color = w.accent,
            trackColor = w.hairline,
            strokeCap = StrokeCap.Butt,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier.height(heightDp.dp),
            color = w.accent,
            trackColor = w.hairline,
            strokeCap = StrokeCap.Butt,
        )
    }
}
