package com.likkho.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.likkho.app.storage.UnlockResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coordinates PIN verification attempts on the lock screen:
 * - Only displays the verifying loader if verification exceeds [loaderDelayMs] (150ms default).
 * - Cancels prior verification and ignores stale results when user enters or deletes digits.
 * - Identical behavior for Primary and Decoy vaults.
 */
class PasscodeAttemptController(
    private val scope: CoroutineScope,
    private val loaderDelayMs: Long = 150L,
    private val attemptUnlock: suspend (String) -> UnlockResult
) {
    private val _showLoader = MutableStateFlow(false)
    val showLoader: StateFlow<Boolean> = _showLoader.asStateFlow()

    private val _isVerifying = MutableStateFlow(false)
    val isVerifying: StateFlow<Boolean> = _isVerifying.asStateFlow()

    private var currentToken: Long = 0L
    private var activeJob: Job? = null
    private var loaderTimerJob: Job? = null

    fun cancelCurrent() {
        currentToken++
        activeJob?.cancel()
        activeJob = null
        loaderTimerJob?.cancel()
        loaderTimerJob = null
        _isVerifying.value = false
        _showLoader.value = false
    }

    fun submit(pin: String, onOutcome: (UnlockResult) -> Unit) {
        currentToken++
        val token = currentToken
        activeJob?.cancel()
        loaderTimerJob?.cancel()

        _isVerifying.value = true
        _showLoader.value = false

        loaderTimerJob = scope.launch {
            delay(loaderDelayMs)
            if (isActive && currentToken == token) {
                _showLoader.value = true
            }
        }

        activeJob = scope.launch {
            val res = try {
                attemptUnlock(pin)
            } catch (_: CancellationException) {
                return@launch
            } catch (t: Throwable) {
                UnlockResult.Error(t.message ?: "Unlock error")
            }
            if (!isActive || currentToken != token) {
                return@launch
            }
            loaderTimerJob?.cancel()
            loaderTimerJob = null
            _isVerifying.value = false
            _showLoader.value = false
            onOutcome(res)
        }
    }
}

/**
 * Lock screen (same as PasscodeScreen.tsx).
 * - dots = length of primary PIN (or secondary if no primary)
 * - PIN auto-checks when typed length hits registered lengths: primary => PRIMARY vault, secondary => DECOY vault
 * - loader shows below dots ONLY when verification takes >150ms (or cold start >150ms)
 * - wrong => red dots + "Incorrect passcode" + vibrate + clears after 350ms; 5th wrong => cooldown (30s), 6th+ 30s*n
 * - biometric key shown only if enabled; prompt is auto-shown ONCE when screen opens
 */
@Composable
fun PasscodeScreen(vm: AppViewModel) {
    val w = wiki()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val maxPinLen = vm.maxPinLength
    val dotCount = vm.minPinLength

    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var bioMessage by remember { mutableStateOf("") }
    var cooldown by remember { mutableStateOf(vm.remainingCooldownSeconds()) }
    var showColdStartLoader by remember { mutableStateOf(false) }
    var anyDigitTyped by remember { mutableStateOf(false) }
    val isCooling = cooldown > 0

    val attemptController = remember(vm) {
        PasscodeAttemptController(scope, loaderDelayMs = 150L) { pin -> vm.attemptUnlock(pin) }
    }
    val showPinLoader by attemptController.showLoader.collectAsState()

    DisposableEffect(attemptController) {
        onDispose { attemptController.cancelCurrent() }
    }

    LaunchedEffect(Unit) {
        while (true) { cooldown = vm.remainingCooldownSeconds(); delay(500) }
    }

    LaunchedEffect(vm.isVaultChecking) {
        if (vm.isVaultChecking) {
            delay(150)
            if (vm.isVaultChecking) showColdStartLoader = true
        } else {
            showColdStartLoader = false
        }
    }

    fun triggerBiometric() {
        if (vm.remainingCooldownSeconds() > 0) return
        bioMessage = ""
        vm.lockBiometricMessage = null
        vm.startBiometricUnlock { res ->
            when (res) {
                is UnlockResult.Success -> {
                    cooldown = 0
                    error = false
                    entered = ""
                }
                is UnlockResult.InCooldown -> {
                    cooldown = res.remainingSeconds
                }
                is UnlockResult.BiometricInvalidated -> {
                    bioMessage = res.message
                }
                is UnlockResult.Error -> {
                    bioMessage = res.message
                }
                UnlockResult.Cancelled -> Unit
                else -> {}
            }
        }
    }

    // auto prompt ONCE when lock screen opens and vault metadata check completed
    LaunchedEffect(vm.biometricsEnabled) {
        if (!anyDigitTyped && vm.biometricsEnabled && vm.remainingCooldownSeconds() == 0) {
            delay(220)
            if (!anyDigitTyped && vm.biometricsEnabled && vm.remainingCooldownSeconds() == 0) {
                triggerBiometric()
            }
        }
    }

    fun onDigit(d: String) {
        val cd = vm.remainingCooldownSeconds()
        if (cd > 0) { cooldown = cd; return }
        anyDigitTyped = true
        vm.hooks.cancelBiometricPrompt()
        attemptController.cancelCurrent()
        error = false; bioMessage = ""; vm.lockBiometricMessage = null
        val next = (entered + d).take(maxPinLen)
        entered = next

        if (vm.isPinLengthApplicable(next.length)) {
            attemptController.submit(next) { res ->
                when (res) {
                    is UnlockResult.Success -> {
                        cooldown = 0
                        error = false
                        entered = ""
                    }
                    is UnlockResult.WrongPin -> {
                        if (res.isMaxApplicableLength || !vm.hasLargerApplicableLength(next.length)) {
                            error = true
                            if (res.cooldownTriggeredSeconds > 0) cooldown = res.cooldownTriggeredSeconds
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            scope.launch {
                                delay(350)
                                if (entered == next) entered = ""
                            }
                        }
                    }
                    is UnlockResult.InCooldown -> {
                        cooldown = res.remainingSeconds
                        entered = ""
                    }
                    else -> {}
                }
            }
        } else if (!vm.hasLargerApplicableLength(next.length)) {
            error = true
            val secs = vm.recordPasscodeFailure()
            if (secs > 0) cooldown = secs
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.launch {
                delay(350)
                if (entered == next) entered = ""
            }
        }
    }

    fun onBackspace() {
        if (vm.remainingCooldownSeconds() > 0) return
        vm.hooks.cancelBiometricPrompt()
        attemptController.cancelCurrent()
        error = false; bioMessage = ""; vm.lockBiometricMessage = null
        entered = entered.dropLast(1)
    }

    val effectiveBioMessage = bioMessage.ifEmpty { vm.lockBiometricMessage.orEmpty() }
    val showLoaderBar = (showPinLoader || showColdStartLoader) && !error && !isCooling

    Column(
        Modifier.fillMaxSize().background(w.bg).padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // ---- top identity ----
        Column(Modifier.padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).background(w.surface).wikiBorder(w.border), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, null, tint = w.accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Likkho Locked", style = serifStyle(24.sp, w.text))
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    isCooling -> "Too many failed attempts. Try again in ${cooldown}s"
                    vm.biometricsEnabled -> "Unlock with Biometrics or enter your passcode"
                    else -> "Enter your passcode to unlock"
                },
                style = sansStyle(12.sp, w.muted, FontWeight.Normal), textAlign = TextAlign.Center,
            )
        }

        // ---- dots & verifying loader ----
        Column(Modifier.padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(maxOf(dotCount, entered.length)) { i ->
                    val filled = i < entered.length
                    val bad = error || isCooling
                    val fill = when { bad -> w.danger; filled -> w.accent; else -> Color.Transparent }
                    val line = when { bad -> w.danger; filled -> w.accent; else -> w.border }
                    Box(
                        Modifier.size(16.dp).scale(if (filled && !bad) 1.1f else 1f)
                            .clip(CircleShape).background(fill)
                            .wikiBorderCircle(line)
                    )
                }
            }
            if (isCooling) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, tint = w.danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Locked for $cooldown seconds due to wrong attempts", style = sansStyle(12.sp, w.danger, FontWeight.SemiBold))
                }
            } else if (error) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, tint = w.danger, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Incorrect passcode", style = sansStyle(12.sp, w.danger, FontWeight.Medium))
                }
            } else if (showLoaderBar) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier.widthIn(max = 180.dp).fillMaxWidth().height(3.dp),
                    color = w.accent,
                    trackColor = w.hairline,
                    strokeCap = StrokeCap.Butt
                )
                Spacer(Modifier.height(6.dp))
                Text("Verifying…", style = sansStyle(11.sp, w.muted, FontWeight.Medium))
            }
            if (effectiveBioMessage.isNotEmpty() && !error && !isCooling) {
                Spacer(Modifier.height(12.dp))
                Text(effectiveBioMessage, style = sansStyle(12.sp, w.muted, FontWeight.Normal), textAlign = TextAlign.Center)
            }
        }

        // ---- keypad ----
        Column(Modifier.widthIn(max = 280.dp).fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { d -> KeyBtn(Modifier.weight(1f), !isCooling, { onDigit(d) }) { c -> Text(d, style = serifStyle(20.sp, c, FontWeight.SemiBold)) } }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (vm.biometricsEnabled) {
                    KeyBtn(Modifier.weight(1f), !isCooling, { triggerBiometric() }, accent = true) { c ->
                        Icon(Icons.Outlined.Fingerprint, "Unlock with Biometrics", tint = c, modifier = Modifier.size(24.dp))
                    }
                } else Spacer(Modifier.weight(1f))
                KeyBtn(Modifier.weight(1f), !isCooling, { onDigit("0") }) { c -> Text("0", style = serifStyle(20.sp, c, FontWeight.SemiBold)) }
                KeyBtn(Modifier.weight(1f), !isCooling, { onBackspace() }, muted = true) { c ->
                    Icon(Icons.Outlined.Backspace, "Backspace", tint = c, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private fun Modifier.wikiBorderCircle(color: Color): Modifier =
    this.then(Modifier.border(0.5.dp, color, CircleShape))

@Composable
private fun KeyBtn(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    accent: Boolean = false,
    muted: Boolean = false,
    content: @Composable (Color) -> Unit,
) {
    val w = wiki()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg = when {
        pressed && muted -> w.hairline
        pressed -> w.accent
        accent -> w.accent.copy(alpha = 0.10f)
        else -> w.surface
    }
    val line = if (accent) w.accent else w.border
    val fg = when {
        pressed && !muted -> Color.White
        accent -> w.accent
        muted -> w.muted
        else -> w.text
    }
    Box(
        modifier.height(56.dp).background(bg, RectangleShape).wikiBorder(line).alpha(if (enabled) 1f else 0.4f)
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(fg) }
}
