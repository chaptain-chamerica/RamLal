package com.likkho.app

import android.os.Bundle
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
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
                .withEndAction { splashViewProvider.remove() }
                .start()
        }

        enableEdgeToEdge()
        setContent { LikkhoTheme(vm.darkMode) { Root(vm) } }
    }
}

@Composable
fun Root(vm: AppViewModel) {
    val w = wiki()
    val view = LocalView.current
    val activity = view.context as ComponentActivity

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
        if (vm.effectivePrimaryPin.isNotEmpty()) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    // system bar icons + full screen
    LaunchedEffect(vm.darkMode, vm.fullScreen) {
        val c = WindowCompat.getInsetsController(activity.window, view)
        c.isAppearanceLightStatusBars = !vm.darkMode
        c.isAppearanceLightNavigationBars = !vm.darkMode
        if (vm.fullScreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

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
        if (vm.route == Route.HOME) HomeFloatingBanners(vm, Modifier.align(Alignment.BottomCenter))
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
