package com.likkho.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeTextToolbarTest {

    private class FakeActionMode(val id: Int)

    @Test
    fun actionItemClickCallingHideSynchronously_finishesModeOnlyOnce() {
        val coordinator = FloatingActionModeCoordinator<FakeActionMode>()
        val mode = FakeActionMode(1)
        var finishCount = 0
        val performFinish: (FakeActionMode) -> Unit = {
            finishCount++
            coordinator.onDestroyActionMode(it)
        }

        coordinator.showMenu(
            isAttached = true,
            rect = Rect(0f, 0f, 100f, 50f),
            onCopyRequested = {
                // Compose 1.7.0 TextFieldSelectionManager.copy() calls textToolbar.hide()
                // before returning to TextActionModeCallback.onActionItemClicked(...)
                coordinator.hide(performFinish)
            },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
            startMode = { mode },
            invalidateMode = {},
            invalidateContentRect = {},
            performFinish = performFinish,
        )
        assertEquals(TextToolbarStatus.Shown, coordinator.status)
        assertSame(mode, coordinator.currentMode)

        val handled = coordinator.onActionItemClicked(
            mode = mode,
            itemId = FloatingActionModeCoordinator.MENU_ITEM_COPY,
            performFinish = performFinish,
        )
        assertTrue(handled)
        assertEquals(1, finishCount)
        assertEquals(TextToolbarStatus.Hidden, coordinator.status)
        assertNull(coordinator.currentMode)
    }

    @Test
    fun reentrantHideDuringFinish_doesNotDoubleFinish() {
        val coordinator = FloatingActionModeCoordinator<FakeActionMode>()
        val mode = FakeActionMode(2)
        var finishCount = 0
        lateinit var performFinish: (FakeActionMode) -> Unit
        performFinish = { m ->
            finishCount++
            // Simulate re-entrant hide triggered during DecorView teardown
            coordinator.hide(performFinish)
            coordinator.onDestroyActionMode(m)
        }

        coordinator.showMenu(
            isAttached = true,
            rect = Rect(10f, 20f, 80f, 40f),
            onCopyRequested = {},
            onPasteRequested = {},
            onCutRequested = null,
            onSelectAllRequested = null,
            startMode = { mode },
            invalidateMode = {},
            invalidateContentRect = {},
            performFinish = performFinish,
        )

        coordinator.hide(performFinish)
        assertEquals(1, finishCount)
        assertEquals(TextToolbarStatus.Hidden, coordinator.status)
        assertNull(coordinator.currentMode)
    }

    @Test
    fun hideAfterExternalDestroy_neverCallsFinish() {
        val coordinator = FloatingActionModeCoordinator<FakeActionMode>()
        val mode = FakeActionMode(3)
        var finishCount = 0
        val performFinish: (FakeActionMode) -> Unit = { finishCount++ }

        coordinator.showMenu(
            isAttached = true,
            rect = Rect(0f, 0f, 40f, 40f),
            onCopyRequested = {},
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
            startMode = { mode },
            invalidateMode = {},
            invalidateContentRect = {},
            performFinish = performFinish,
        )

        // Window/DecorView already destroyed this floating ActionMode
        coordinator.onDestroyActionMode(mode)
        assertEquals(TextToolbarStatus.Hidden, coordinator.status)
        assertNull(coordinator.currentMode)

        // Late Compose DisposableEffect / ActionMode click teardown must not call finish()
        coordinator.hide(performFinish)
        coordinator.finishModeSafely(mode, performFinish)
        assertEquals(0, finishCount)
    }

    @Test
    fun showMenu_reusesLiveMode_andStartsFreshAfterDestroy() {
        val coordinator = FloatingActionModeCoordinator<FakeActionMode>()
        var nextId = 1
        var startCount = 0
        var invalidateCount = 0
        var rectInvalidateCount = 0
        val performFinish: (FakeActionMode) -> Unit = { coordinator.onDestroyActionMode(it) }

        coordinator.showMenu(
            isAttached = true,
            rect = Rect(0f, 0f, 20f, 20f),
            onCopyRequested = {},
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
            startMode = {
                startCount++
                FakeActionMode(nextId++)
            },
            invalidateMode = { invalidateCount++ },
            invalidateContentRect = { rectInvalidateCount++ },
            performFinish = performFinish,
        )
        assertEquals(1, startCount)
        assertEquals(0, invalidateCount)
        assertEquals(1, coordinator.currentMode?.id)

        // Reposition while active -> invalidates existing mode instead of starting another
        coordinator.showMenu(
            isAttached = true,
            rect = Rect(50f, 60f, 90f, 80f),
            onCopyRequested = {},
            onPasteRequested = {},
            onCutRequested = null,
            onSelectAllRequested = null,
            startMode = {
                startCount++
                FakeActionMode(nextId++)
            },
            invalidateMode = { invalidateCount++ },
            invalidateContentRect = { rectInvalidateCount++ },
            performFinish = performFinish,
        )
        assertEquals(1, startCount)
        assertEquals(1, invalidateCount)
        assertEquals(1, rectInvalidateCount)
        assertEquals(1, coordinator.currentMode?.id)

        // Destroy externally, then show again -> starts fresh mode #2
        coordinator.onDestroyActionMode(coordinator.currentMode)
        coordinator.showMenu(
            isAttached = true,
            rect = Rect(5f, 5f, 25f, 25f),
            onCopyRequested = {},
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
            startMode = {
                startCount++
                FakeActionMode(nextId++)
            },
            invalidateMode = { invalidateCount++ },
            invalidateContentRect = { rectInvalidateCount++ },
            performFinish = performFinish,
        )
        assertEquals(2, startCount)
        assertEquals(2, coordinator.currentMode?.id)
        assertEquals(TextToolbarStatus.Shown, coordinator.status)
    }

    @Test
    fun detachedView_doesNotStartMode_andUnknownMenuItemReturnsFalse() {
        val coordinator = FloatingActionModeCoordinator<FakeActionMode>()
        var started = false
        coordinator.showMenu(
            isAttached = false,
            rect = Rect(0f, 0f, 10f, 10f),
            onCopyRequested = {},
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
            startMode = {
                started = true
                FakeActionMode(99)
            },
            invalidateMode = {},
            invalidateContentRect = {},
            performFinish = {},
        )
        assertFalse(started)
        assertEquals(TextToolbarStatus.Hidden, coordinator.status)
        assertFalse(coordinator.onActionItemClicked(null, 999) {})
    }
}
