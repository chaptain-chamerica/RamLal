package com.likkho.diary

import kotlinx.coroutines.delay

/**
 * =====================================================================================
 *  FEATURE HOOKS  —  THE ONLY PLACE STUDIO AI HAS TO FILL IN
 * =====================================================================================
 *  Saari UI/UX screens (Compose) sirf is interface ko call karti hain. `StubFeatureHooks`
 *  fake delay + fake result deta hai taaki har button ka UI behaviour (loading, success,
 *  error banner) bina real feature ke bhi dikhe.
 *
 *  Studio AI: `RealFeatureHooks : FeatureHooks` banao (encryption, backup, Wikipedia,
 *  recorder, picker, reminders...) aur AppViewModel me `hooks = RealFeatureHooks(...)` kar do.
 *  UI code ko chhune ki zaroorat nahi.
 * =====================================================================================
 */

class IncorrectKeyException : Exception("Incorrect Encryption Key.")

sealed class UpdateCheck {
    data class UpToDate(val version: String) : UpdateCheck()
    data class Available(val info: AppUpdateInfo) : UpdateCheck()
    object Failed : UpdateCheck()
    object Offline : UpdateCheck()
}

data class BackupResult(val failedMediaCount: Int = 0)
data class RestoreResult(val logs: List<DiaryLog>, val skippedMediaCount: Int = 0)

interface FeatureHooks {
    // ---- App update (GitHub releases) ----
    suspend fun checkForUpdate(manual: Boolean): UpdateCheck
    fun downloadApk(info: AppUpdateInfo)                       // "Download & Install APK"
    fun openUrl(url: String)                                   // "GitHub Page", legal links

    // ---- Home: 3-dot menu ----
    suspend fun shareDiaryAsArchive(log: DiaryLog): String     // returns success message
    suspend fun exportDiary(log: DiaryLog, format: ExportFormat): String

    // ---- Settings ----
    fun authenticateBiometric(forUnlock: Boolean, onResult: (success: Boolean, message: String?) -> Unit)
    fun cancelBiometricPrompt()
    suspend fun exportAllDataZip(): String
    suspend fun clearAllData()
    fun loadSavedWords(vault: VaultMode): List<WordAnalysisRecord>
    fun deleteSavedWord(word: String, vault: VaultMode): List<WordAnalysisRecord>
    fun loadPasswords(vault: VaultMode): List<SavedPassword>
    fun persistPasswords(vault: VaultMode, items: List<SavedPassword>)

    // ---- Backup / Restore ----
    suspend fun hashPassphrase(passphrase: String): String
    suspend fun performBackup(passphrase: String): BackupResult
    suspend fun performRestore(passphrase: String): RestoreResult      // throws IncorrectKeyException
    suspend fun openBackupVault(passphrase: String): List<DiaryLog>    // throws IncorrectKeyException
    suspend fun deleteFromBackup(log: DiaryLog)

    // ---- Workspace / canvas ----
    fun persistLogs(logs: List<DiaryLog>)
    fun scheduleReminder(log: DiaryLog, at: Long?)
    suspend fun pickImage(): CanvasImage?                      // media picker "Add Image"
    suspend fun pickAudio(): CanvasAudio?                      // media picker "Attach Audio"
    suspend fun pickPfpImage(): String?                        // "Browse Image" in PFP studio
    suspend fun pickBackgroundImage(): String?
    suspend fun pickFont(lang: String): CustomFont?            // "Add .ttf"
    suspend fun importDocument(): String                       // TXT, MD, RTF, ... returns status text
    fun startVoiceRecording()
    fun stopVoiceRecording(): CanvasAudio?
    suspend fun wikipediaSuggest(query: String, lang: String): List<WikiSuggestion>
    suspend fun wikipediaInsert(title: String, lang: String, withImages: Boolean): String
    suspend fun analyzeWord(word: String): WordAnalysisRecord
    fun applyFormat(action: FormatAction)
    fun applyTextColor(hex: String)            // "default" = reset
    fun applyHighlight(hex: String)            // "transparent" = reset
    fun applyFontSizePx(px: Int?)              // null = disable
    fun applyFont(fontId: String?)             // null = default
    fun applyLink(url: String)
    fun applySpoiler(passcode: String)
    fun insertLatex(latex: String)
    fun updateLatex(latex: String?)            // null = delete formula
}

/** Fake implementation: delays + demo data so every UI state can be seen without real features. */
class StubFeatureHooks : FeatureHooks {
    private var passwords = mutableListOf<SavedPassword>()
    private var savedWords = mutableListOf(
        WordAnalysisRecord(
            word = "Serendipity", phoneticTranscription = "/ˌsɛrənˈdɪpɪti/", syllableMetrics = "5 syllables",
            perfectRhymes = listOf("—"), nearSlantRhymes = listOf("identity"), meterAndRhythmScore = "Dactylic-ish",
            definitions = listOf(WordDefinition("noun", "The occurrence of events by chance in a happy way.", "A fortunate serendipity.")),
            synonyms = listOf("luck", "fluke"), antonyms = listOf("misfortune"), partOfSpeech = listOf("noun"),
            etymologyAndOrigin = "Coined by Horace Walpole, 1754.", formalityAndRegister = "Neutral",
        ),
    )

    override suspend fun checkForUpdate(manual: Boolean): UpdateCheck {
        delay(900)
        return if (manual) UpdateCheck.UpToDate("1.5.0") else UpdateCheck.UpToDate("1.5.0")
    }
    override fun downloadApk(info: AppUpdateInfo) {}
    override fun openUrl(url: String) {}

    override suspend fun shareDiaryAsArchive(log: DiaryLog): String { delay(1200); return "Shared \"${log.heading}\" as .ramlal archive." }
    override suspend fun exportDiary(log: DiaryLog, format: ExportFormat): String {
        delay(1200); return "Exported \"${log.heading}\" as ${format.label} to Downloads/RamLal."
    }

    override fun authenticateBiometric(forUnlock: Boolean, onResult: (Boolean, String?) -> Unit) {
        // demo: enabling works; unlocking by biometric is not wired yet so lock screen stays usable with PIN
        if (forUnlock) onResult(false, "Use your PIN to unlock.") else onResult(true, null)
    }
    override fun cancelBiometricPrompt() {}
    override suspend fun exportAllDataZip(): String { delay(1500); return "All data exported to Downloads/RamLal (ZIP)." }
    override suspend fun clearAllData() { delay(800) }
    override fun loadSavedWords(vault: VaultMode) = savedWords.sortedBy { it.word.lowercase() }
    override fun deleteSavedWord(word: String, vault: VaultMode): List<WordAnalysisRecord> {
        savedWords.removeAll { it.word.equals(word, true) }; return loadSavedWords(vault)
    }
    override fun loadPasswords(vault: VaultMode) = passwords.toList()
    override fun persistPasswords(vault: VaultMode, items: List<SavedPassword>) { passwords = items.toMutableList() }

    override suspend fun hashPassphrase(passphrase: String): String = "demo-hash-" + passphrase.hashCode()
    override suspend fun performBackup(passphrase: String): BackupResult { delay(1500); return BackupResult(0) }
    override suspend fun performRestore(passphrase: String): RestoreResult { delay(1500); return RestoreResult(emptyList(), 0) }
    override suspend fun openBackupVault(passphrase: String): List<DiaryLog> { delay(900); return SampleData.backedUpLogs() }
    override suspend fun deleteFromBackup(log: DiaryLog) { delay(600) }

    override fun persistLogs(logs: List<DiaryLog>) {}
    override fun scheduleReminder(log: DiaryLog, at: Long?) {}
    override suspend fun pickImage(): CanvasImage? {
        delay(300)
        val n = System.currentTimeMillis()
        return CanvasImage(id = "img_$n", source = "", x = 40f, y = 160f, width = 160f, height = 120f, sizeBytes = 2_400_000)
    }
    override suspend fun pickAudio(): CanvasAudio? {
        delay(300)
        val n = System.currentTimeMillis()
        return CanvasAudio(id = "aud_$n", name = "Audio ($n).mp3", format = "mp3", durationSec = 74, y = 220f)
    }
    override suspend fun pickPfpImage(): String? = null
    override suspend fun pickBackgroundImage(): String? = "demo-bg"
    override suspend fun pickFont(lang: String): CustomFont? =
        CustomFont("font_${System.currentTimeMillis()}", if (lang == "hi") "Demo Hindi Font" else "Demo English Font", lang)
    override suspend fun importDocument(): String { delay(800); return "Document imported to canvas." }
    override fun startVoiceRecording() {}
    override fun stopVoiceRecording(): CanvasAudio? {
        val n = System.currentTimeMillis()
        return CanvasAudio(id = "rec_$n", name = "Voice (${fmtDateTimeShort(n)}).wav", durationSec = 12, y = 200f)
    }
    override suspend fun wikipediaSuggest(query: String, lang: String): List<WikiSuggestion> {
        delay(400)
        if (query.isBlank()) return emptyList()
        return listOf(
            WikiSuggestion(query.trim().replaceFirstChar { it.uppercase() }, "Demo suggestion"),
            WikiSuggestion(query.trim().replaceFirstChar { it.uppercase() } + " (disambiguation)", "Demo suggestion"),
            WikiSuggestion("History of " + query.trim(), "Demo suggestion"),
        )
    }
    override suspend fun wikipediaInsert(title: String, lang: String, withImages: Boolean): String {
        delay(1000); return "Inserted \"$title\" from Wikipedia."
    }
    override suspend fun analyzeWord(word: String): WordAnalysisRecord {
        delay(1000)
        return WordAnalysisRecord(
            word = word, phoneticTranscription = "/demo/", syllableMetrics = "—",
            definitions = listOf(WordDefinition("noun", "Demo definition for \"$word\" (Studio AI will fill real analysis).")),
            synonyms = listOf("demo", "sample"), partOfSpeech = listOf("noun"),
        )
    }
    override fun applyFormat(action: FormatAction) {}
    override fun applyTextColor(hex: String) {}
    override fun applyHighlight(hex: String) {}
    override fun applyFontSizePx(px: Int?) {}
    override fun applyFont(fontId: String?) {}
    override fun applyLink(url: String) {}
    override fun applySpoiler(passcode: String) {}
    override fun insertLatex(latex: String) {}
    override fun updateLatex(latex: String?) {}
}
