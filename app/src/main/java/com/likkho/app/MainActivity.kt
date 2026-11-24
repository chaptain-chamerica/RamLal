package com.likkho.app

import android.content.ComponentCallbacks2
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        vm.hooks.attachActivity(this)

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

    override fun onDestroy() {
        vm.hooks.attachActivity(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && vm.fullScreen) {
            applySystemBars(darkMode = vm.darkMode, fullScreen = vm.fullScreen)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        vm.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        vm.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
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

    DisposableEffect(activity) {
        vm.hooks.attachActivity(activity)
        onDispose {
            if (activity != null) vm.hooks.attachActivity(null)
        }
    }

    // Lock and zeroize DEK whenever app goes to background (if passcode set)
    DisposableEffect(Unit) {
        val obs = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (vm.effectivePrimaryPin.isNotEmpty()) {
                    vm.handleInstantLock()
                }
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
        LocalImageBitmapLoader provides { mediaId -> vm.loadDecryptedImageBitmap(mediaId) },
        LocalBitmapKeyLifecycle provides vm.bitmapKeyLifecycle
    ) {
        ProvideSafeTextToolbar {
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

        // Lightweight Wiki loader when app has no passcode and is finishing cold-start Keystore unwrap + index load
        AnimatedVisibility(
            visible = !vm.isLocked && vm.isVaultChecking,
            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
            exit = fadeOut(animationSpec = tween(durationMillis = 180))
        ) {
            Column(
                Modifier.fillMaxSize().background(w.bg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.size(56.dp).background(w.surface).wikiBorder(w.border), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.LockOpen, null, tint = w.accent, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("Likkho", style = serifStyle(24.sp, w.text))
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier.widthIn(max = 180.dp).fillMaxWidth().height(3.dp),
                    color = w.accent,
                    trackColor = w.hairline,
                    strokeCap = StrokeCap.Butt
                )
                Spacer(Modifier.height(6.dp))
                Text("Loading…", style = sansStyle(11.sp, w.muted, FontWeight.Medium))
            }
        }

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
}
