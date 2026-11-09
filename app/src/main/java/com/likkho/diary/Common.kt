package com.likkho.diary

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.outlined.ErrorOutline

// ============================================================================
// Building blocks reused by every screen. Everything is SQUARE (no rounded corners),
// 1dp border, same as the React app. Only switches and passcode dots are round.
// ============================================================================

fun Modifier.wikiBorder(color: Color, width: Dp = 0.5.dp): Modifier = this.border(width, color, RectangleShape)

fun Modifier.noRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}

/** Square icon button 40x40 with border (like React header buttons). */
@Composable
fun IconBtn(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    bordered: Boolean = true,
    tint: Color = wiki().text,
    bg: Color = wiki().bg,
    active: Boolean = false,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
) {
    val w = wiki()
    val fill = if (active) w.accent.copy(alpha = 0.12f) else bg
    val line = if (active) w.accent else if (bordered) w.border else Color.Transparent
    Box(
        modifier = modifier
            .size(size)
            .background(fill, RectangleShape)
            .wikiBorder(line)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = if (active) w.accent else tint, modifier = Modifier.size(iconSize))
    }
}

enum class BtnKind { PRIMARY, DANGER, OUTLINE, SUCCESS }

/** Square text button (+optional icon). PRIMARY = #3366cc, DANGER = #b32424, OUTLINE = border + surface. */
@Composable
fun WikiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.OUTLINE,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 40.dp,
    fontSize: Int = 12,
    mono: Boolean = false,
) {
    val w = wiki()
    val (bg, fg, line) = when (kind) {
        BtnKind.PRIMARY -> Triple(w.accent, Color.White, w.accentDark)
        BtnKind.DANGER -> Triple(w.danger, Color.White, w.dangerDark)
        BtnKind.SUCCESS -> Triple(w.success, Color.White, w.success)
        BtnKind.OUTLINE -> Triple(w.surface, w.text, w.border)
    }
    Row(
        modifier = modifier
            .height(height)
            .background(bg, RectangleShape)
            .wikiBorder(line)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = (if (mono) monoStyle(fontSize.sp, fg, FontWeight.SemiBold) else sansStyle(fontSize.sp, fg, FontWeight.SemiBold)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 44x24 pill switch (the only rounded thing besides dots). */
@Composable
fun WikiSwitch(checked: Boolean) {
    val w = wiki()
    Box(
        modifier = Modifier
            .width(44.dp).height(24.dp)
            .clip(CircleShape)
            .background(if (checked) w.accent else w.surface)
            .border(0.5.dp, if (checked) w.accent else w.border, CircleShape)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(if (checked) Color.White else w.muted))
    }
}

/** Single-line square text field (h=36). */
@Composable
fun WikiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
    height: Dp = 36.dp,
    textStyle: TextStyle? = null,
    trailing: (@Composable () -> Unit)? = null,
    mono: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val w = wiki()
    val st = textStyle ?: (if (mono) monoStyle(12.sp, w.text) else sansStyle(12.sp, w.text, FontWeight.Normal))
    Row(
        modifier = modifier
            .height(height)
            .background(w.surface, RectangleShape)
            .wikiBorder(w.border)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = st.copy(color = w.muted.copy(alpha = 0.7f)), maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = st,
                cursorBrush = SolidColor(w.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password && keyboardType == KeyboardType.Text) KeyboardType.Password else keyboardType,
                    imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(text, style = sansStyle(12.sp, wiki().text, FontWeight.Medium), modifier = Modifier.padding(bottom = 4.dp))
}

/** Modal dialog card: header (surface bg, title, close X) + content. Tap outside = dismiss. */
@Composable
fun WikiDialog(
    onDismiss: () -> Unit,
    title: String,
    titleIcon: ImageVector? = null,
    titleIconTint: Color = wiki().accent,
    maxWidth: Dp = 384.dp,
    dismissOnOutside: Boolean = true,
    showClose: Boolean = true,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val w = wiki()
    Dialog(
        onDismissRequest = { if (dismissOnOutside) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = dismissOnOutside),
    ) {
        Box(
            Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .background(w.bg, RectangleShape)
                    .wikiBorder(w.border)
            ) {
                Row(
                    Modifier.fillMaxWidth().background(w.surface).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (titleIcon != null) {
                        Icon(titleIcon, null, tint = titleIconTint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = serifStyle(17.sp, w.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, style = sansStyle(10.sp, w.muted, FontWeight.Normal))
                    }
                    if (showClose) {
                        Icon(
                            Icons.Outlined.Close, "Close", tint = w.muted,
                            modifier = Modifier.size(22.dp).clickable { onDismiss() }.padding(2.dp),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.hairline))
                Column(Modifier.verticalScroll(rememberScrollState())) { content() }
            }
        }
    }
}

/** Standard confirm-delete style dialog body: message + Cancel / action buttons. */
@Composable
fun ConfirmButtons(
    cancelText: String = "Cancel",
    confirmText: String,
    confirmIcon: ImageVector? = null,
    confirmKind: BtnKind = BtnKind.DANGER,
    busy: Boolean = false,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
        WikiButton(cancelText, onCancel, enabled = !busy)
        Spacer(Modifier.width(8.dp))
        WikiButton(confirmText, onConfirm, kind = confirmKind, icon = confirmIcon, enabled = !busy)
    }
}

enum class BannerKind { SUCCESS, ERROR, INFO }

/** Bordered banner (success green / error red / info blue). */
@Composable
fun StatusBanner(text: String, kind: BannerKind, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val w = wiki()
    val c = when (kind) { BannerKind.SUCCESS -> w.success; BannerKind.ERROR -> w.danger; BannerKind.INFO -> w.accent }
    Row(
        modifier.fillMaxWidth()
            .background(c.copy(alpha = 0.10f), RectangleShape)
            .wikiBorder(c)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            icon ?: if (kind == BannerKind.SUCCESS) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            null, tint = c, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = sansStyle(12.sp, if (kind == BannerKind.ERROR) c else w.text, FontWeight.Medium))
    }
}

/** Floating bottom pill used for "Settings toast", "Export processing/status" banners. */
@Composable
fun FloatingBanner(text: String, kind: BannerKind, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val w = wiki()
    val c = when (kind) { BannerKind.SUCCESS -> w.success; BannerKind.ERROR -> w.danger; BannerKind.INFO -> w.accent }
    Row(
        modifier
            .widthIn(max = 384.dp)
            .background(w.bg, RectangleShape)
            .wikiBorder(c)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        Text(text, style = sansStyle(12.sp, c, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Screen header (h=56, surface bg, bottom border): back arrow + serif title + optional trailing. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val w = wiki()
    Column(modifier.fillMaxWidth().background(w.surface)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconBtn(Icons.Outlined.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = serifStyle(20.sp, w.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = monoStyle(10.sp, w.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) trailing()
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.border))
    }
}

@Composable
fun HairlineDivider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(wiki().hairline))
}

/** Empty-state block used by Home / Saved words / Passwords / Backed-up diaries. */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val w = wiki()
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).background(w.surface).wikiBorder(w.border), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = w.muted, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = serifStyle(20.sp, w.text))
        Spacer(Modifier.height(4.dp))
        Text(message, style = sansStyle(12.sp, w.muted, FontWeight.Normal), modifier = Modifier.widthIn(max = 300.dp))
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}
