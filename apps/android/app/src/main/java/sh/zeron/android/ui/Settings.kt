package sh.zeron.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sh.zeron.android.BuildConfig
import sh.zeron.android.app.AppModel
import uniffi.zeron_core.coreVersion
import uniffi.zeron_core.relativeTimeLabel

@Composable
fun SettingsScreen(app: AppModel, onBack: () -> Unit, onArchived: () -> Unit, modifier: Modifier = Modifier) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val client = app.client ?: return
    val devices = remember(app.workspaceRevision) { client.devices() }
    var confirmSignOut by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<uniffi.zeron_core.DeviceView?>(null) }
    val now = System.currentTimeMillis()

    Column(modifier.fillMaxSize().background(Z.background).safeTop().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Z.text) }
            Text("Settings", color = Z.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        // Account
        Card {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Z.accentSoft), contentAlignment = Alignment.Center) {
                    val initial = (app.accountName.ifBlank { app.accountEmail }).firstOrNull()?.uppercase() ?: "Z"
                    Text(initial, color = Z.accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(app.accountName.ifBlank { "Signed in" }, color = Z.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val sub = listOf(app.accountEmail, app.accountOrg).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, color = Z.secondary, fontSize = 13.sp)
                }
            }
        }

        SectionLabel("Devices")
        Card {
            if (devices.isEmpty()) Text("No devices yet", color = Z.secondary, modifier = Modifier.padding(16.dp))
            devices.forEachIndexed { i, d ->
                if (i > 0) Hairline(Modifier.padding(start = 16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (d.online) Z.success else Z.tertiary.copy(alpha = 0.4f)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name + if (d.isSelf) " (this phone)" else "", color = Z.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        val detail = buildList {
                            add(d.platform)
                            d.version?.let { add("v$it") }
                            if (!d.online) d.lastSeenMs?.let { add("seen ${relativeTimeLabel(it, now)} ago") }
                            if (d.sessionCount > 0u) add("${d.sessionCount} sessions")
                        }.joinToString(" · ")
                        Text(detail, color = Z.subline, fontSize = 12.sp)
                    }
                    IconButton(onClick = { renaming = d }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Edit, "Rename ${d.name}", tint = Z.tertiary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        SectionLabel("Library")
        Card {
            Row(Modifier.fillMaxWidth().clickable(onClick = onArchived).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Archived sessions", color = Z.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, tint = Z.tertiary)
            }
        }

        if (app.voice.available) {
            SectionLabel("Voice")
            VoiceCard(app)
        }

        SectionLabel("Agent accounts")
        AgentAccountsCard(app)

        SectionLabel("Security")
        SecurityCard(app)

        SectionLabel("Notifications")
        NotificationsCard()

        SectionLabel("Wallpaper")
        WallpaperCard()

        SectionLabel("About")
        Card {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Version", color = Z.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("${BuildConfig.VERSION_NAME} · core ${coreVersion()}", color = Z.secondary, fontSize = 13.sp)
            }
            Hairline(Modifier.padding(start = 16.dp))
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("This device", color = Z.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(app.deviceId, color = Z.secondary, fontSize = 13.sp, fontFamily = LocalMono.current)
            }
        }

        Spacer(Modifier.height(20.dp))
        Card {
            Box(Modifier.fillMaxWidth().clickable { confirmSignOut = true }.padding(16.dp)) {
                Text("Sign out", color = Z.danger, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }

    renaming?.let { device ->
        var name by remember(device.id) { mutableStateOf(device.name) }
        var error by remember(device.id) { mutableStateOf<String?>(null) }
        fun save() {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return
            try {
                client.renameDevice(device.id, trimmed)
                app.refresh()
                renaming = null
            } catch (e: Exception) {
                error = (e as? uniffi.zeron_core.CoreException)?.message?.removePrefix("message=") ?: e.message
            }
        }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        name, { name = it; error = null }, singleLine = true, label = { Text("Name") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
                    )
                    error?.let { Text(it, color = Z.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = { TextButton(onClick = ::save, enabled = name.isNotBlank() && name.trim() != device.name) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("This phone will forget your sessions until you sign in again.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; app.signOut() }) { Text("Sign out", color = Z.danger) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Z.tertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 6.dp))
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Z.elevated)) { content() }
}

@Composable
fun ArchivedScreen(app: AppModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val client = app.client ?: return
    val rows = remember(app.workspaceRevision) { client.archivedSessions() }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Z.background).safeTop().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Z.text) }
            Text("Archived", color = Z.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (rows.isEmpty()) EmptyState("Nothing archived", "Archived sessions show up here.")
        LazyColumn {
            items(rows, key = { it.id }) { SessionItem(app, it, onOpen) }
        }
    }
}

@Composable
private fun WallpaperCard() {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var effectDialog by remember { mutableStateOf(false) }
    val version = sh.zeron.android.design.WallpaperStore.version
    val store = sh.zeron.android.design.WallpaperStore
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            var name = "Photo"
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) name = c.getString(i)
            }
            store.set(uri, name)
        }
    }
    val isSet = remember(version) { store.isSet }
    Card {
        Column(
            Modifier.fillMaxWidth().clickable {
                picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }.padding(16.dp),
        ) {
            Text(if (isSet) "Change wallpaper…" else "Choose wallpaper…", color = Z.text, fontSize = 15.sp)
            Text(if (isSet) store.name.orEmpty() else "Shown behind new chats and the sessions list", color = Z.subline, fontSize = 12.sp)
        }
        if (isSet) {
            Hairline(Modifier.padding(start = 16.dp))
            Column(Modifier.fillMaxWidth().clickable { effectDialog = true }.padding(16.dp)) {
                Text("Effect", color = Z.text, fontSize = 15.sp)
                val e = remember(version) { store.effect }
                Text("${store.label(e)} — ${store.detail(e)}", color = Z.subline, fontSize = 12.sp)
            }
            Hairline(Modifier.padding(start = 16.dp))
            Box(Modifier.fillMaxWidth().clickable { store.remove() }.padding(16.dp)) {
                Text("Remove wallpaper", color = Z.danger, fontSize = 15.sp)
            }
        }
    }
    if (effectDialog) {
        AlertDialog(
            onDismissRequest = { effectDialog = false },
            title = { Text("Wallpaper effect") },
            text = {
                Column {
                    for (e in store.allEffects) {
                        Row(
                            Modifier.fillMaxWidth().clickable { store.effect = e; effectDialog = false }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(store.label(e), color = Z.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(store.detail(e), color = Z.subline, fontSize = 12.sp)
                            }
                            if (e == store.effect) Text("✓", color = Z.accent, fontSize = 18.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { effectDialog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun NotificationsCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val n = sh.zeron.android.notify.Notifications
    // Re-read the stored switches after any change.
    var tick by remember { mutableStateOf(0) }
    val granted = remember(tick) { n.permissionGranted(context) }
    val on = remember(tick) { n.enabled(context) && granted }
    fun changed() { tick++; sh.zeron.android.notify.KeepAliveService.sync(context) }

    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { ok ->
        if (ok) n.setEnabled(context, true) else openNotificationSettings(context)
        changed()
    }

    Card {
        SwitchRow("Session alerts", "Run finished, waiting on you, or failed — with the same chimes as the desktop.", on) { want ->
            if (want) {
                n.setEnabled(context, true)
                if (!granted && android.os.Build.VERSION.SDK_INT >= 33) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) else if (!granted) openNotificationSettings(context)
            } else n.setEnabled(context, false)
            changed()
        }
        if (on) {
            for (c in sh.zeron.android.notify.Category.entries) {
                Hairline(Modifier.padding(start = 16.dp))
                SwitchRow(c.title, null, n.wants(context, c)) { v -> n.setWants(context, c, v); tick++ }
            }
            Hairline(Modifier.padding(start = 16.dp))
            Column(Modifier.fillMaxWidth().clickable { openNotificationSettings(context) }.padding(16.dp)) {
                Text("Sounds and channels", color = Z.text, fontSize = 15.sp)
                Text("Change a chime or silence one kind in system settings", color = Z.subline, fontSize = 12.sp)
            }
            Hairline(Modifier.padding(start = 16.dp))
            Column(
                Modifier.fillMaxWidth().clickable {
                    try { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }.padding(16.dp),
            ) {
                Text("Keep running in the background", color = Z.text, fontSize = 15.sp)
                Text("Zeron holds a quiet notification while connected. Exempting it from battery optimization keeps alerts reliable.", color = Z.subline, fontSize = 12.sp)
            }
        } else if (!granted && n.enabled(context)) {
            Hairline(Modifier.padding(start = 16.dp))
            Column(Modifier.fillMaxWidth().clickable { openNotificationSettings(context) }.padding(16.dp)) {
                Text("Notifications are blocked", color = Z.warning, fontSize = 15.sp)
                Text("Allow them for Zeron in system settings", color = Z.subline, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SecurityCard(app: AppModel) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? androidx.fragment.app.FragmentActivity
    var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(app.lockEnabled) }
    val canLock = activity != null && AppLock.available(activity)
    Card {
        SwitchRow(
            "Require unlock",
            if (canLock) "Ask for your fingerprint, face or screen lock when Zeron opens or returns after a while. Also hides it from recent apps."
            else "Set up a fingerprint, face or screen lock in Android settings first.",
            on && canLock,
        ) { want ->
            if (!canLock || activity == null) return@SwitchRow
            if (want) {
                // Prove it works before turning it on, so a broken unlock can't lock the user out.
                AppLock.prompt(activity) { app.lockEnabled = true; app.locked = false; on = true }
            } else {
                app.lockEnabled = false
                on = false
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Z.text, fontSize = 15.sp)
            subtitle?.let { Text(it, color = Z.subline, fontSize = 12.sp) }
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked, onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Z.accent),
        )
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    try {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
    }
}

@Composable
private fun VoiceCard(app: AppModel) {
    val voice = app.voice
    var tick by remember { mutableStateOf(0) }
    var pickHost by remember { mutableStateOf(false) }
    var pickStyle by remember { mutableStateOf(false) }
    val hosts = remember(app.workspaceRevision) { voice.hosts }
    val host = remember(tick, hosts) { hosts.firstOrNull { it.id == voice.selectedHost } ?: hosts.singleOrNull() }
    val style = remember(tick) { voice.selectedStyle }
    Card {
        Column(Modifier.fillMaxWidth().clickable { pickHost = true }.padding(16.dp)) {
            Text("Computer to call", color = Z.text, fontSize = 15.sp)
            Text(host?.name ?: if (hosts.isEmpty()) "None online with voice" else "Ask each time", color = Z.subline, fontSize = 12.sp)
        }
        Hairline(Modifier.padding(start = 16.dp))
        Column(Modifier.fillMaxWidth().clickable { pickStyle = true }.padding(16.dp)) {
            Text("Voice", color = Z.text, fontSize = 15.sp)
            Text(style?.replaceFirstChar { it.uppercase() } ?: "Default", color = Z.subline, fontSize = 12.sp)
        }
    }
    if (pickHost) {
        PickerSheet(
            title = "Computer to call",
            rows = hosts.map { PickerRow(it.id, it.name, subtitle = it.platform) },
            selectedKey = host?.id,
            onPick = { id -> voice.selectedHost = id; tick++; pickHost = false },
            onDismiss = { pickHost = false },
        )
    }
    if (pickStyle) {
        PickerSheet(
            title = "Voice",
            rows = voice.styles.map { PickerRow(it, it.replaceFirstChar { c -> c.uppercase() }) },
            selectedKey = style,
            onPick = { v -> voice.selectedStyle = v; tick++; pickStyle = false },
            onDismiss = { pickStyle = false },
        )
    }
}
