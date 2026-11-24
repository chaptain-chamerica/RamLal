package com.likkho.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeTextToolbarTest {

    private class FakeActionModeHandle : ActionModeHandle {
        var invalidateCount = 0
        var invalidateRectCount = 0
        var finishCount = 0

        override fun invalidate() {
            invalidateCount++
        }

        override fun invalidateContentRect() {
            invalidateRectCount++
        }

        override fun finish() {
            finishCount++
        }
    }

    @Test
    fun copy_action_that_hides_toolbar_synchronously_finishes_action_mode_only_once() {
        var attached = true
        val handles = mutableListOf<FakeActionModeHandle>()
        lateinit var coordinator: FloatingActionModeCoordinator
        coordinator = FloatingActionModeCoordinator(
            isAttached = { attached },
            startActionMode = { FakeActionModeHandle().also { handles.add(it) } }
        )

        var copyInvoked = false
        coordinator.showMenu(
            rect = Rect(10f, 20f, 100f, 60f),
            onCopyRequested = {
                copyInvoked = true
                // Simulate Compose TextFieldSelectionManager.copy() calling textToolbar.hide()
                coordinator.hide()
            },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
        )

        assertEquals(1, handles.size)
        val handle = handles.single()
        assertEquals(TextToolbarStatus.Shown, coordinator.status)

        val handled = coordinator.onActionItemClicked(handle, 0 /* MENU_ITEM_COPY */)
        assertTrue(handled)
        assertTrue(copyInvoked)
        assertEquals(1, handle.finishCount)
        assertEquals(TextToolbarStatus.Hidden, coordinator.status)

        // Subsequent destroy callback or duplicate hide must not finish again
        coordinator.onDestroyActionMode(handle)
        coordinator.hide()
        assertEquals(1, handle.finishCount)
    }

    @Test
    fun destroy_callback_from_decor_view_prevents_subsequent_hide_from_finishing_again() {
        val handles = mutableListOf<FakeActionModeHandle>()
        val coordinator = FloatingActionModeCoordinator(
            isAttached = { true },
            startActionMode = { FakeActionModeHandle().also { handles.add(it) } }
        )

        coordinator.showMenu(
            rect = Rect(0f, 0f, 50f, 20f),
            onCopyRequested = {},
            onPasteRequested = {},
            onCutRequested = null,
            onSelectAllRequested = null,
        )
        val handle = handles.single()

        // DecorView destroys the floating mode externally (e.g., tap outside or window focus loss)
        coordinator.onDestroyActionMode(handle)
        assertEquals(TextToolbarStatus.Hidden, coordinator.status)

        // Compose later calls hide() -> must NOT call finish() on the already destroyed mode
        coordinator.hide()
        assertEquals(0, handle.finishCount)
    }

    @Test
    fun repeated_hide_and_detached_hide_never_double_finish() {
        var attached = true
        val handles = mutableListOf<FakeActionModeHandle>()
        val coordinator = FloatingActionModeCoordinator(
            isAttached = { attached },
            startActionMode = { FakeActionModeHandle().also { handles.add(it) } }
        )

        coordinator.showMenu(
            rect = Rect(0f, 0f, 50f, 20f),
            onCopyRequested = {},
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
        )
        val handle = handles.single()

        coordinator.hide()
        coordinator.hide()
        assertEquals(1, handle.finishCount)

        // Start a second mode, then detach window before hide()
        coordinator.showMenu(
            rect = Rect(5f, 5f, 60f, 25f),
            onCopyRequested = {},
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
        )
        assertEquals(2, handles.size)
        val second = handles[1]

        attached = false
        coordinator.onDetached()
        coordinator.hide()
        assertEquals(0, second.finishCount)
        assertEquals(TextToolbarStatus.Hidden, coordinator.status)
    }

    @Test
    fun show_menu_while_detached_or_empty_does_not_start_action_mode() {
        val handles = mutableListOf<FakeActionModeHandle>()
        val detachedCoordinator = FloatingActionModeCoordinator(
            isAttached = { false },
            startActionMode = { FakeActionModeHandle().also { handles.add(it) } }
        )
        detachedCoordinator.showMenu(Rect(0f, 0f, 10f, 10f), {}, null, null, null)
        assertTrue(handles.isEmpty())
        assertEquals(TextToolbarStatus.Hidden, detachedCoordinator.status)

        val emptyActionsCoordinator = FloatingActionModeCoordinator(
            isAttached = { true },
            startActionMode = { FakeActionModeHandle().also { handles.add(it) } }
        )
        emptyActionsCoordinator.showMenu(Rect(0f, 0f, 10f, 10f), null, null, null, null)
        assertTrue(handles.isEmpty())
        assertFalse(emptyActionsCoordinator.status == TextToolbarStatus.Shown)
    }
}
