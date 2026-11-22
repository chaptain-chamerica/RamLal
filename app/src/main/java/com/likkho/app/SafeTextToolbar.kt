package com.likkho.app

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Pure state/lifecycle coordinator for [ActionMode.TYPE_FLOATING].
 *
 * Compose 1.7.0's default [androidx.compose.ui.platform.AndroidTextToolbar] can invoke
 * `ActionMode.finish()` twice on the same floating `ActionMode` instance (for example when
 * `onActionItemClicked` invokes `onCopyRequested`, which calls `hide()`, and then
 * `onActionItemClicked` calls `mode?.finish()` again; or when `hide()` re-enters while
 * `DecorView` is already tearing down the mode). Because `FloatingActionMode.finish()` does
 * not guard against repeated calls, `DecorView` logs:
 * `Destroying unexpected ActionMode instance of TYPE_FLOATING; ... was not the current floating action mode! Expected null`.
 *
 * Tracking active/finishing/finished mode instances guarantees `performFinish` is executed
 * at most once per mode instance.
 */
internal class FloatingActionModeCoordinator<M : Any> {
    companion object {
        const val MENU_ITEM_COPY = 0
        const val MENU_ITEM_PASTE = 1
        const val MENU_ITEM_CUT = 2
        const val MENU_ITEM_SELECT_ALL = 3
    }

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

    private var activeMode: M? = null
    private var finishingMode: M? = null
    private val finishedModes: MutableSet<M> = Collections.newSetFromMap(WeakHashMap())

    val currentMode: M?
        get() = activeMode

    fun showMenu(
        isAttached: Boolean,
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        startMode: () -> M?,
        invalidateMode: (M) -> Unit,
        invalidateContentRect: (M) -> Unit,
        performFinish: (M) -> Unit,
    ) {
        contentRect = rect
        this.onCopyRequested = onCopyRequested
        this.onPasteRequested = onPasteRequested
        this.onCutRequested = onCutRequested
        this.onSelectAllRequested = onSelectAllRequested

        if (!isAttached) {
            hide(performFinish)
            return
        }

        val current = activeMode
        if (current == null || finishingMode === current || finishedModes.contains(current)) {
            activeMode = null
            val started = startMode()
            if (started != null && finishingMode !== started && !finishedModes.contains(started)) {
                activeMode = started
                status = TextToolbarStatus.Shown
            } else {
                activeMode = null
                status = TextToolbarStatus.Hidden
            }
        } else {
            status = TextToolbarStatus.Shown
            invalidateMode(current)
            invalidateContentRect(current)
        }
    }

    fun hide(performFinish: (M) -> Unit) {
        status = TextToolbarStatus.Hidden
        val mode = activeMode
        activeMode = null
        finishModeSafely(mode, performFinish)
    }

    fun finishModeSafely(mode: M?, performFinish: (M) -> Unit) {
        if (mode == null) return
        if (activeMode === mode) {
            activeMode = null
        }
        status = TextToolbarStatus.Hidden
        if (finishingMode === mode || finishedModes.contains(mode)) {
            return
        }
        finishingMode = mode
        finishedModes.add(mode)
        try {
            performFinish(mode)
        } finally {
            if (finishingMode === mode) {
                finishingMode = null
            }
        }
    }

    fun onActionItemClicked(mode: M?, itemId: Int, performFinish: (M) -> Unit): Boolean {
        val action: (() -> Unit)? = when (itemId) {
            MENU_ITEM_COPY -> onCopyRequested
            MENU_ITEM_PASTE -> onPasteRequested
            MENU_ITEM_CUT -> onCutRequested
            MENU_ITEM_SELECT_ALL -> onSelectAllRequested
            else -> return false
        }
        action?.invoke()
        finishModeSafely(mode, performFinish)
        return true
    }

    fun onDestroyActionMode(mode: M?) {
        if (mode != null) {
            finishedModes.add(mode)
        }
        if (activeMode === mode) {
            activeMode = null
            status = TextToolbarStatus.Hidden
        }
    }
}

class SafeFloatingTextToolbar(private val view: View) : TextToolbar {
    private val coordinator = FloatingActionModeCoordinator<ActionMode>()

    private val finishActionMode: (ActionMode) -> Unit = { mode ->
        runCatching { mode.finish() }
    }

    private val actionModeCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            if (mode == null || menu == null) return false
            syncMenuItems(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            if (mode == null || menu == null) return false
            syncMenuItems(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            val itemId = item?.itemId ?: return false
            return coordinator.onActionItemClicked(mode, itemId, finishActionMode)
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            coordinator.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: AndroidRect?) {
            val r = coordinator.contentRect
            outRect?.set(
                safeRoundCoord(r.left),
                safeRoundCoord(r.top),
                safeRoundCoord(r.right),
                safeRoundCoord(r.bottom),
            )
        }
    }

    override val status: TextToolbarStatus
        get() = coordinator.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        coordinator.showMenu(
            isAttached = view.isAttachedToWindow && view.windowToken != null,
            rect = rect,
            onCopyRequested = onCopyRequested,
            onPasteRequested = onPasteRequested,
            onCutRequested = onCutRequested,
            onSelectAllRequested = onSelectAllRequested,
            startMode = {
                runCatching {
                    view.startActionMode(actionModeCallback, ActionMode.TYPE_FLOATING)
                }.getOrNull()
            },
            invalidateMode = { mode -> runCatching { mode.invalidate() } },
            invalidateContentRect = { mode -> runCatching { mode.invalidateContentRect() } },
            performFinish = finishActionMode,
        )
    }

    override fun hide() {
        coordinator.hide(finishActionMode)
    }

    private fun syncMenuItems(menu: Menu) {
        syncMenuItem(
            menu = menu,
            id = FloatingActionModeCoordinator.MENU_ITEM_COPY,
            order = 0,
            titleRes = android.R.string.copy,
            enabled = coordinator.onCopyRequested != null,
        )
        syncMenuItem(
            menu = menu,
            id = FloatingActionModeCoordinator.MENU_ITEM_PASTE,
            order = 1,
            titleRes = android.R.string.paste,
            enabled = coordinator.onPasteRequested != null,
        )
        syncMenuItem(
            menu = menu,
            id = FloatingActionModeCoordinator.MENU_ITEM_CUT,
            order = 2,
            titleRes = android.R.string.cut,
            enabled = coordinator.onCutRequested != null,
        )
        syncMenuItem(
            menu = menu,
            id = FloatingActionModeCoordinator.MENU_ITEM_SELECT_ALL,
            order = 3,
            titleRes = android.R.string.selectAll,
            enabled = coordinator.onSelectAllRequested != null,
        )
    }

    private fun syncMenuItem(
        menu: Menu,
        id: Int,
        order: Int,
        titleRes: Int,
        enabled: Boolean,
    ) {
        val existing = menu.findItem(id)
        if (enabled && existing == null) {
            menu.add(Menu.NONE, id, order, titleRes)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        } else if (!enabled && existing != null) {
            menu.removeItem(id)
        }
    }

    private fun safeRoundCoord(value: Float): Int =
        if (value.isFinite()) value.roundToInt() else 0
}

@Composable
fun rememberSafeTextToolbar(): TextToolbar {
    val view = LocalView.current
    val toolbar = remember(view) { SafeFloatingTextToolbar(view) }
    DisposableEffect(toolbar, view) {
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                toolbar.hide()
            }
        }
        view.addOnAttachStateChangeListener(attachListener)
        onDispose {
            view.removeOnAttachStateChangeListener(attachListener)
            toolbar.hide()
        }
    }
    return toolbar
}
