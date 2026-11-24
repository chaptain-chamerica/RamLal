package com.likkho.app

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.roundToInt

private const val MENU_ITEM_COPY = 0
private const val MENU_ITEM_PASTE = 1
private const val MENU_ITEM_CUT = 2
private const val MENU_ITEM_SELECT_ALL = 3

internal interface ActionModeHandle {
    fun invalidate()
    fun invalidateContentRect()
    fun finish()
}

/**
 * Coordinates the lifecycle of a floating [ActionModeHandle] so `finish()` can never be invoked
 * more than once for the same mode instance or after `onDestroyActionMode` has already cleared it.
 *
 * This avoids AOSP DecorView's:
 * `E/DecorView[]: Destroying unexpected ActionMode instance of TYPE_FLOATING; ... Expected null`
 * triggered when Compose's TextActionModeCallback calls `mode?.finish()` after `copy()`/`cut()`
 * has already called `textToolbar.hide()`.
 */
internal class FloatingActionModeCoordinator(
    private val isAttached: () -> Boolean,
    private val startActionMode: () -> ActionModeHandle?,
) {
    private var activeHandle: ActionModeHandle? = null
    private val finishedHandles = Collections.newSetFromMap(IdentityHashMap<ActionModeHandle, Boolean>())

    var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    var contentRect: Rect = Rect.Zero
        private set
    var onCopyRequested: (() -> Unit)? = null
        private set
    var onPasteRequested: (() -> Unit)? = null
        private set
    var onCutRequested: (() -> Unit)? = null
        private set
    var onSelectAllRequested: (() -> Unit)? = null
        private set

    fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        val rectChanged = contentRect != rect
        val callbacksChanged = (this.onCopyRequested != null) != (onCopyRequested != null) ||
            (this.onPasteRequested != null) != (onPasteRequested != null) ||
            (this.onCutRequested != null) != (onCutRequested != null) ||
            (this.onSelectAllRequested != null) != (onSelectAllRequested != null)

        contentRect = rect
        this.onCopyRequested = onCopyRequested
        this.onPasteRequested = onPasteRequested
        this.onCutRequested = onCutRequested
        this.onSelectAllRequested = onSelectAllRequested

        val hasAnyAction = onCopyRequested != null ||
            onPasteRequested != null ||
            onCutRequested != null ||
            onSelectAllRequested != null
        if (!hasAnyAction || !isAttached()) {
            hide()
            return
        }

        val existing = activeHandle
        if (existing == null || finishedHandles.contains(existing)) {
            activeHandle = null
            status = TextToolbarStatus.Shown
            val started = startActionMode()
            if (started != null && !finishedHandles.contains(started)) {
                if (status == TextToolbarStatus.Shown && isAttached()) {
                    activeHandle = started
                } else {
                    finishSafely(started)
                }
            } else {
                status = TextToolbarStatus.Hidden
            }
        } else {
            status = TextToolbarStatus.Shown
            if (callbacksChanged) {
                existing.invalidate()
            }
            if (rectChanged) {
                existing.invalidateContentRect()
            }
        }
    }

    fun hide() {
        status = TextToolbarStatus.Hidden
        val handle = activeHandle
        activeHandle = null
        if (handle != null) {
            if (isAttached()) {
                finishSafely(handle)
            } else {
                finishedHandles.add(handle)
            }
        }
    }

    fun onDetached() {
        status = TextToolbarStatus.Hidden
        val handle = activeHandle
        activeHandle = null
        if (handle != null) {
            finishedHandles.add(handle)
        }
    }

    fun onActionItemClicked(handle: ActionModeHandle?, itemId: Int): Boolean {
        val action = when (itemId) {
            MENU_ITEM_COPY -> onCopyRequested
            MENU_ITEM_PASTE -> onPasteRequested
            MENU_ITEM_CUT -> onCutRequested
            MENU_ITEM_SELECT_ALL -> onSelectAllRequested
            else -> return false
        }
        action?.invoke()
        finishSafely(handle)
        return true
    }

    fun onDestroyActionMode(handle: ActionModeHandle?) {
        if (handle != null) {
            finishedHandles.add(handle)
            if (activeHandle === handle) {
                activeHandle = null
                status = TextToolbarStatus.Hidden
            }
        } else {
            activeHandle = null
            status = TextToolbarStatus.Hidden
        }
    }

    private fun finishSafely(handle: ActionModeHandle?) {
        if (handle == null) return
        if (activeHandle === handle) {
            activeHandle = null
            status = TextToolbarStatus.Hidden
        }
        if (finishedHandles.add(handle)) {
            handle.finish()
        }
    }
}

internal class SafeAndroidTextToolbar(private val view: View) : TextToolbar {
    private val handleMap = IdentityHashMap<ActionMode, ActionModeHandle>()

    private fun wrap(mode: ActionMode): ActionModeHandle =
        handleMap.getOrPut(mode) {
            object : ActionModeHandle {
                override fun invalidate() {
                    runCatching { mode.invalidate() }
                }

                override fun invalidateContentRect() {
                    runCatching { mode.invalidateContentRect() }
                }

                override fun finish() {
                    runCatching { mode.finish() }
                }
            }
        }

    private val callback: ActionMode.Callback2 = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            if (mode == null || menu == null) return false
            wrap(mode)
            populateMenu(menu)
            return menu.size() > 0
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            if (mode == null || menu == null) return false
            menu.clear()
            populateMenu(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            val itemId = item?.itemId ?: return false
            val handle = mode?.let { wrap(it) }
            return coordinator.onActionItemClicked(handle, itemId)
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            val handle = mode?.let { handleMap.remove(it) ?: wrap(it) }
            coordinator.onDestroyActionMode(handle)
        }

        override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: AndroidRect?) {
            val rect = coordinator.contentRect
            outRect?.set(
                rect.left.roundToInt(),
                rect.top.roundToInt(),
                rect.right.roundToInt(),
                rect.bottom.roundToInt()
            )
        }
    }

    private val coordinator: FloatingActionModeCoordinator = FloatingActionModeCoordinator(
        isAttached = { view.isAttachedToWindow && view.windowToken != null },
        startActionMode = {
            val started = runCatching {
                view.startActionMode(callback, ActionMode.TYPE_FLOATING)
            }.getOrNull()
            started?.let { wrap(it) }
        }
    )

    override val status: TextToolbarStatus
        get() = coordinator.status

    private fun populateMenu(menu: Menu) {
        if (coordinator.onCopyRequested != null) {
            menu.add(Menu.NONE, MENU_ITEM_COPY, 0, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        if (coordinator.onPasteRequested != null) {
            menu.add(Menu.NONE, MENU_ITEM_PASTE, 1, android.R.string.paste)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        if (coordinator.onCutRequested != null) {
            menu.add(Menu.NONE, MENU_ITEM_CUT, 2, android.R.string.cut)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        if (coordinator.onSelectAllRequested != null) {
            menu.add(Menu.NONE, MENU_ITEM_SELECT_ALL, 3, android.R.string.selectAll)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        coordinator.showMenu(
            rect = rect,
            onCopyRequested = onCopyRequested,
            onPasteRequested = onPasteRequested,
            onCutRequested = onCutRequested,
            onSelectAllRequested = onSelectAllRequested,
        )
    }

    override fun hide() {
        coordinator.hide()
    }

    fun onDetached() {
        coordinator.onDetached()
        handleMap.clear()
    }
}

@Composable
fun ProvideSafeTextToolbar(content: @Composable () -> Unit) {
    val view = LocalView.current
    val toolbar = remember(view) { SafeAndroidTextToolbar(view) }
    DisposableEffect(view, toolbar) {
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                toolbar.onDetached()
            }
        }
        view.addOnAttachStateChangeListener(attachListener)
        onDispose {
            view.removeOnAttachStateChangeListener(attachListener)
            toolbar.hide()
        }
    }
    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        content()
    }
}
