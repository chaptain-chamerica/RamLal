package com.likkho.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.likkho.app.R

/** Wikipedia-style palette: same hex values as index.css (--wiki-*). */
data class WikiColors(
    val bg: Color, val surface: Color, val border: Color, val hairline: Color,
    val text: Color, val muted: Color, val link: Color, val isDark: Boolean,
) {
    val accent = Color(0xFF3366CC)
    val accentDark = Color(0xFF2A56B0)
    val danger = Color(0xFFB32424)
    val dangerDark = Color(0xFF941D1D)
    val success = Color(0xFF14866D)
}

val LightWiki = WikiColors(
    bg = Color(0xFFFFFFFF), surface = Color(0xFFF8F9FA), border = Color(0xFFA2A9B1), hairline = Color(0xFFEAECF0),
    text = Color(0xFF202122), muted = Color(0xFF54595D), link = Color(0xFF3366CC), isDark = false,
)
val DarkWiki = WikiColors(
    bg = Color(0xFF101418), surface = Color(0xFF1A1F24), border = Color(0xFF3A4047), hairline = Color(0xFF272D34),
    text = Color(0xFFEAECF0), muted = Color(0xFF9AA0A6), link = Color(0xFF6699FF), isDark = true,
)

val LocalWiki = staticCompositionLocalOf { LightWiki }

val CormorantGaramondFamily = FontFamily(Font(R.font.cormorant_garamond, FontWeight.Normal))
val LoraFamily = FontFamily(Font(R.font.lora, FontWeight.Normal))
val PlusJakartaSansFamily = FontFamily(Font(R.font.plus_jakarta_sans, FontWeight.Normal))
val IbmPlexMonoFamily = FontFamily(Font(R.font.ibm_plex_mono, FontWeight.Normal))
val NotoSerifDevanagariFamily = FontFamily(Font(R.font.noto_serif_devanagari, FontWeight.Normal))
val MuktaDevanagariFamily = FontFamily(Font(R.font.mukta, FontWeight.Normal))

object WikiFonts {
    val serif: FontFamily = CormorantGaramondFamily
    val prose: FontFamily = LoraFamily
    val sans: FontFamily = PlusJakartaSansFamily
    val mono: FontFamily = IbmPlexMonoFamily
    val devanagari: FontFamily = NotoSerifDevanagariFamily
    val devanagariSans: FontFamily = MuktaDevanagariFamily
}

fun serifStyle(size: TextUnit, color: Color, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = WikiFonts.serif, fontSize = size, fontWeight = weight, color = color)

fun sansStyle(size: TextUnit, color: Color, weight: FontWeight = FontWeight.Medium) =
    TextStyle(fontFamily = WikiFonts.sans, fontSize = size, fontWeight = weight, color = color)

fun monoStyle(size: TextUnit, color: Color, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = WikiFonts.mono, fontSize = size, fontWeight = weight, color = color)

fun proseStyle(size: TextUnit, color: Color, italic: Boolean = false) =
    TextStyle(
        fontFamily = WikiFonts.prose, fontSize = size, color = color,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    )

@Composable
fun LikkhoTheme(dark: Boolean, content: @Composable () -> Unit) {
    val w = if (dark) DarkWiki else LightWiki
    val scheme = if (dark) {
        darkColorScheme(
            primary = w.accent, onPrimary = Color.White, background = w.bg, onBackground = w.text,
            surface = w.surface, onSurface = w.text, outline = w.border, surfaceVariant = w.surface,
            onSurfaceVariant = w.muted,
        )
    } else {
        lightColorScheme(
            primary = w.accent, onPrimary = Color.White, background = w.bg, onBackground = w.text,
            surface = w.surface, onSurface = w.text, outline = w.border, surfaceVariant = w.surface,
            onSurfaceVariant = w.muted,
        )
    }
    CompositionLocalProvider(LocalWiki provides w) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun RamLalTheme(dark: Boolean, content: @Composable () -> Unit) = LikkhoTheme(dark, content)

@Composable
fun wiki(): WikiColors = LocalWiki.current

fun parseHex(hex: String): Color? = try {
    if (hex == "default" || hex == "transparent") null else Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) { null }

data class StoragePalette(
    val diaries: Color,
    val photos: Color,
    val audio: Color,
    val videos: Color,
    val backups: Color,
    val temporary: Color,
    val unused: Color,
    val damaged: Color,
)

@Composable
fun storagePalette(): StoragePalette {
    val w = wiki()
    return if (w.isDark) {
        StoragePalette(
            diaries = Color(0xFF6699FF),
            photos = Color(0xFF2AA189),
            audio = Color(0xFF9F7AEA),
            videos = Color(0xFFE09F3E),
            backups = Color(0xFF38BDF8),
            temporary = w.muted,
            unused = Color(0xFFFB923C),
            damaged = Color(0xFFE05252),
        )
    } else {
        StoragePalette(
            diaries = w.accent,
            photos = w.success,
            audio = Color(0xFF6B4BA1),
            videos = Color(0xFFAC6600),
            backups = Color(0xFF0E7490),
            temporary = w.muted,
            unused = Color(0xFFD97706),
            damaged = w.danger,
        )
    }
}
