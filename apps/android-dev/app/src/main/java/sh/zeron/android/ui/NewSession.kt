package sh.zeron.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import sh.zeron.android.app.AppModel
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.ChatConfig
import uniffi.zeron_core.FolderListing
import uniffi.zeron_core.NewSession
import uniffi.zeron_core.OutgoingAttachment
import uniffi.zeron_core.SandboxLevel
import uniffi.zeron_core.SendRequest
import uniffi.zeron_core.SessionTarget
import uniffi.zeron_core.WorktreeSpec
import uniffi.zeron_core.fallbackHarnesses
import uniffi.zeron_core.fallbackModels
import uniffi.zeron_core.reasoningLabel

private data class Target(val spaceId: String?, val deviceId: String, val label: String, val sub: String?, val path: String?, val git: Boolean, val colorIndex: Int?) {
    val key get() = spaceId ?: "home:$deviceId"
    val title get() = label + (sub?.let { " @ $it" } ?: "")
}

/**
 * The home screen: a new chat, in the Claude / Gemini mould. A menu button
 * opens the sessions drawer; the agent picker sits top-left; the composer holds
 * the model picker. Project, branch and worktree are chips above it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(app: AppModel, onMenu: () -> Unit, onProfile: () -> Unit, onCreated: (String) -> Unit) {
    val client = app.client ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision = app.workspaceRevision
    val projects = remember(revision) { client.projects() }
    val hosts = remember(revision) { client.executionDevices() }

    // Like the desktop: one project row per repository (every checkout of it, on any device, is one row),
    // plus a separate device picker for where it runs.
    class Group(val key: String, val name: String, val colorIndex: Int, val members: List<uniffi.zeron_core.ProjectView>)
    val groups = remember(projects) {
        projects.groupBy { it.groupKey }
            .map { (k, m) -> Group(k, m.first().groupName.ifBlank { m.first().name }, m.first().colorIndex.toInt(), m) }
            .sortedBy { it.name.lowercase() }
    }
    val execDevices = remember(hosts) { hosts.sortedBy { it.name.lowercase() } }
    var spaceId by remember { mutableStateOf<String?>(null) }
    var noProject by remember { mutableStateOf(false) }
    var deviceChoice by remember { mutableStateOf<String?>(null) }
    // Restore the last target; else the project with the freshest session; else a device's home folder.
    LaunchedEffect(projects, hosts) {
        val key = app.lastTargetKey
        when {
            spaceId != null && projects.any { it.id == spaceId } -> {}
            noProject && execDevices.isNotEmpty() -> if (execDevices.none { it.id == deviceChoice }) deviceChoice = execDevices.first().id
            key?.startsWith("home:") == true && execDevices.any { it.id == key.removePrefix("home:") } -> {
                noProject = true; spaceId = null; deviceChoice = key.removePrefix("home:")
            }
            key != null && projects.any { it.id == key } -> { spaceId = key; noProject = false }
            projects.isNotEmpty() -> {
                spaceId = projects.maxByOrNull { p -> p.sessions.firstOrNull()?.lastActivityMs ?: p.createdAtMs }!!.id
                noProject = false
            }
            execDevices.isNotEmpty() -> { noProject = true; spaceId = null; deviceChoice = execDevices.first().id }
        }
    }
    val selected = if (noProject) null else projects.firstOrNull { it.id == spaceId }
    val effectiveDevice = selected?.deviceId ?: deviceChoice?.takeIf { id -> execDevices.any { it.id == id } } ?: execDevices.firstOrNull()?.id
    val target: Target? = when {
        selected != null -> Target(selected.id, selected.deviceId, selected.name, selected.deviceName, selected.path, selected.gitDetected, selected.colorIndex.toInt())
        effectiveDevice != null -> Target(null, effectiveDevice, "No project", execDevices.firstOrNull { it.id == effectiveDevice }?.name, null, false, null)
        else -> null
    }
    val nothingToRunOn = projects.isEmpty() && execDevices.isEmpty()
    var showProjects by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }

    /** Desktop `pick_project`: stay on the current checkout, else one on the current device, else the first. */
    fun pickProject(g: Group) {
        val pick = g.members.firstOrNull { it.id == spaceId } ?: g.members.firstOrNull { it.deviceId == effectiveDevice } ?: g.members.first()
        spaceId = pick.id
        noProject = false
    }
    fun pickNoProject() {
        deviceChoice = effectiveDevice
        spaceId = null
        noProject = true
    }
    // A new session starts from the most recently active one (agent, model, thinking level); Medium / Slow
    // only when there is none. Fast mode is never carried over: it is opted into per session.
    val latest = remember {
        val f = client.frontPage()
        (f.pinned + f.sections.flatMap { it.sessions } + f.recent)
            .filter { it.parentChatId == null && it.harness != null }
            .maxByOrNull { it.lastActivityMs }
    }
    var harness by remember { mutableStateOf(latest?.harness ?: app.lastHarness ?: "") }
    var model by remember { mutableStateOf<String?>(if (latest != null) latest.model else app.lastModel) }
    var effort by remember { mutableStateOf<String?>(if (latest != null) latest.reasoning else app.lastEffort) }
    var options by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showConfig by remember { mutableStateOf(false) }
    var newWorktree by remember { mutableStateOf(false) }
    var branch by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf(app.newSessionText) }
    val images = remember { mutableStateListOf<Pair<OutgoingAttachment, Bitmap?>>() }
    var busy by remember { mutableStateOf(false) }
    val mentions = remember { MentionIndex() }
    var error by remember { mutableStateOf<String?>(null) }
    var folderBrowser by remember { mutableStateOf(false) }
    LaunchedEffect(text) { app.newSessionText = text }

    val deviceId = target?.deviceId
    // `first` says whether the host's own list has arrived: until then the bundled fallback must not
    // knock a remembered agent back to the first one (Claude Code).
    val harnessList by produceState(false to fallbackHarnesses(), deviceId) {
        if (deviceId != null) value = true to client.listHarnesses(deviceId).filter { it.offered }.ifEmpty { fallbackHarnesses() }
    }
    val harnesses = harnessList.second
    LaunchedEffect(harnessList) {
        if (harness.isEmpty() || (harnessList.first && harnesses.none { it.id == harness })) {
            harness = harnesses.firstOrNull { it.id == harness }?.id ?: harnesses.firstOrNull()?.id.orEmpty()
        }
    }
    // The catalog is tagged with the agent it was loaded for, so a stale list from the previous agent is never used.
    val catalog by produceState(harness to emptyList<uniffi.zeron_core.ModelInfo>(), deviceId, harness) {
        value = harness to emptyList()
        if (deviceId != null && harness.isNotEmpty()) value = harness to client.listModels(deviceId, harness).ifEmpty { fallbackModels(harness) }
    }
    val models = if (catalog.first == harness) catalog.second else emptyList()
    // New chats start at Medium thinking and Slow (standard) speed.
    LaunchedEffect(models, model) {
        if (models.isEmpty()) return@LaunchedEffect
        val info = models.firstOrNull { it.id == model } ?: models.first().also { model = it.id; options = defaultOptions(it) }
        if (effort == null || effort !in info.reasoningLevels) effort = defaultEffort(info)
        if (options.isEmpty() || options.keys.any { k -> info.options.none { it.id == k } }) options = sanitizeOptions(info, app.lastOptions)
    }
    val refs by produceState(emptyList<uniffi.zeron_core.RepoRef>(), target) {
        val t = target
        value = if (t?.git == true && t.path != null) try { client.listRefs(t.deviceId, t.path) } catch (_: Exception) { emptyList() } else emptyList()
    }
    // The branch and worktree choice come back per project; an unknown branch falls to the current one.
    LaunchedEffect(refs, target?.key) {
        val key = target?.key ?: return@LaunchedEffect
        val saved = app.branchFor(key)
        branch = refs.firstOrNull { it.name == saved }?.name ?: refs.firstOrNull { it.current }?.name ?: refs.firstOrNull()?.name
        newWorktree = app.worktreeFor(key)
    }
    fun chooseBranch(name: String) {
        branch = name
        target?.let { app.setBranchFor(it.key, name) }
    }
    fun chooseWorktree(on: Boolean) {
        newWorktree = on
        target?.let { app.setWorktreeFor(it.key, on) }
    }

    // Remember every pick as it is made, not only when a session is sent.
    LaunchedEffect(target?.key) { target?.let { app.lastTargetKey = it.key } }
    LaunchedEffect(harness, model, effort, options) {
        if (model != null && models.any { it.id == model }) {
            app.lastHarness = harness
            app.lastModel = model
            app.lastEffort = effort
            app.lastOptions = options
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        for (uri in uris) try {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            images += OutgoingAttachment("image-${images.size + 1}.jpg", context.contentResolver.getType(uri) ?: "image/jpeg", bytes) to thumb
        } catch (_: Exception) {}
    }

    val ready = target != null && (text.isNotBlank() || images.isNotEmpty()) && !busy

    fun start() {
        val t = target ?: return
        val body = mentions.encode(text.trim())
        if (body.isEmpty() && images.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            try {
                val modelInfo = models.firstOrNull { it.id == model }
                val config = ChatConfig(
                    harness, model, effort?.takeIf { modelInfo?.reasoningLevels?.contains(it) == true },
                    options.ifEmpty { defaultOptions(modelInfo) },
                    SandboxLevel.WORKSPACE_WRITE,
                )
                val chatId = client.createSession(
                    NewSession(
                        if (t.spaceId != null) SessionTarget.Project(t.spaceId) else SessionTarget.Projectless(t.deviceId),
                        config, if (newWorktree) null else branch.takeIf { t.git }, null, null,
                    ),
                )
                val handle = client.openSession(chatId)
                handle.send(
                    SendRequest(
                        body, images.map { it.first },
                        if (newWorktree && t.path != null && branch != null) WorktreeSpec(t.path, branch!!, t.spaceId) else null,
                        BusyPolicy.QUEUE,
                    ),
                )
                app.lastTargetKey = t.key
                app.newSessionText = ""
                text = ""
                mentions.reset()
                images.clear()
                busy = false
                onCreated(chatId)
            } catch (e: Exception) {
                error = (e as? uniffi.zeron_core.CoreException)?.message?.removePrefix("message=") ?: e.message
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Z.background)) {
    WallpaperBackdrop()
    Column(Modifier.fillMaxSize().safeTop().imePadding().navigationBarsPadding()) {
        // Top bar: menu · agent picker · profile
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Rounded.Menu, "Sessions", tint = Z.text) }
            Spacer(Modifier.width(2.dp))
            Dropdown(
                label = harnesses.firstOrNull { it.id == harness }?.label ?: harness.ifEmpty { "Agent" },
                big = true,
                leading = { if (harness.isNotEmpty()) AgentMark(harness, size = 22.dp) },
            ) { close ->
                for (h in harnesses) DropdownMenuItem(
                    text = { Text(h.label) },
                    leadingIcon = { AgentMark(h.id, size = 22.dp) },
                    trailingIcon = { if (h.id == harness) Icon(Icons.Rounded.Check, null, tint = Z.text) },
                    onClick = { close(); if (h.id != harness) { harness = h.id; model = null; effort = null; options = emptyMap() } },
                )
            }
            Spacer(Modifier.weight(1f))
            val initial = (app.accountName.ifBlank { app.accountEmail }).firstOrNull()?.uppercase() ?: "Z"
            Box(
                Modifier.padding(end = 8.dp).size(36.dp).clip(CircleShape).background(Z.accentSoft).clickable(onClick = onProfile),
                contentAlignment = Alignment.Center,
            ) { Text(initial, color = Z.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }

        // Greeting
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (nothingToRunOn) {
                Text("No computer is online", color = Z.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text("Open Zeron on a computer signed in to this account, then start a session on it from here.", color = Z.secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
            } else {
                val first = app.accountName.trim().substringBefore(' ')
                Text(
                    if (first.isNotEmpty()) "Hi $first, what should we build?" else "What should we build?",
                    color = Z.text, fontSize = 28.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, lineHeight = 36.sp,
                )
            }
        }

        error?.let { Text(it, color = Z.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }

        // Context chips: project · branch · worktree
        if (!nothingToRunOn) {
            LazyRow(Modifier.padding(bottom = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    PillChip(
                        label = selected?.let { p -> groups.firstOrNull { it.key == p.groupKey }?.name ?: p.name } ?: "No project",
                        leading = {
                            if (selected != null) ProjectTile(selected.name, selected.colorIndex.toInt(), 18.dp)
                            else Icon(Icons.Rounded.Folder, null, tint = Z.secondary, modifier = Modifier.size(16.dp))
                        },
                        onClick = { showProjects = true },
                    )
                }
                item {
                    val device = execDevices.firstOrNull { it.id == effectiveDevice }
                    PillChip(
                        label = selected?.deviceName ?: device?.name ?: "Device",
                        leading = {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if ((selected?.deviceOnline ?: device?.online) == true) Z.success else Z.tertiary.copy(alpha = 0.5f)))
                        },
                        onClick = { showDevices = true },
                    )
                }
                if (target?.git == true && refs.isNotEmpty()) {
                    item {
                        PillChip(
                            label = (branch ?: "Branch") + if (newWorktree) " · worktree" else "",
                            onClick = { showBranches = true },
                        )
                    }
                }
            }
        }

        val mentionToken = trailingMention(text)
        val mentionFiles = rememberMentionFiles(client, deviceId, null, target?.spaceId, mentionToken)
        MentionPopup(mentionFiles, { f -> mentionToken?.let { text = mentions.insert(text, it, f) } }, Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp))
        val token = trailingInvocation(text)
        val invocations = rememberInvocations(client, deviceId, harness, null, target?.spaceId, token != null)
        if (token != null) InvocationPopup(matchInvocations(invocations, token), { text = insertInvocation(text, token, it) }, Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp))
        // Composer card
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 10.dp).clip(RoundedCornerShape(28.dp)).background(Z.elevated)
                .border(0.5.dp, Z.hairline, RoundedCornerShape(28.dp)).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            if (images.isNotEmpty()) {
                LazyRow(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images.size) { i ->
                        Box(Modifier.size(64.dp)) {
                            images[i].second?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) }
                            Box(
                                Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Z.text.copy(alpha = 0.7f)).clickable { images.removeAt(i) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Close, "Remove", tint = Z.background, modifier = Modifier.size(12.dp)) }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (text.isEmpty()) Text("Message ${harnesses.firstOrNull { it.id == harness }?.label ?: "the agent"}…", color = Z.tertiary, fontSize = 17.sp)
                BasicTextField(
                    text, { text = it }, maxLines = 8, cursorBrush = SolidColor(Z.accent),
                    textStyle = TextStyle(color = Z.text, fontSize = 17.sp, lineHeight = 24.sp), modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Z.controlFill)
                        .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Attach", tint = Z.text, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(8.dp))
                val info = models.firstOrNull { it.id == model }
                val pillLabel = (info?.label ?: "Model") +
                    (effort?.takeIf { info?.reasoningLevels?.contains(it) == true }?.let { " · ${reasoningLabel(it)}" } ?: "") +
                    (if (speedIsFast(info, options)) " · Fast" else "")
                Row(
                    Modifier.clip(CircleShape).background(Z.controlFill).clickable { showConfig = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pillLabel, color = Z.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 230.dp))
                    Spacer(Modifier.width(2.dp))
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Z.tertiary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                if (!ready && !busy && app.voice.available) {
                    VoiceButton(app)
                } else Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (ready) Z.text else Z.controlFill).clickable(enabled = ready, onClick = ::start),
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(color = Z.background, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    else Icon(Icons.Rounded.ArrowUpward, "Start", tint = if (ready) Z.background else Z.tertiary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
    }

    if (showProjects) {
        PickerSheet(
            title = "Project",
            rows = groups.map { g ->
                PickerRow(
                    g.key, g.name,
                    subtitle = if (g.members.size > 1) "On ${g.members.size} devices" else g.members.first().deviceName,
                    leading = { ProjectTile(g.name, g.colorIndex, 30.dp) },
                )
            },
            selectedKey = selected?.groupKey,
            onPick = { key -> groups.firstOrNull { it.key == key }?.let(::pickProject); showProjects = false },
            onDismiss = { showProjects = false },
            searchable = true, placeholder = "Search projects",
            footer = {
                PickerAction(
                    "No project", "Runs in the device's home folder", selected = noProject,
                    leading = { Icon(Icons.Rounded.Folder, null, tint = Z.tertiary, modifier = Modifier.size(30.dp).padding(4.dp)) },
                ) { pickNoProject(); showProjects = false }
                PickerAction(
                    "Add project…", "Pick a folder on a computer",
                    leading = { Icon(Icons.Rounded.Add, null, tint = Z.tertiary, modifier = Modifier.size(30.dp).padding(4.dp)) },
                ) { showProjects = false; folderBrowser = true }
            },
        )
    }

    if (showDevices) {
        // With a project: its checkouts (the path tells two on one device apart). Without: every device.
        val members = selected?.let { s -> projects.filter { it.groupKey == s.groupKey } }
        val rows = if (members != null) members.map { m ->
            val shared = members.count { it.deviceId == m.deviceId } > 1
            PickerRow(m.id, m.deviceName ?: "Device", subtitle = if (shared) m.path else null, muted = !m.deviceOnline, searchText = m.path)
        } else execDevices.map { d -> PickerRow(d.id, d.name, subtitle = d.platform, muted = !d.online) }
        PickerSheet(
            title = "Device",
            rows = rows,
            selectedKey = if (members != null) selected?.id else effectiveDevice,
            onPick = { key -> if (members != null) spaceId = key else deviceChoice = key; showDevices = false },
            onDismiss = { showDevices = false },
            placeholder = "Search devices",
        )
    }

    if (showBranches) {
        PickerSheet(
            title = "Branch",
            rows = refs.map { r ->
                PickerRow(
                    r.name, r.name,
                    subtitle = when {
                        r.current -> "Current checkout"
                        r.worktreePath != null -> "Worktree · ${r.worktreePath}"
                        else -> null
                    },
                )
            },
            selectedKey = branch,
            onPick = { key -> chooseBranch(key); showBranches = false },
            onDismiss = { showBranches = false },
            searchable = true, placeholder = "Search branches",
            footer = {
                Row(
                    Modifier.fillMaxWidth().clickable { chooseWorktree(!newWorktree) }.padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Isolated worktree", color = Z.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("Run in a fresh copy branched from ${branch ?: "this ref"}", color = Z.subline, fontSize = 12.sp)
                    }
                    androidx.compose.material3.Switch(
                        newWorktree, { chooseWorktree(it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Z.accent),
                    )
                }
            },
        )
    }

    if (showConfig) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showConfig = false }, containerColor = Z.elevated) {
            Column(Modifier.navigationBarsPadding()) {
                Text("Run settings", color = Z.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp))
                RunConfigPanel(
                    RunConfig(harness, model, effort, options),
                    onChange = { c ->
                        if (c.harness != harness) { harness = c.harness; model = null; effort = null; options = emptyMap() }
                        else { model = c.model; effort = c.effort; options = c.options }
                    },
                    harnesses = harnesses, models = models, agentLocked = false, warnCache = false,
                    modifier = Modifier.weight(1f, fill = false),
                    onPickFavorite = { f -> harness = f.harness; model = f.model; effort = null; options = emptyMap() },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showConfig = false }) { Text("Done", color = Z.accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    if (folderBrowser) {
        val hostId = target?.deviceId ?: hosts.firstOrNull()?.id
        if (hostId != null) FolderBrowser(
            app, hostId, hosts.firstOrNull { it.id == hostId }?.name ?: "",
            onDismiss = { folderBrowser = false },
            onPick = { path, git ->
                folderBrowser = false
                scope.launch {
                    try {
                        val id = client.createProject(hostId, path, git)
                        app.refresh()
                        val p = client.project(id)
                        if (p != null) { spaceId = p.id; noProject = false }
                    } catch (e: Exception) {
                        error = e.message
                    }
                }
            },
        )
    }
}

/** A rounded chip that opens a picker sheet. */
@Composable
private fun PillChip(label: String, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Z.controlFill).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(7.dp)) }
        Text(label, color = Z.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        Spacer(Modifier.width(2.dp))
        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Z.tertiary, modifier = Modifier.size(16.dp))
    }
}

/** A pill that opens a menu; `content` builds the items and receives a `close` callback. */
@Composable
private fun Dropdown(
    label: String, big: Boolean = false, leading: @Composable (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(CircleShape).background(if (big) androidx.compose.ui.graphics.Color.Transparent else Z.controlFill)
                .clickable { open = true }.padding(horizontal = if (big) 10.dp else 12.dp, vertical = if (big) 8.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let { it(); Spacer(Modifier.width(6.dp)) }
            Text(
                label, color = if (big) Z.text else Z.secondary, fontSize = if (big) 19.sp else 14.sp,
                fontWeight = if (big) FontWeight.Medium else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (big) 220.dp else 240.dp),
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Z.tertiary, modifier = Modifier.size(if (big) 20.dp else 16.dp))
        }
        DropdownMenu(open, { open = false }) { content { open = false } }
    }
}

@Composable
private fun FolderBrowser(app: AppModel, deviceId: String, deviceName: String, onDismiss: () -> Unit, onPick: (String, Boolean) -> Unit) {
    val client = app.client ?: return
    var path by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) {
        error = null
        try { listing = client.listFolders(deviceId, path) } catch (e: Exception) { error = e.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a project on $deviceName") },
        text = {
            Column {
                Text(listing?.path ?: "…", color = Z.subline, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (error != null) Text(error!!, color = Z.danger, fontSize = 13.sp)
                LazyColumn(Modifier.height(320.dp)) {
                    val l = listing
                    if (l != null) {
                        if (parent(l.path) != null) item {
                            Row(Modifier.fillMaxWidth().clickable { path = parent(l.path) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Folder, null, tint = Z.tertiary); Spacer(Modifier.width(10.dp)); Text("..", color = Z.text)
                            }
                        }
                        items(l.entries.filter { it.isDir }) { e ->
                            Row(
                                Modifier.fillMaxWidth().clickable { path = l.path.trimEnd('/', '\\') + (if (l.path.contains('\\')) "\\" else "/") + e.name }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Folder, null, tint = if (e.isRepo) Z.accent else Z.tertiary)
                                Spacer(Modifier.width(10.dp))
                                Text(e.name, color = Z.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (e.isRepo) Text("git", color = Z.subline, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { listing?.let { onPick(it.path, false) } }, enabled = listing != null) { Text("Use this folder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun parent(path: String): String? {
    val sep = if (path.contains('\\')) '\\' else '/'
    val trimmed = path.trimEnd(sep)
    val i = trimmed.lastIndexOf(sep)
    return when {
        i < 0 -> null
        i == 0 -> if (trimmed.length > 1) sep.toString() else null
        trimmed.length > 2 && trimmed[1] == ':' && i == 2 -> trimmed.substring(0, 3).takeIf { trimmed.length > 3 }
        else -> trimmed.substring(0, i)
    }
}
