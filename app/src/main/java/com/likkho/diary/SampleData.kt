package com.likkho.diary

/** Demo diaries so Home screen shows every state (pinned, locked, reminder, plain). Delete when real storage exists. */
object SampleData {
    private const val DAY = 86_400_000L
    fun starterLogs(): List<DiaryLog> {
        val now = System.currentTimeMillis()
        return listOf(
            DiaryLog(
                id = "log_starter_welcome_guide_v1", heading = "Welcome to RamLal",
                contentHtml = "RamLal is an elegant writing and diary app. Tap Create to write your first entry.",
                plainPreview = "RamLal is an elegant writing and diary app. Tap Create to write your first entry.",
                createdAt = now - 3 * DAY, updatedAt = now - 3 * DAY, pinned = true,
            ),
            DiaryLog(
                id = "log_demo_locked", heading = "Private thoughts", plainPreview = "hidden",
                createdAt = now - 2 * DAY, updatedAt = now - DAY, diaryLockPin = "1234",
            ),
            DiaryLog(
                id = "log_demo_reminder", heading = "Meeting notes",
                contentHtml = "Discuss release plan and APK testing.", plainPreview = "Discuss release plan and APK testing.",
                createdAt = now - DAY, updatedAt = now - DAY / 2, reminderAt = now + DAY / 3,
            ),
            DiaryLog(
                id = "log_demo_plain", heading = "आज का दिन",
                contentHtml = "आज बहुत कुछ सीखा।", plainPreview = "आज बहुत कुछ सीखा।",
                createdAt = now - 5 * 3_600_000L, updatedAt = now - 5 * 3_600_000L,
            ),
        )
    }

    fun backedUpLogs(): List<DiaryLog> {
        val now = System.currentTimeMillis()
        return listOf(
            DiaryLog(id = "bk1", heading = "Old trip diary", contentHtml = "Paragraph one of the old diary.", plainPreview = "Paragraph one of the old diary.", createdAt = now - 40 * DAY, updatedAt = now - 40 * DAY, pinned = true,
                canvasImages = listOf(CanvasImage("i1")), audioAttachments = listOf(CanvasAudio("a1", "Voice note", durationSec = 31))),
            DiaryLog(id = "bk2", heading = "Secret one", contentHtml = "Locked content.", plainPreview = "Locked content.", createdAt = now - 30 * DAY, updatedAt = now - 30 * DAY, diaryLockPin = "1234"),
        )
    }
}
