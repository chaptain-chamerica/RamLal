package com.likkho.app

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.likkho.app.storage.InMemoryKeyStoreProvider
import com.likkho.app.storage.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.system.measureNanoTime

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class WorkspacePerfBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: AppViewModel
    private lateinit var probe: WorkspacePerfProbe

    private data class FrameStats(
        val label: String,
        val frameTimesMs: List<Double>,
        val workspaceRecompositions: Int,
        val headerRecompositions: Int,
        val toolbarRecompositions: Int,
        val canvasBoxRecompositions: Int,
        val canvasBoxLayouts: Int,
        val mediaItemRecompositions: Int,
        val mediaItemLayouts: Int,
        val textFieldRecompositions: Int,
        val textFieldLayouts: Int,
        val bitmapLoadCalls: Int,
        val thumbnailLoadCalls: Int,
    ) {
        val totalFrames: Int get() = frameTimesMs.size
        val slowFrames: Int get() = frameTimesMs.count { it > 16.67 }
        val jankPct: Double get() = if (frameTimesMs.isEmpty()) 0.0 else (slowFrames * 100.0) / frameTimesMs.size
        val p50Ms: Double get() = percentile(50.0)
        val p95Ms: Double get() = percentile(95.0)

        private fun percentile(p: Double): Double {
            if (frameTimesMs.isEmpty()) return 0.0
            val sorted = frameTimesMs.sorted()
            val idx = ceil((p / 100.0) * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[idx]
        }

        fun summary(): String = buildString {
            appendLine("[$label]")
            appendLine("  frames=$totalFrames slow(>16.67ms)=$slowFrames jank=${"%.1f".format(jankPct)}% p50=${"%.2f".format(p50Ms)}ms p95=${"%.2f".format(p95Ms)}ms")
            appendLine("  recomp: workspace=$workspaceRecompositions header=$headerRecompositions toolbar=$toolbarRecompositions canvasBox=$canvasBoxRecompositions media=$mediaItemRecompositions textField=$textFieldRecompositions")
            appendLine("  layout: canvasBox=$canvasBoxLayouts media=$mediaItemLayouts textField=$textFieldLayouts")
            appendLine("  decode: bitmapLoads=$bitmapLoadCalls thumbLoads=$thumbnailLoadCalls")
        }
    }

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vaultsDir = tempFolder.newFolder("perf_vaults")
        val filesDir = tempFolder.newFolder("perf_files")
        val cacheDir = tempFolder.newFolder("perf_cache")
        val vaultManager = VaultManager(
            baseDir = vaultsDir,
            keyStoreProvider = InMemoryKeyStoreProvider(),
            dispatcher = Dispatchers.Unconfined,
            pbkdf2Iterations = 5000
        )
        vaultManager.ensurePrimaryVaultInitialized()
        vm = AppViewModel(
            application = app,
            vaultManager = vaultManager,
            hooksFactory = { _, v ->
                RealFeatureHooks(
                    context = app,
                    vaultManager = v,
                    dispatcher = Dispatchers.Unconfined,
                    baseStorageDir = filesDir,
                    cacheStorageDir = cacheDir
                )
            }
        )
        probe = WorkspacePerfProbe()
    }

    private fun buildFiveThousandWordHtml(): String {
        val sentence = "Likkho diary canvas keeps private writing encrypted and responsive across long sessions with smooth scrolling and media layout. "
        // 19 words per sentence -> ~265 sentences = ~5035 words across ~66 paragraphs
        val sb = StringBuilder(360_000)
        sb.append("<h1>Long Diary Benchmark</h1>")
        for (p in 1..66) {
            sb.append("<p><b>Paragraph ").append(p).append(":</b> ")
            repeat(4) {
                sb.append(sentence)
            }
            sb.append("</p>")
        }
        return sb.toString()
    }

    private fun createSamplePngBytes(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun setUpWorkspaceWithLongTextAndMedia(): DiaryLog {
        val (img1, img2, aud1, vid1) = runBlocking {
            vm.vaultManager.withActiveDek { dek ->
                val repo = vm.vaultManager.activeMediaRepository()
                val i1 = repo.importMediaBytes("perf_img_1", createSamplePngBytes(0xFF3366CC.toInt()), dek)
                val i2 = repo.importMediaBytes("perf_img_2", createSamplePngBytes(0xFF14866D.toInt()), dek)
                val a1 = repo.importMediaBytes("perf_aud_1", ByteArray(4096) { 1 }, dek)
                val v1 = repo.importMediaBytes("perf_vid_1", ByteArray(4096) { 2 }, dek)
                listOf(i1, i2, a1, v1)
            }
        }
        val log = DiaryLog(
            id = "perf_diary_1",
            heading = "Perf Benchmark Entry",
            contentHtml = buildFiveThousandWordHtml(),
            plainPreview = "Long diary benchmark",
            canvasImages = listOf(
                CanvasImage(id = img1, source = img1, x = 24f, y = 120f, width = 160f, height = 120f, behindText = false),
                CanvasImage(id = img2, source = img2, x = 210f, y = 260f, width = 140f, height = 100f, behindText = true),
            ),
            audioAttachments = listOf(
                CanvasAudio(id = aud1, name = "VoiceNote.m4a", audioUri = aud1, durationSec = 12, x = 24f, y = 400f, behindText = false)
            ),
            videoAttachments = listOf(
                CanvasVideo(id = vid1, name = "Clip.mp4", source = vid1, durationSec = 8, x = 180f, y = 480f, width = 200f, height = 130f, behindText = false)
            )
        )
        vm.editingLog = log
        vm.route = Route.WORKSPACE
        WindowCompat.setDecorFitsSystemWindows(composeRule.activity.window, false)
        composeRule.setContent {
            LikkhoTheme(dark = false) {
                CompositionLocalProvider(
                    LocalImageBitmapLoader provides { id -> vm.loadDecryptedImageBitmap(id) },
                    LocalBitmapKeyLifecycle provides vm.bitmapKeyLifecycle,
                    LocalWorkspacePerfProbe provides probe
                ) {
                    WorkspaceScreen(vm)
                }
            }
        }
        composeRule.waitForIdle()
        // Switch from reading mode to edit mode
        composeRule.onNodeWithText("Edit").performClick()
        composeRule.waitForIdle()
        return log
    }

    private fun captureStats(label: String, frames: List<Double>): FrameStats = FrameStats(
        label = label,
        frameTimesMs = frames,
        workspaceRecompositions = probe.workspaceRecompositions,
        headerRecompositions = probe.headerRecompositions,
        toolbarRecompositions = probe.toolbarRecompositions,
        canvasBoxRecompositions = probe.canvasBoxRecompositions,
        canvasBoxLayouts = probe.canvasBoxLayouts,
        mediaItemRecompositions = probe.mediaItemRecompositions,
        mediaItemLayouts = probe.mediaItemLayouts,
        textFieldRecompositions = probe.textFieldRecompositions,
        textFieldLayouts = probe.textFieldLayouts,
        bitmapLoadCalls = probe.bitmapLoadCalls,
        thumbnailLoadCalls = probe.thumbnailLoadCalls,
    )

    private fun dispatchImeInsets(imeBottomPx: Int, navBottomPx: Int = 48) {
        val decor = composeRule.activity.window.decorView
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBottomPx))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
            .build()
        ViewCompat.dispatchApplyWindowInsets(decor, insets)
    }

    @Test
    fun benchmark_workspace_ime_scroll_drag() {
        setUpWorkspaceWithLongTextAndMedia()

        // Warmup 1 pass
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()

        // ---------------- (a) IME OPEN ----------------
        dispatchImeInsets(0)
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
        probe.reset()

        val imeFrames = mutableListOf<Double>()
        val steps = 20
        val maxImePx = 680
        for (i in 1..steps) {
            val imeBottom = (maxImePx * i) / steps
            val ns = measureNanoTime {
                composeRule.runOnUiThread { dispatchImeInsets(imeBottom) }
                composeRule.mainClock.advanceTimeBy(16)
                composeRule.waitForIdle()
            }
            imeFrames.add(ns / 1_000_000.0)
        }
        val imeStats = captureStats("A_IME_OPEN", imeFrames)

        // Close IME before scroll test
        composeRule.runOnUiThread { dispatchImeInsets(0) }
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.waitForIdle()

        // ---------------- (b) 5000-WORD SCROLL & FLING ----------------
        probe.reset()
        val scrollFrames = mutableListOf<Double>()
        // Drag scroll in 16ms frame increments
        composeRule.onNodeWithText("Long Diary Benchmark", substring = true).performTouchInput {
            down(center)
            repeat(18) {
                val ns = measureNanoTime {
                    advanceEventTime(16)
                    moveBy(Offset(0f, -60f))
                    composeRule.waitForIdle()
                }
                scrollFrames.add(ns / 1_000_000.0)
            }
            up()
        }
        // Fling gesture + 24 decay frames
        val flingStartNs = measureNanoTime {
            composeRule.onNodeWithText("Long Diary Benchmark", substring = true).performTouchInput {
                swipeUp(startY = bottom - 80f, endY = top + 80f, durationMillis = 90L)
            }
        }
        scrollFrames.add(flingStartNs / 1_000_000.0)
        repeat(24) {
            val ns = measureNanoTime {
                composeRule.mainClock.advanceTimeBy(16)
                composeRule.waitForIdle()
            }
            scrollFrames.add(ns / 1_000_000.0)
        }
        val scrollStats = captureStats("B_SCROLL_FLING_5000W", scrollFrames)

        // Scroll back to top so media item at (24dp, 120dp) is visible for drag test
        composeRule.onNodeWithText("Long Diary Benchmark", substring = true).performTouchInput {
            repeat(8) {
                swipeUp(startY = top + 120f, endY = bottom - 120f, durationMillis = 80L)
                advanceEventTime(16)
            }
        }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        // ---------------- (c1) MEDIA NORMAL DRAG ----------------
        probe.reset()
        val normalDragFrames = mutableListOf<Double>()
        val imgNode = composeRule.onNodeWithContentDescription("Canvas image")
        imgNode.performTouchInput {
            down(center)
            repeat(24) {
                val ns = measureNanoTime {
                    advanceEventTime(16)
                    moveBy(Offset(6f, 5f))
                    composeRule.waitForIdle()
                }
                normalDragFrames.add(ns / 1_000_000.0)
            }
            up()
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
        val normalDragStats = captureStats("C1_MEDIA_NORMAL_DRAG", normalDragFrames)

        // ---------------- (c2) MEDIA EDGE-AUTOSCROLL DRAG ----------------
        probe.reset()
        val edgeDragFrames = mutableListOf<Double>()
        val rootBounds = composeRule.onNodeWithText("Save").fetchSemanticsNode().root.layoutInfo.coordinates.size
        imgNode.performTouchInput {
            down(center)
            // Move finger near right-bottom edge of viewport
            val targetX = (rootBounds.width - 18f) - center.x
            val targetY = (rootBounds.height * 0.55f) - center.y
            val moveSteps = 10
            repeat(moveSteps) {
                val ns = measureNanoTime {
                    advanceEventTime(16)
                    moveBy(Offset(targetX / moveSteps, targetY / moveSteps))
                    composeRule.waitForIdle()
                }
                edgeDragFrames.add(ns / 1_000_000.0)
            }
            // Hold at edge to trigger autoscroll frames
            repeat(20) {
                val ns = measureNanoTime {
                    advanceEventTime(16)
                    composeRule.mainClock.advanceTimeBy(16)
                    composeRule.waitForIdle()
                }
                edgeDragFrames.add(ns / 1_000_000.0)
            }
            up()
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.waitForIdle()
        val edgeDragStats = captureStats("C2_MEDIA_EDGE_AUTOSCROLL_DRAG", edgeDragFrames)

        println("WORKSPACE_PERF_REPORT_START")
        print(imeStats.summary())
        print(scrollStats.summary())
        print(normalDragStats.summary())
        print(edgeDragStats.summary())
        println("WORKSPACE_PERF_REPORT_END")

        assertTrue(imeStats.totalFrames > 0)
        assertTrue(scrollStats.totalFrames > 0)
        assertTrue(normalDragStats.totalFrames > 0)
        assertTrue(edgeDragStats.totalFrames > 0)
    }
}
