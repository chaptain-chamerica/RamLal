package com.likkho.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        applySystemBars(darkMode = vm.darkMode, fullScreen = vm.fullScreen)

        splashScreen.setKeepOnScreenCondition { !vm.isStartupReady }
        splashScreen.setOnExitAnimationListener { splashViewProvider ->
            runCatching {
                splashViewProvider.iconView.animate()
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .alpha(0f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            splashViewProvider.view.animate()
                .alpha(0f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    splashViewProvider.remove()
                    applySystemBars(darkMode = vm.darkMode, fullScreen = vm.fullScreen)
                }
                .start()
        }

        setContent { LikkhoTheme(vm.darkMode) { Root(vm) } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applySystemBars(darkMode = vm.darkMode, fullScreen = vm.fullScreen)
        }
    }

    fun applySystemBars(darkMode: Boolean, fullScreen: Boolean) {
        val statusBarStyle = if (darkMode) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        val navBarStyle = if (darkMode) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.argb(0x66, 0x00, 0x00, 0x00)
            )
        }
        enableEdgeToEdge(statusBarStyle = statusBarStyle, navigationBarStyle = navBarStyle)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !darkMode
        controller.isAppearanceLightNavigationBars = !darkMode
        if (fullScreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
fun Root(vm: AppViewModel) {
    val w = wiki()
    val view = LocalView.current
    val activity = view.context as? MainActivity

    // lock whenever app goes to background (if passcode set)
    DisposableEffect(Unit) {
        val obs = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (vm.effectivePrimaryPin.isNotEmpty()) vm.isLocked = true
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(obs) }
    }
    // FLAG_SECURE while passcode set
    LaunchedEffect(vm.savedPasscode, vm.secondaryPasscode) {
        val win = activity?.window ?: return@LaunchedEffect
        if (vm.effectivePrimaryPin.isNotEmpty()) {
            win.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            win.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    // Keep status bar & navigation bar icons readable in both light and dark modes
    LaunchedEffect(vm.darkMode, vm.fullScreen) {
        activity?.applySystemBars(darkMode = vm.darkMode, fullScreen = vm.fullScreen)
    }

    CompositionLocalProvider(
        LocalImageBitmapLoader provides { mediaId -> vm.loadDecryptedImageBitmap(mediaId) }
    ) {
    Box(Modifier.fillMaxSize().background(w.bg).statusBarsPadding()) {
        Crossfade(
            targetState = vm.route,
            animationSpec = tween(durationMillis = 200),
            label = "route_transition"
        ) { currentRoute ->
            when (currentRoute) {
                Route.HOME -> HomeScreen(vm)
                Route.WORKSPACE -> WorkspaceScreen(vm)
                Route.SETTINGS -> SettingsScreen(vm)
                Route.BACKUP -> BackupRestoreScreen(vm)
            }
        }
        if (vm.route == Route.HOME || vm.route == Route.WORKSPACE) {
            HomeFloatingBanners(vm, Modifier.align(Alignment.BottomCenter))
        }
        RingingBanner(vm, Modifier.align(Alignment.TopCenter))
        UnlockDiaryDialog(vm)
        DeleteEntryDialog(vm)
        ExportDialog(vm)
        ShareDiaryDialog(vm)
        vm.availableUpdate?.let { UpdateDialog(it, vm.hooks) { vm.availableUpdate = null } }

        AnimatedVisibility(
            visible = vm.isLocked && vm.effectivePrimaryPin.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = fadeOut(animationSpec = tween(durationMillis = 220))
        ) {
            Box(Modifier.fillMaxSize().background(w.bg)) {
                PasscodeScreen(vm)
            }
        }
    }
    }
}
