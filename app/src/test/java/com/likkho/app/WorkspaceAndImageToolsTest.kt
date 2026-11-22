package com.likkho.app

import com.likkho.app.storage.ClockProvider
import com.likkho.app.storage.InMemoryKeyStoreProvider
import com.likkho.app.storage.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceAndImageToolsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var vaultsDir: File
    private lateinit var cacheDir: File
    private lateinit var vaultManager: VaultManager
    private lateinit var hooks: RealFeatureHooks
    private val testDispatcher = UnconfinedTestDispatcher()

    private val clock = object : ClockProvider {
        override fun elapsedRealtimeMs(): Long = 10_000L
        override fun wallClockMs(): Long = 1_700_000_000_000L
    }

    @Before
    fun setUp() {
        filesDir = tempFolder.newFolder("app_files")
        vaultsDir = File(filesDir, "vaults")
        cacheDir = tempFolder.newFolder("app_cache")
        vaultManager = VaultManager(
            baseDir = vaultsDir,
            keyStoreProvider = InMemoryKeyStoreProvider(),
            dispatcher = testDispatcher,
            pbkdf2Iterations = 5000,
            clockProvider = clock
        )
        hooks = RealFeatureHooks(
            context = null,
            vaultManager = vaultManager,
            dispatcher = testDispatcher,
            scope = CoroutineScope(testDispatcher),
            baseStorageDir = filesDir,
            cacheStorageDir = cacheDir
        )
    }

    // =========================================================================
    // 1. Auto-Save on Canvas Close + Empty-Check Rules
    // =========================================================================

    @Test
    fun newDiary_untouched_doesNotAutoSave() {
        val action = evaluateWorkspaceExitAction(
            isReading = false,
            baseline = null,
            baselineHtml = "",
            currentHeading = "",
            currentContentHtml = "",
            currentPlainText = "",
            hasDividerSpan = false,
            pfp = null,
            bgSrc = null,
            bgOpacity = 0.35f,
            images = emptyList(),
            audios = emptyList(),
            videos = emptyList(),
            reminderAt = null,
            lockPin = null
        )
        assertEquals(WorkspaceExitAction.NO_SAVE_EXIT, action)
    }

    @Test
    fun newDiary_onlyWhitespace_doesNotAutoSave() {
        val action = evaluateWorkspaceExitAction(
            isReading = false,
            baseline = null,
            baselineHtml = "",
            currentHeading = "   \t ",
            currentContentHtml = "<p>   </p>",
            currentPlainText = "   \n  \u200B ",
            hasDividerSpan = false,
            pfp = null,
            bgSrc = "   ",
            bgOpacity = 0.35f,
            images = emptyList(),
            audios = emptyList(),
            videos = emptyList(),
            reminderAt = null,
            lockPin = ""
        )
        assertEquals(WorkspaceExitAction.NO_SAVE_EXIT, action)
    }

    @Test
    fun newDiary_withAnyEntry_autoSavesWithoutClickingSave() {
        // 1. Heading only
        assertEquals(
            WorkspaceExitAction.AUTO_SAVE_AND_EXIT,
            evaluateWorkspaceExitAction(
                isReading = false, baseline = null, baselineHtml = "",
                currentHeading = "Morning Thoughts", currentContentHtml = "", currentPlainText = "",
                hasDividerSpan = false, pfp = null, bgSrc = null, bgOpacity = 0.35f,
                images = emptyList(), audios = emptyList(), videos = emptyList(),
                reminderAt = null, lockPin = null
            )
        )
        // 2. Body text only
        assertEquals(
            WorkspaceExitAction.AUTO_SAVE_AND_EXIT,
            evaluateWorkspaceExitAction(
                isReading = false, baseline = null, baselineHtml = "",
                currentHeading = "", currentContentHtml = "<p>Hello world</p>", currentPlainText = "Hello world",
                hasDividerSpan = false, pfp = null, bgSrc = null, bgOpacity = 0.35f,
                images = emptyList(), audios = emptyList(), videos = emptyList(),
                reminderAt = null, lockPin = null
            )
        )
        // 3. Divider only
        assertEquals(
            WorkspaceExitAction.AUTO_SAVE_AND_EXIT,
            evaluateWorkspaceExitAction(
                isReading = false, baseline = null, baselineHtml = "",
                currentHeading = "", currentContentHtml = "<hr/>", currentPlainText = "\u200B",
                hasDividerSpan = true, pfp = null, bgSrc = null, bgOpacity = 0.35f,
                images = emptyList(), audios = emptyList(), videos = emptyList(),
                reminderAt = null, lockPin = null
            )
        )
        // 4. Image only
        assertEquals(
            WorkspaceExitAction.AUTO_SAVE_AND_EXIT,
            evaluateWorkspaceExitAction(
                isReading = false, baseline = null, baselineHtml = "",
                currentHeading = "", currentContentHtml = "", currentPlainText = "",
                hasDividerSpan = false, pfp = null, bgSrc = null, bgOpacity = 0.35f,
                images = listOf(CanvasImage(id = "img_1", source = "med_1")),
                audios = emptyList(), videos = emptyList(),
                reminderAt = null, lockPin = null
            )
        )
        // 5. Audio / Video / PFP / Background / Reminder / Lock PIN
        assertEquals(
            WorkspaceExitAction.AUTO_SAVE_AND_EXIT,
            evaluateWorkspaceExitAction(
                isReading = false, baseline = null, baselineHtml = "",
                currentHeading = "", currentContentHtml = "", currentPlainText = "",
                hasDividerSpan = false, pfp = Pfp(text = "AB"), bgSrc = null, bgOpacity = 0.35f,
                images = emptyList(), audios = emptyList(), videos = emptyList(),
                reminderAt = null, lockPin = null
            )
        )
    }

    @Test
    fun newDiary_enteredThenClearedBeforeExit_doesNotAutoSaveAndCleansMedia() {
        val sessionImported = mutableListOf("med_temp_img")
        var cleanedUp: Set<String> = emptySet()

        // User clears heading, text, and deletes image before exiting
        val action = evaluateWorkspaceExitAction(
            isReading = false,
            baseline = null,
            baselineHtml = "",
            currentHeading = "",
            currentContentHtml = "",
            currentPlainText = "",
            hasDividerSpan = false,
            pfp = null,
            bgSrc = null,
            bgOpacity = 0.35f,
            images = emptyList(),
            audios = emptyList(),
            videos = emptyList(),
            reminderAt = null,
            lockPin = null
        )
        assertEquals(WorkspaceExitAction.NO_SAVE_EXIT, action)

        // When exiting without save, abandoned session media must be deleted
        cleanupAbandonedSessionMedia(sessionImported, emptySet()) { cleanedUp = it }
        assertEquals(setOf("med_temp_img"), cleanedUp)
        assertTrue(sessionImported.isEmpty())
    }

    @Test
    fun existingDiary_readingOrUnmodifiedOrClearedToEmpty_skipsAutoSave_whileEditedAutoSaves() {
        val baseline = DiaryLog(
            id = "log_1",
            heading = "Day 1",
            contentHtml = "<p>Original</p>",
            plainPreview = "Original",
            canvasImages = listOf(CanvasImage(id = "img1", source = "med_img1"))
        )

        // Reading mode -> no save
        assertEquals(
            WorkspaceExitAction.NO_SAVE_EXIT,
            evaluateWorkspaceExitAction(
                isReading = true, baseline = baseline, baselineHtml = "<p>Original</p>",
                currentHeading = "Day 1", currentContentHtml = "<p>Original</p>", currentPlainText = "Original",
                hasDividerSpan = false, pfp = null, bgSrc = null, bgOpacity = 0.35f,
                images = baseline.canvasImages, audios = emptyList(), videos = emptyList(),
                reminderAt = null, lockPin = null
            )
        )

        // Edit mode, unchanged -> no save
        assertEquals(
            WorkspaceExitAction.NO_SAVE_EXIT,
            evaluateWorkspaceExitAction(
                isReading = false, baseline = baseline, baselineHtml = "<p>Original</p>",
                currentHeading = "Day 1", currentContentHtml = "<p>Original</p>", currentPlainText = "Original",
                hasDividerSpan = false, pfp = null, bgSrc = null, bgOpacity = 0.35f,
                images = baseline.canvasImages, audios = emptyList(), videos = emptyList(),
                reminderAt = null, lockPin = null
            )
        )

        // Edit mode, edited -> auto-save
        assertEquals(
            WorkspaceExitAction.AUTO_SAVE_AND_EXIT,
            evaluateWorkspaceExitAction(
                isReading = false, baseline = baseline, baselineHtml = "<p>Original</p>",
                currentHeading = "Day 1", currentContentHtml = "<p>Updated text</p>", currentPlainText = "Updated text",
                hasDividerSpan = false, pfp = null, bgSrc = null, bgOpacity = 0.35f,
                images = baseline.canvasImages, audios = emptyList(), videos = emptyList(),
                reminderAt = null, lockPin = null
            )
        )

        // Edit mode, cleared completely to empty -> does NOT save empty diary
        assertEquals(
            WorkspaceExitAction.NO_SAVE_EXIT,
            evaluateWorkspaceExitAction(
                isReading = false, baseline = baseline, baselineHtml = "<p>Original</p>",
                currentHeading = "", currentContentHtml = "", currentPlainText = "",
                hasDividerSpan = false, pfp = null, bgSrc = null, bgOpacity = 0.35f,
                images = emptyList(), audios = emptyList(), videos = emptyList(),
                reminderAt = null, lockPin = null
            )
        )
    }

    // =========================================================================
    // 2. Smooth Dragging Math & Non-Recomposition Bounds Expansion
    // =========================================================================

    @Test
    fun dragBoundsExpansion_onlyTriggersNearCanvasEdge() {
        // Normal drag well inside 400x800 canvas -> returns exact same bounds (no recomposition!)
        val same1 = computeExpandedDragBounds(
            itemRightDp = 260f,
            itemBottomDp = 420f,
            currentCanvasWidthDp = 400f,
            currentCanvasHeightDp = 800f,
            currentBounds = null
        )
        assertNull(same1)

        // Approaching right edge (360 > 400 - 48 = 352) -> expands by +320dp chunk
        val expandedRight = computeExpandedDragBounds(
            itemRightDp = 360f,
            itemBottomDp = 420f,
            currentCanvasWidthDp = 400f,
            currentCanvasHeightDp = 800f,
            currentBounds = null
        )
        assertEquals(680f to 0f, expandedRight)

        // Subsequent drag inside expanded 920dp canvas -> stays unchanged (no per-pixel recomposition)
        val same2 = computeExpandedDragBounds(
            itemRightDp = 361f,
            itemBottomDp = 425f,
            currentCanvasWidthDp = 920f,
            currentCanvasHeightDp = 800f,
            currentBounds = expandedRight
        )
        assertEquals(expandedRight, same2)
    }

    @Test
    fun autoScrollStep_zeroInsideSafeZone_andClampsLeftTopWhenScrollZero() {
        assertEquals(0f, computeAutoScrollStepPx(200f, 1000f, 100, 120f, 36f), 0.001f)
        // Left edge does not negative-scroll if already at 0
        assertEquals(0f, computeAutoScrollStepPx(20f, 1000f, 0, 120f, 36f), 0.001f)
        // Left edge scrolls negatively when scrolled > 0
        assertTrue(computeAutoScrollStepPx(20f, 1000f, 50, 120f, 36f) < 0f)
        // Right edge scrolls positively
        assertTrue(computeAutoScrollStepPx(960f, 1000f, 0, 120f, 36f) > 0f)
    }

    // =========================================================================
    // 3. Image Editor Engine Geometry, Presets & Encrypted Media Replacement
    // =========================================================================

    @Test
    fun cropBounds_aspectRatiosZoomAndPanClampCorrectly() {
        // 1:1 centered crop on 2000x1000 landscape photo
        val square = ImageEditorEngine.computeCropBounds(2000, 1000, "1:1", zoom = 1f, panX = 0f, panY = 0f)
        assertEquals(1000, square.width)
        assertEquals(1000, square.height)
        assertEquals(500, square.left)
        assertEquals(0, square.top)

        // 2x zoom with full right pan (+1f) stays strictly inside image width
        val panned = ImageEditorEngine.computeCropBounds(2000, 1000, "1:1", zoom = 2f, panX = 1f, panY = 1f)
        assertEquals(500, panned.width)
        assertEquals(500, panned.height)
        assertEquals(1500, panned.left)
        assertEquals(500, panned.top)

        // 16:9 on square 1000x1000 photo
        val wide = ImageEditorEngine.computeCropBounds(1000, 1000, "16:9", zoom = 1f, panX = 0f, panY = 0f)
        assertEquals(1000, wide.width)
        assertEquals(563, wide.height)
        assertTrue(wide.top in 0..(1000 - wide.height))
    }

    @Test
    fun scaledDimensionsAndCanvasCardResize_preserveBounds() {
        assertEquals(1600 to 800, ImageEditorEngine.computeScaledDimensions(3200, 1600, 1600))
        assertEquals(800 to 600, ImageEditorEngine.computeScaledDimensions(800, 600, 1600))
        assertEquals(3200 to 1600, ImageEditorEngine.computeScaledDimensions(3200, 1600, null))

        // Rotating 200x100 landscape canvas card -> portrait 1000x2000 pixel output swaps proportions smoothly
        val (newW, newH) = ImageEditorEngine.computeUpdatedCanvasSize(200f, 100f, 1000, 2000)
        assertTrue("Expected tall portrait card but got $newW x $newH", newH > newW)
        assertEquals(0.5f, newW / newH, 0.05f)
    }

    @Test
    fun imageEditParams_presetsAndDirtyDetectionWork() {
        val base = ImageEditParams()
        assertFalse(base.hasPixelOrEncodingChanges())
        assertFalse(base.withCompressionPreset(CompressionPreset.BALANCED).let { !it.compress })
        assertTrue(base.withCompressionPreset(CompressionPreset.TINY).hasPixelOrEncodingChanges())
        assertEquals("JPEG", base.withCompressionPreset(CompressionPreset.TINY).format)
        assertEquals(50, base.withCompressionPreset(CompressionPreset.TINY).quality)
        assertEquals(800, base.withCompressionPreset(CompressionPreset.TINY).maxDimension)
        assertEquals(50, ImageEditorEngine.computeSavingsPercent(1000L, 500L))
        assertEquals(270, ImageEditParams(rotationDegrees = -90).normalizedRotation())
    }

    @Test
    fun replacingEditedImageInVault_persistsNewMediaAndDeletesObsoleteOriginal() = runTest(testDispatcher) {
        vaultManager.ensurePrimaryVaultInitialized()
        val origBytes = "original-image-bytes-123456".toByteArray()
        val editedBytes = "compressed-webp-bytes".toByteArray()

        val origId = "med_orig_1"
        val editedId = "med_edit_2"
        vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMedia(origId, ByteArrayInputStream(origBytes), dek, origBytes.size.toLong())
        }
        val initialDiary = DiaryLog(
            id = "log_edit_test",
            heading = "Photo Note",
            contentHtml = "<p>Nice pic</p>",
            canvasImages = listOf(CanvasImage(id = "card_1", source = origId, sizeBytes = origBytes.size.toLong()))
        )

        var saveSuccess = false
        hooks.persistLogs(listOf(initialDiary), emptySet()) { res -> saveSuccess = res.isSuccess }
        assertTrue(saveSuccess)
        assertTrue(vaultManager.activeMediaRepository().exists(origId))

        // Simulate editing image via Image Studio: new encrypted media written, card updated to editedId, old origId passed as obsolete
        vaultManager.withActiveDek { dek ->
            vaultManager.activeMediaRepository().importMediaBytes(editedId, editedBytes, dek)
        }
        val updatedDiary = initialDiary.copy(
            updatedAt = initialDiary.updatedAt + 1000L,
            canvasImages = listOf(CanvasImage(id = "card_1", source = editedId, sizeBytes = editedBytes.size.toLong()))
        )
        var replaceSuccess = false
        hooks.persistLogs(listOf(updatedDiary), setOf(origId)) { res -> replaceSuccess = res.isSuccess }
        assertTrue(replaceSuccess)

        val repo = vaultManager.activeMediaRepository()
        assertTrue("Edited image file should remain on disk", repo.exists(editedId))
        assertFalse("Replaced original unreferenced image should be deleted", repo.exists(origId))
        vaultManager.withActiveDek { dek ->
            assertArrayEquals(editedBytes, repo.readDecryptedBytes(editedId, dek))
        }
    }
}
