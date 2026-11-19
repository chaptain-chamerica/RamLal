package com.likkho.app.editor

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.likkho.app.CustomFont
import com.likkho.app.R
import com.likkho.app.WikiFonts
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class StoredCustomFontMeta(
    val id: String,
    val name: String,
    val lang: String = "multi",
    val fileName: String
)

/**
 * Manages built-in editorial fonts and persistent user-imported .ttf/.otf custom fonts.
 * Caches Compose [FontFamily] and Android [Typeface] instances for smooth typing and Canvas rendering
 * across both English and Hindi (Devanagari) scripts.
 */
object FontRegistry {
    const val BUILTIN_SERIF = "builtin_serif"
    const val BUILTIN_SANS = "builtin_sans"
    const val BUILTIN_MONO = "builtin_mono"
    const val MAX_FONT_BYTES = 30L * 1024L * 1024L
    private const val STREAM_BUFFER_BYTES = 64 * 1024

    val builtinFonts: List<Pair<String, String>> = listOf(
        BUILTIN_SERIF to "Cormorant",
        BUILTIN_SANS to "Jakarta Sans",
        BUILTIN_MONO to "Monospace"
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val fontFamilyCache = ConcurrentHashMap<String, FontFamily>()
    private val typefaceCache = ConcurrentHashMap<String, Typeface>()
    private val filePathMap = ConcurrentHashMap<String, String>()

    fun fontsDir(baseDir: File): File = File(baseDir, "custom_fonts").apply { mkdirs() }
    private fun indexFile(baseDir: File): File = File(fontsDir(baseDir), "fonts_index.json")

    fun loadSavedFonts(baseDir: File): List<CustomFont> {
        val idx = indexFile(baseDir)
        if (!idx.exists()) return emptyList()
        val metas = runCatching {
            json.decodeFromString<List<StoredCustomFontMeta>>(idx.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())

        val dir = fontsDir(baseDir)
        val result = mutableListOf<CustomFont>()
        for (m in metas) {
            val file = File(dir, m.fileName)
            if (file.exists() && file.length() > 0L) {
                filePathMap[m.id] = file.absolutePath
                result.add(CustomFont(id = m.id, name = m.name, lang = m.lang, filePath = file.absolutePath))
            }
        }
        return result
    }

    fun registerCustomFont(font: CustomFont) {
        if (font.filePath.isNotBlank()) {
            filePathMap[font.id] = font.filePath
        }
    }

    fun importFontFromUri(context: Context, uri: Uri, baseDir: File): CustomFont? {
        val rawDisplayName = queryDisplayName(context, uri)
        val dir = fontsDir(baseDir)
        val id = "custom_font_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}"
        val ext = if (rawDisplayName?.lowercase()?.endsWith(".otf") == true) "otf" else "ttf"
        val fileName = "$id.$ext"
        val destFile = File(dir, fileName)

        val streamed = context.contentResolver.openInputStream(uri)?.use { stream ->
            streamValidatedFontToFile(stream, destFile)
        } ?: false
        if (!streamed) {
            destFile.delete()
            return null
        }

        val loadedTypeface = runCatching { Typeface.createFromFile(destFile) }.getOrNull()
        if (loadedTypeface == null) {
            destFile.delete()
            return null
        }

        val cleanName = cleanFontDisplayName(rawDisplayName)
        val customFont = CustomFont(
            id = id,
            name = cleanName,
            lang = "multi",
            filePath = destFile.absolutePath
        )

        filePathMap[id] = destFile.absolutePath
        typefaceCache[id] = loadedTypeface
        fontFamilyCache[id] = FontFamily(Font(destFile, FontWeight.Normal))

        saveMetaEntry(baseDir, StoredCustomFontMeta(id = id, name = cleanName, lang = "multi", fileName = fileName))
        return customFont
    }

    internal fun streamValidatedFontToFile(
        inputStream: InputStream,
        destFile: File,
        maxBytes: Long = MAX_FONT_BYTES
    ): Boolean {
        destFile.parentFile?.mkdirs()
        val tmpFile = File(destFile.parentFile, "${destFile.name}.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        val header = ByteArray(12)
        var headerRead = 0
        while (headerRead < header.size) {
            val n = inputStream.read(header, headerRead, header.size - headerRead)
            if (n == -1) break
            headerRead += n
        }
        if (headerRead < 12 || !isValidFontHeader(header)) {
            return false
        }

        var totalBytes = headerRead.toLong()
        val ok = try {
            FileOutputStream(tmpFile).use { fos ->
                fos.write(header, 0, headerRead)
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    totalBytes += read
                    if (totalBytes > maxBytes) {
                        return@use false
                    }
                    fos.write(buffer, 0, read)
                }
                fos.flush()
                runCatching { if (fos.fd.valid()) fos.fd.sync() }
                true
            }
        } catch (_: Exception) {
            false
        }

        if (!ok) {
            tmpFile.delete()
            return false
        }
        if (destFile.exists()) destFile.delete()
        if (!tmpFile.renameTo(destFile)) {
            tmpFile.delete()
            return false
        }
        return true
    }

    private fun isValidFontHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        // TrueType 0x00010000
        if (b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) return true
        val tag = String(bytes, 0, 4, Charsets.US_ASCII)
        // OpenType 'OTTO', Apple TrueType 'true', TrueType Collection 'ttcf'
        return tag == "OTTO" || tag == "true" || tag == "ttcf"
    }

    fun isValidFontBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || bytes.size > MAX_FONT_BYTES) return false
        return isValidFontHeader(bytes)
    }

    fun cleanFontDisplayName(rawName: String?): String {
        val base = rawName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(Regex("\\.(ttf|otf|ttc)$", RegexOption.IGNORE_CASE), "")
            ?.replace('_', ' ')
            ?.replace('-', ' ')
            ?.trim()
            .orEmpty()
        return if (base.isBlank()) "Custom Font" else base.take(22)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun saveMetaEntry(baseDir: File, meta: StoredCustomFontMeta) {
        val idx = indexFile(baseDir)
        val existing = if (idx.exists()) {
            runCatching {
                json.decodeFromString<List<StoredCustomFontMeta>>(idx.readText(Charsets.UTF_8))
            }.getOrDefault(emptyList())
        } else emptyList()
        val updated = existing.filter { it.id != meta.id } + meta
        idx.writeText(json.encodeToString(updated), Charsets.UTF_8)
    }

    fun resolveFontFamily(fontId: String?): FontFamily? {
        if (fontId.isNullOrBlank()) return null
        return when (fontId) {
            BUILTIN_SERIF -> WikiFonts.serif
            BUILTIN_SANS -> WikiFonts.sans
            BUILTIN_MONO -> WikiFonts.mono
            else -> fontFamilyCache.getOrPut(fontId) {
                val path = filePathMap[fontId] ?: return null
                val file = File(path)
                if (!file.exists()) return null
                FontFamily(Font(file, FontWeight.Normal))
            }
        }
    }

    fun resolveTypeface(context: Context, fontId: String?): Typeface? {
        if (fontId.isNullOrBlank()) return null
        typefaceCache[fontId]?.let { return it }
        val loaded: Typeface? = when (fontId) {
            BUILTIN_SERIF -> runCatching { ResourcesCompat.getFont(context, R.font.cormorant_garamond) }.getOrNull()
            BUILTIN_SANS -> runCatching { ResourcesCompat.getFont(context, R.font.plus_jakarta_sans) }.getOrNull()
            BUILTIN_MONO -> runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_mono) }.getOrNull()
            else -> {
                val path = filePathMap[fontId]
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
                } else null
            }
        }
        if (loaded != null) typefaceCache[fontId] = loaded
        return loaded
    }

    fun clearAll(baseDir: File) {
        fontFamilyCache.clear()
        typefaceCache.clear()
        filePathMap.clear()
        runCatching { fontsDir(baseDir).deleteRecursively() }
    }
}
