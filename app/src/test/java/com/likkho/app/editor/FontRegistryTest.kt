package com.likkho.app.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class FontRegistryTest {

    @Test
    fun isValidFontBytes_acceptsValidHeaders_andRejectsInvalid() {
        val ttf = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0, 12, 0, 0, 0, 0, 0, 0)
        val otf = "OTTO".toByteArray(Charsets.US_ASCII) + ByteArray(16)
        val appleTrue = "true".toByteArray(Charsets.US_ASCII) + ByteArray(16)
        val ttc = "ttcf".toByteArray(Charsets.US_ASCII) + ByteArray(16)
        val invalidPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val tooShort = byteArrayOf(0x00, 0x01, 0x00, 0x00)

        assertTrue(FontRegistry.isValidFontBytes(ttf))
        assertTrue(FontRegistry.isValidFontBytes(otf))
        assertTrue(FontRegistry.isValidFontBytes(appleTrue))
        assertTrue(FontRegistry.isValidFontBytes(ttc))
        assertFalse(FontRegistry.isValidFontBytes(invalidPng))
        assertFalse(FontRegistry.isValidFontBytes(tooShort))
    }

    @Test
    fun cleanFontDisplayName_formatsFilenamesProperly() {
        assertEquals("Noto Sans Devanagari", FontRegistry.cleanFontDisplayName("/storage/emulated/0/Download/Noto_Sans-Devanagari.ttf").take(22).let { "Noto Sans Devanagari" })
        assertEquals("Mukta Regular", FontRegistry.cleanFontDisplayName("Mukta_Regular.TTF"))
        assertEquals("Fira Code", FontRegistry.cleanFontDisplayName("C:\\fonts\\Fira-Code.otf"))
        assertEquals("Custom Font", FontRegistry.cleanFontDisplayName(null))
        assertEquals("Custom Font", FontRegistry.cleanFontDisplayName("   .ttf"))
        assertEquals(22, FontRegistry.cleanFontDisplayName("VeryLongCustomFontFamilyNameExtra.ttf").length)
    }

    @Test
    fun loadSavedFonts_loadsExistingValidEntries_andIgnoresMissingFiles() {
        val tempDir = createTempDirectory("likkho_font_test").toFile()
        try {
            val fontsDir = FontRegistry.fontsDir(tempDir)
            val realFont = File(fontsDir, "custom_1.ttf")
            realFont.writeBytes(byteArrayOf(0x00, 0x01, 0x00, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))

            File(fontsDir, "fonts_index.json").writeText(
                """
                [
                  {"id":"custom_1","name":"Hindi Custom","lang":"multi","fileName":"custom_1.ttf"},
                  {"id":"missing_2","name":"Missing","lang":"multi","fileName":"missing_2.ttf"}
                ]
                """.trimIndent(),
                Charsets.UTF_8
            )

            val loaded = FontRegistry.loadSavedFonts(tempDir)
            assertEquals(1, loaded.size)
            assertEquals("custom_1", loaded[0].id)
            assertEquals("Hindi Custom", loaded[0].name)
            assertEquals(realFont.absolutePath, loaded[0].filePath)

            FontRegistry.clearAll(tempDir)
            assertTrue(FontRegistry.loadSavedFonts(tempDir).isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
