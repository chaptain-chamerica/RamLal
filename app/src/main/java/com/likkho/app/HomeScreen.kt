package com.likkho.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.likkho.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect

/**
 * HOME (App.tsx homepage).
 *  Header: "Likkho" (or search field) | Search button | "Lock" button | hamburger -> Settings
 *  List: pinned first, then newest. Tap row = open (locked diary asks passcode). 3-dots menu: Pin / Share / Export / Delete.
 *  FAB "+ Create" bottom-right -> blank workspace.
 */
@Composable
fun HomeScreen(vm: AppViewModel) {
    val w = wiki()
    val logs = vm.sortedLogs
    val q = vm.searchQuery.trim()

    Box(Modifier.fillMaxSize().background(w.bg)) {
        Column(Modifier.fillMaxSize()) {
            // ---------------- header ----------------
            Column(Modifier.fillMaxWidth().background(w.surface)) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (vm.isSearchOpen) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        Icon(Icons.Outlined.Search, null, tint = w.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        WikiTextField(
                            vm.searchQuery, { vm.searchQuery = it },
                            Modifier.weight(1f).focusRequester(focus), "Search diaries...", height = 36.dp,
                            onDone = {},
                        )
                        Spacer(Modifier.width(8.dp))
                        IconBtn(Icons.Outlined.Close, "Close search", { vm.isSearchOpen = false; vm.searchQuery = "" }, size = 36.dp, tint = w.muted, iconSize = 16.dp)
                    } else {
                        Text("Likkho", style = serifStyle(24.sp, w.text), modifier = Modifier.weight(1f))
                        IconBtn(Icons.Outlined.Search, "Search diaries", { vm.isSearchOpen = true }, iconSize = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        // Instant Lock (no passcode set => opens Settings)
                        Row(
                            Modifier.height(40.dp).background(w.bg).wikiBorder(w.border).clickable { vm.handleInstantLock() }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Lock, "Instant Lock", tint = w.accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Lock", style = sansStyle(12.sp, w.text, FontWeight.SemiBold), maxLines = 1)
                        }
                        Spacer(Modifier.width(6.dp))
                        IconBtn(Icons.Outlined.Menu, "Open Settings", { vm.navigateTo(Route.SETTINGS) })
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.border))
            }

            // ---------------- list ----------------
            if (logs.isEmpty()) {
                EmptyState(
                    Icons.Outlined.MenuBook,
                    if (q.isNotEmpty()) "No Matching Diaries Found" else "No Logs Yet",
                    if (q.isNotEmpty()) "No diary entry contains \"${vm.searchQuery}\"." else "Tap the create button below to start writing.",
                )
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(logs, key = { it.id }) { log ->
                        DiaryRow(vm, log)
                        HairlineDivider()
                    }
                }
            }
        }

        // ---------------- FAB ----------------
        Row(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 24.dp)
                .height(56.dp).background(w.accent).wikiBorder(w.accentDark)
                .clickable { vm.navigateTo(Route.WORKSPACE, null) }.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, "Create new diary log", tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Create", style = serifStyle(16.sp, Color.White, FontWeight.SemiBold))
        }
    }
}

@Composable
private fun DiaryRow(vm: AppViewModel, log: DiaryLog) {
    val w = wiki()
    val menuOpen = vm.openMenuLogId == log.id
    Row(
        Modifier.fillMaxWidth().clickable { vm.openLog(log) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PfpAvatar(log.pfp, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (log.pinned) { Icon(Icons.Outlined.PushPin, null, tint = w.accent, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
                if (log.diaryLockPin != null) { Icon(Icons.Outlined.Lock, "Locked Diary", tint = w.danger, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)) }
                Text(log.heading, style = serifStyle(17.sp, w.text.copy(alpha = 0.90f), weight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val preview = if (log.diaryLockPin != null || log.plainPreview == "Reminder scheduled.") "" else log.plainPreview
            if (preview.isNotEmpty()) {
                Text(preview, style = proseStyle(14.sp, w.text.copy(alpha = 0.65f)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            Column(Modifier.padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val timestampStyle = monoStyle(11.sp, w.muted.copy(alpha = 0.78f))
                Text("Created: ${log.dateStamp} · ${log.timeStamp}", style = timestampStyle)
                val rem = log.reminderAt
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Modified: ${log.updatedDateStamp} · ${log.updatedTimeStamp}", style = timestampStyle)
                    if (rem != null && rem > System.currentTimeMillis()) {
                        Text(" · ", style = timestampStyle)
                        Icon(Icons.Outlined.NotificationsActive, null, tint = w.accent, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(fmtTime(rem), style = monoStyle(11.sp, w.accent))
                    }
                }
            }
        }
        // 3-dots
        Box {
            IconBtn(Icons.Outlined.MoreVert, "Log options", { vm.openMenuLogId = if (menuOpen) null else log.id }, bordered = false, tint = w.muted)
            DropdownMenu(
                expanded = menuOpen, onDismissRequest = { vm.openMenuLogId = null },
                modifier = Modifier.width(176.dp), shape = RectangleShape, containerColor = w.bg,
                border = BorderStroke(0.5.dp, w.border),
            ) {
                MenuItem(Icons.Outlined.PushPin, if (log.pinned) "Unpin from Top" else "Pin to Top", w.accent, w.text) { vm.togglePin(log.id) }
                MenuItem(Icons.Outlined.Share, "Share (.likkho)", w.accent, w.text, enabled = vm.exportProcessingBanner == null) { vm.shareLog(log) }
                MenuItem(Icons.Outlined.Download, if (vm.exportProcessingBanner != null) "Exporting..." else "Export", w.accent, w.text, enabled = vm.exportProcessingBanner == null) { vm.requestExport(log) }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(w.hairline))
                MenuItem(Icons.Outlined.Delete, "Delete", w.danger, w.danger) { vm.requestDelete(log.id) }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, iconTint: Color, textColor: Color, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = sansStyle(12.sp, textColor, FontWeight.Medium)) },
        leadingIcon = { Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp)) },
        onClick = onClick, enabled = enabled,
    )
}

/**
 * 1:1 square avatar. When no custom PFP is set, displays the official app icon.
 * Emoji/Text PFP => colored box. Photo PFP => bitmap/photo icon.
 */
@Composable
fun PfpAvatar(pfp: Pfp?, size: Dp, modifier: Modifier = Modifier) {
    val w = wiki()
    if (pfp == null || (pfp.source == null && pfp.text.isNullOrBlank())) {
        Box(
            modifier = modifier
                .size(size)
                .background(Color.White)
                .wikiBorder(w.border, 0.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_app_open_icon),
                contentDescription = "App Icon",
                modifier = Modifier.fillMaxSize().padding(size * 0.08f),
                contentScale = ContentScale.Fit,
            )
        }
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(Color(pfp.bgColor))
                .wikiBorder(w.border, 0.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (pfp.source != null) {
                val loader = LocalImageBitmapLoader.current
                val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = pfp.source) {
                    value = loader?.invoke(pfp.source)
                }
                if (bmp != null) {
                    Image(
                        bitmap = bmp!!,
                        contentDescription = "Diary Photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Outlined.Image, "Diary Photo", tint = Color.White, modifier = Modifier.size(size / 2))
                }
            } else {
                Text(
                    pfp.text.orEmpty(),
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = WikiFonts.serif,
                        fontWeight = FontWeight.Normal,
                        color = Color(pfp.textColor),
                        fontSize = (size.value * 0.45f * pfp.textScale).sp,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Floating processing / status banners shown above the FAB (App.tsx bottom-20). */
@Composable
fun HomeFloatingBanners(vm: AppViewModel, modifier: Modifier = Modifier) {
    val w = wiki()
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        vm.exportProcessingBanner?.let {
            FloatingBanner(it, BannerKind.INFO, leading = { CircularProgressIndicator(Modifier.size(16.dp), color = w.accent, strokeWidth = 2.dp) })
        }
        val st = vm.exportStatusBanner
        if (st != null && vm.exportProcessingBanner == null) {
            FloatingBanner(st, BannerKind.SUCCESS, leading = { Icon(Icons.Outlined.CheckCircle, null, tint = w.success, modifier = Modifier.size(16.dp)) })
        }
    }
}
