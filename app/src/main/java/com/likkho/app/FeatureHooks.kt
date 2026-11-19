package com.likkho.app

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
data class RestoreResult(
    val logs: List<DiaryLog>,
    val skippedMediaCount: Int = 0,
    val failedDiaries: List<com.likkho.app.storage.FailedDiaryEntry> = emptyList()
)

interface FeatureHooks {
    // ---- App update (GitHub releases) ----
    suspend fun checkForUpdate(manual: Boolean): UpdateCheck
    fun downloadApk(info: AppUpdateInfo)                       // "Download & Install APK"
    fun openUrl(url: String)                                   // "GitHub Page", legal links

    // ---- Home: 3-dot menu ----
    suspend fun shareDiaryAsArchive(log: DiaryLog, passphrase: String = ""): String     // returns success message
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
    fun persistLogs(
        logs: List<DiaryLog>,
        obsoleteMediaIds: Set<String> = emptySet(),
        onResult: (Result<Unit>) -> Unit = {}
    )
    fun deleteDiary(id: String, onResult: (Result<Unit>) -> Unit = {})
    fun deleteUnreferencedMedia(candidateIds: Set<String>, onResult: (Result<Unit>) -> Unit = {})
    fun scheduleReminder(log: DiaryLog, at: Long?, onResult: (Result<Unit>) -> Unit = {})
    fun cancelReminder(diaryId: String) {}
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

