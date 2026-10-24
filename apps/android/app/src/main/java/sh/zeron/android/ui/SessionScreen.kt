package sh.zeron.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import sh.zeron.android.app.AppModel
import sh.zeron.android.transcript.TranscriptList
import uniffi.zeron_core.BusyPolicy
import uniffi.zeron_core.ComposerState
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.InputRequest
import uniffi.zeron_core.OutgoingAttachment
import uniffi.zeron_core.QueueGate
import uniffi.zeron_core.QueueItem
import uniffi.zeron_core.SendRequest
import uniffi.zeron_core.SendState
import uniffi.zeron_core.UserInputAnswer
import uniffi.zeron_core.harnessLabel
import uniffi.zeron_core.reasoningLabel
import java.io.ByteArrayOutputStream

private class Staged(val name: String, val mime: String, val bytes: ByteArray, val thumb: Bitmap?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(app: AppModel, chatId: String, onMenu: () -> Unit, onBack: () -> Unit) {
    val client = app.client ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val handle = remember(chatId, client) { client.openSession(chatId) }
    val revision = app.composerRevision
    val composer: ComposerState = remember(revision, handle) { handle.composer() }
    val row = remember(app.workspaceRevision, chatId) { client.sessionRow(chatId) }
    val dark = Z.dark

    var text by remember(chatId) { mutableStateOf(app.draft(chatId)) }
    val staged = remember(chatId) { mutableStateListOf<Staged>() }
    var error by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<TranscriptList?>(null) }
    var following by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var menu by remember { mutableStateOf(false) }

    // A chat only counts as viewed while the app is on screen: a run that finishes after
    // the app goes to the background stays unseen, so it raises a session alert.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(chatId, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> { handle.setViewAttached(true); handle.markSeen() }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> handle.setViewAttached(false)
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            handle.setViewAttached(true)
            handle.markSeen()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            handle.setViewAttached(false)
            handle.markSeen()
        }
    }
    LaunchedEffect(text) { app.setDraft(chatId, text) }
    LaunchedEffect(list, composer.host.deviceId) { list?.setHostDevice(client, composer.host.deviceId) }
    LaunchedEffect(dark, list) { list?.setDarkMode(dark) }
    BackHandler(onBack = onBack)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        for (uri in uris) readStaged(context, uri)?.let { staged += it }
    }

    // Run settings: model, thinking level and speed can change mid-session; the agent too on hosts that switch providers.
    val sessionCfg = remember(revision, chatId) { client.sessionConfig(chatId) }
    var showConfig by remember { mutableStateOf(false) }
    var draftCfg by remember { mutableStateOf<RunConfig?>(null) }
    val cfgHarness = sessionCfg?.harness.orEmpty()
    // While the settings sheet is open the catalog follows the agent being picked, which can change on
    // a host that switches providers between turns.
    val modelHarness = draftCfg?.harness?.takeIf { showConfig } ?: cfgHarness
    val modelCatalog by androidx.compose.runtime.produceState(modelHarness to emptyList<uniffi.zeron_core.ModelInfo>(), composer.host.deviceId, modelHarness) {
        value = modelHarness to emptyList()
        if (composer.host.deviceId.isNotEmpty() && modelHarness.isNotEmpty()) {
            // The host may be offline: fall back to the bundled catalog so the model can still be changed.
            value = modelHarness to (try { client.listModels(composer.host.deviceId, modelHarness).ifEmpty { uniffi.zeron_core.fallbackModels(modelHarness) } } catch (_: Exception) { uniffi.zeron_core.fallbackModels(modelHarness) })
        } else if (modelHarness.isNotEmpty()) {
            value = modelHarness to uniffi.zeron_core.fallbackModels(modelHarness)
        }
    }
    val models = if (modelCatalog.first == modelHarness) modelCatalog.second else emptyList()
    val canSwitchAgent = composer.host.capabilities.providerSwitch && !composer.live.turnRunning
    val harnesses by androidx.compose.runtime.produceState(uniffi.zeron_core.fallbackHarnesses(), composer.host.deviceId, canSwitchAgent) {
        if (canSwitchAgent && composer.host.deviceId.isNotEmpty()) {
            value = try { client.listHarnesses(composer.host.deviceId).filter { it.offered }.ifEmpty { uniffi.zeron_core.fallbackHarnesses() } } catch (_: Exception) { uniffi.zeron_core.fallbackHarnesses() }
        }
    }
    // A new agent starts on its default model, Medium thinking and Slow, like a new session.
    LaunchedEffect(models, draftCfg?.harness) {
        val d = draftCfg ?: return@LaunchedEffect
        if (!showConfig || d.model != null || models.isEmpty() || d.harness != modelHarness) return@LaunchedEffect
        draftCfg = withModel(d, models.first())
    }

    // Plan usage of the account this chat's agent is signed in with (the desktop's plan ring).
    var account by remember(chatId) { mutableStateOf<uniffi.zeron_core.AgentAccountView?>(null) }
    var showUsage by remember { mutableStateOf(false) }
    var refreshingUsage by remember { mutableStateOf(false) }
    var lastForced by remember { mutableStateOf(0L) }
    suspend fun loadUsage(force: Boolean) {
        val device = composer.host.deviceId
        if (device.isEmpty() || cfgHarness.isEmpty() || !reportsPlanUsage(cfgHarness)) return
        try {
            val list = client.listAgentAccounts(device, force)
            account = list.firstOrNull { it.harness == cfgHarness && it.active } ?: list.firstOrNull { it.harness == cfgHarness }
        } catch (_: Exception) {
            // Host offline or no usage: keep whatever we last had.
        }
    }
    LaunchedEffect(composer.host.deviceId, cfgHarness) {
        while (true) {
            loadUsage(false)
            kotlinx.coroutines.delay(5 * 60_000L)
        }
    }
    val current = sessionCfg?.let { RunConfig(it.harness, it.model, it.reasoning, it.modelOptions) }
    val hasHistory = remember(revision) { handle.transcriptStatus().let { it.entryCount > 0u || it.pendingCount > 0u } }

    fun send(policy: BusyPolicy) {
        val body = text.trim()
        if (body.isEmpty() && staged.isEmpty()) return
        list?.beginOwnTurn()
        try {
            handle.send(
                SendRequest(
                    body,
                    staged.map { OutgoingAttachment(it.name, it.mime, it.bytes) },
                    null,
                    policy,
                ),
            )
            text = ""
            staged.clear()
            error = null
        } catch (e: Exception) {
            error = (e as? uniffi.zeron_core.CoreException)?.message?.removePrefix("message=") ?: e.message
        }
    }

    Column(Modifier.fillMaxSize().background(Z.background).safeTop().imePadding().navigationBarsPadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Rounded.Menu, "Menu", tint = Z.text) }
            Column(Modifier.weight(1f)) {
                Text(
                    composer.title.ifBlank { "New session" }, color = Z.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val project = row?.project?.name ?: "No project"
                val sub = composer.host.name?.let { "$project @ $it" } ?: project
                Text(sub, color = Z.subline, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (row != null) StatusGlyph(composer.live.indicator, false, Modifier.padding(end = 4.dp), 18.dp)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Z.secondary) }
                if (menu) ActionSheet(
                    title = row?.title ?: "Session",
                    subtitle = listOfNotNull(row?.project?.name, row?.branch, row?.harnessLabel).joinToString(" · "),
                    groups = listOf(
                        listOf(
                            SheetAction(Icons.Rounded.ContentCopy, "Copy transcript") { list?.copy(list!!.plainText()) },
                            SheetAction(Icons.Rounded.PushPin, if (row?.pinned == true) "Unpin" else "Pin") {
                                if (row?.pinned == true) client.unpinSession(chatId) else client.pinSession(chatId)
                            },
                        ),
                        listOf(SheetAction(Icons.Rounded.Archive, "Archive") { client.archiveSession(chatId); onBack() }),
                    ),
                    onDismiss = { menu = false },
                )
            }
        }

        // Transcript
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            AndroidView(
                factory = { ctx ->
                    TranscriptList(ctx).also { tl ->
                        tl.scope = scope
                        tl.onFollowChange = { following = it }
                        tl.onShowDetail = { title, body -> detail = title to body }
                        tl.onOpenImage = { _, bmp -> preview = bmp }
                        tl.onAction = { action ->
                            // Provider sign-in lives on the computer running the chat; the phone can't start it yet.
                            val msg = if (action == "sign-in") "Sign in to this agent in Zeron on the computer running the chat (Settings → Providers). Signing in from the phone isn't built yet." else "Not available on Android yet."
                            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                        }
                        tl.imageLoader = { ref -> client.readAttachment(composer.host.deviceId, ref) }
                        tl.setDarkMode(dark)
                        tl.attach(client, chatId, composer.host.deviceId)
                        list = tl
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.close() },
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = !following,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut(),
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Z.elevated).border(0.5.dp, Z.hairline, CircleShape)
                        .clickable { list?.scrollToBottom() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ArrowDownward, "Jump to latest", tint = Z.text, modifier = Modifier.size(20.dp)) }
            }
        }

        // Banner
        Banner(app, composer, error)

        // The agent's checklist
        TodoTray(remember(revision, handle) { handle.todos() })

        // Queue
        if (composer.queue.isNotEmpty()) QueuePanel(handle, composer.queue, steerable = composer.live.turnRunning && composer.host.capabilities.midTurnSteering == true)

        // Question panel replaces the composer while input is requested.
        val open = composer.openInput
        if (open != null) {
            QuestionPanel(open) { answers ->
                try { handle.respondInput(open.requestId, answers) } catch (e: Exception) { error = e.message }
            }
        } else {
            Composer(
                invocations = rememberInvocations(client, composer.host.deviceId, cfgHarness, chatId, null, trailingInvocation(text) != null),
                text = text, onText = { text = it },
                placeholder = "Message ${row?.harnessLabel ?: "the agent"}",
                running = composer.live.turnRunning,
                canInterrupt = composer.live.canInterrupt,
                canSteer = composer.host.capabilities.midTurnSteering == true,
                staged = staged,
                chips = chips(row, composer, speedIsFast(models.firstOrNull { it.id == current?.model }, current?.options.orEmpty())),
                onChips = { if (current != null) { draftCfg = current; showConfig = true } },
                onAttach = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = { staged.removeAt(it) },
                onSend = { send(BusyPolicy.QUEUE) },
                onSteer = { send(BusyPolicy.STEER) },
                onStop = { try { handle.interrupt() } catch (_: Exception) {} },
                progress = composer.transferProgress,
                usage = composer.contextUsage,
                account = account,
                voiceApp = app,
                onUsage = {
                    showUsage = true
                    // A forced probe hits the provider, so not more often than every 30 s.
                    val now = System.currentTimeMillis()
                    if (now - lastForced > 30_000) { lastForced = now; scope.launch { refreshingUsage = true; loadUsage(true); refreshingUsage = false } }
                },
            )
        }
    }

    if (showUsage) {
        UsageSheet(
            usage = composer.contextUsage, account = account, harnessLabel = row?.harnessLabel ?: harnessLabel(cfgHarness),
            refreshing = refreshingUsage,
            onRefresh = { lastForced = System.currentTimeMillis(); scope.launch { refreshingUsage = true; loadUsage(true); refreshingUsage = false } },
            onDismiss = { showUsage = false },
        )
    }

    val dc = draftCfg
    if (showConfig && dc != null && current != null) {
        RunConfigSheet(
            initial = current, draft = dc, onDraft = { draftCfg = it },
            harnesses = harnesses, models = models, agentLocked = !canSwitchAgent, hasHistory = hasHistory,
            lockedNote = if (composer.host.capabilities.providerSwitch) "Wait for the run to finish to switch agent. You can still switch models." else "This computer needs a newer Zeron to switch agent. You can still switch models.",
            applyLabel = if (hasHistory && affectsCache(current, dc)) "Apply anyway" else "Apply",
            canApply = dc.model != null,
            onApply = {
                showConfig = false
                try {
                    client.setSessionConfig(chatId, uniffi.zeron_core.ChatConfig(dc.harness, dc.model, dc.effort, dc.options, sessionCfg!!.sandbox))
                    app.refresh()
                } catch (e: Exception) {
                    error = (e as? uniffi.zeron_core.CoreException)?.message?.removePrefix("message=") ?: e.message
                }
            },
            onDismiss = { showConfig = false },
        )
    }

    detail?.let { (title, body) ->
        ModalBottomSheet(onDismissRequest = { detail = null }, containerColor = Z.elevated) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(title, color = Z.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Text(
                        body, color = Z.text, fontSize = 13.sp, fontFamily = LocalMono.current, lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().height(360.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
    preview?.let { ImageViewer(it) { preview = null } }
}

private class Chip(val title: String, val tint: Boolean = false)

private fun chips(row: uniffi.zeron_core.SessionRow?, c: ComposerState, fast: Boolean): List<Chip> {
    val out = ArrayList<Chip>()
    (row?.modelLabel ?: row?.harnessLabel)?.let { out += Chip(it) }
    row?.reasoning?.takeIf { it.isNotBlank() }?.let { out += Chip(reasoningLabel(it)) }
    if (fast) out += Chip("Fast")
    val pr = row?.pullRequest
    if (pr != null) out += Chip("#${pr.number}") else row?.branch?.takeIf { it.isNotBlank() }?.let { out += Chip(it) }
    return out
}

@Composable
private fun Banner(app: AppModel, c: ComposerState, error: String?) {
    val net = app.connectivity?.state
    val (text, tone) = when {
        error != null -> error to Z.danger
        c.sendState == SendState.FAILED -> "Not delivered — tap to retry" to Z.danger
        net == ConnectivityState.OFFLINE -> "Offline — messages are saved on this phone" to Z.warning
        net == ConnectivityState.RECONNECTING || c.room.degraded -> "Reconnecting…" to Z.warning
        c.sendState == SendState.QUEUED -> "Waiting to deliver…" to Z.secondary
        !c.host.online -> "${c.host.name ?: "The host"} is offline — messages will queue" to Z.warning
        else -> return
    }
    val retry = c.sendState == SendState.FAILED
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(tone.copy(alpha = 0.1f)).then(if (retry) Modifier.clickable { app.client?.openSession(c.chatId)?.retryDelivery() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(text, color = tone, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun QueuePanel(handle: uniffi.zeron_core.SessionHandle, queue: List<QueueItem>, steerable: Boolean) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(true) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(Z.controlFill).padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${queue.size} queued", color = Z.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp, null, tint = Z.tertiary, modifier = Modifier.size(18.dp))
        }
        if (expanded) {
            for ((i, item) in queue.withIndex()) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                        Text(item.visibleText, color = Z.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        when (val g = item.gate) {
                            is QueueGate.Editing -> Text(if (g.mine) "Editing" else "Being edited", color = Z.tertiary, fontSize = 11.sp)
                            is QueueGate.ReviewRequired -> Text("Needs review", color = Z.warning, fontSize = 11.sp)
                            null -> if (item.attachments.isNotEmpty()) Text("${item.attachments.size} image(s)", color = Z.tertiary, fontSize = 11.sp)
                        }
                    }
                    if (i > 0) SmallIcon(Icons.Rounded.KeyboardArrowUp, "Move up") { handle.moveQueuedBy(item.id, -1) }
                    if (i < queue.size - 1) SmallIcon(Icons.Rounded.KeyboardArrowDown, "Move down") { handle.moveQueuedBy(item.id, 1) }
                    // Delivers without interrupting: into the live turn when the agent reads mid-turn, else next.
                    val deliver = { scope.launch { try { handle.deliverQueuedNow(item.id) } catch (_: Exception) {} }; Unit }
                    if (steerable && item.attachments.isEmpty()) {
                        Box(
                            Modifier.padding(horizontal = 4.dp).clip(CircleShape).background(Z.accent).clickable(onClick = deliver)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) { Text("Steer", color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    } else SmallIcon(Icons.Rounded.ArrowUpward, "Send next", deliver)
                    SmallIcon(Icons.Rounded.Delete, "Remove") { scope.launch { try { handle.removeQueued(item.id) } catch (_: Exception) {} } }
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) { Icon(icon, label, tint = Z.secondary, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun QuestionPanel(req: InputRequest, onAnswer: (List<UserInputAnswer>) -> Unit) {
    var page by remember(req.requestId) { mutableStateOf(0) }
    val picked = remember(req.requestId) { mutableStateListOf<Set<String>>().also { l -> req.questions.forEach { l += emptySet<String>() } } }
    val free = remember(req.requestId) { mutableStateListOf<String>().also { l -> req.questions.forEach { q -> l += (q.prefill ?: "") } } }
    val q = req.questions[page]
    fun answers() = req.questions.mapIndexed { i, qq ->
        val labels = picked[i].toMutableList()
        if (free[i].isNotBlank()) labels += free[i].trim()
        UserInputAnswer(qq.id, labels)
    }
    Column(
        Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(Z.elevated)
            .border(0.5.dp, Z.hairline, RoundedCornerShape(18.dp)).padding(16.dp),
    ) {
        if (q.header.isNotBlank()) Text(q.header.uppercase(), color = Z.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(4.dp))
        Text(q.question, color = Z.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        for (opt in q.options) {
            val on = opt in picked[page]
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) Z.accentSoft else Z.controlFill)
                    .clickable {
                        picked[page] = if (q.multiSelect) (if (on) picked[page] - opt else picked[page] + opt) else setOf(opt)
                        if (!q.multiSelect && req.questions.size == 1 && free[page].isBlank()) onAnswer(
                            listOf(UserInputAnswer(q.id, listOf(opt))),
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) { Text(opt, color = if (on) Z.accent else Z.text, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        }
        Spacer(Modifier.height(6.dp))
        FieldBox(free[page], { free[page] = it }, "Or type an answer", minLines = if (q.multiline) 3 else 1)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (page > 0) TextButton(onClick = { page-- }) { Text("Back", color = Z.secondary) }
            if (page < req.questions.size - 1) TextButton(onClick = { page++ }) { Text("Next", color = Z.accent) }
            else TextButton(onClick = { onAnswer(answers()) }) { Text("Submit", color = Z.accent, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun FieldBox(value: String, onChange: (String) -> Unit, placeholder: String, minLines: Int = 1, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Z.controlFill).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, color = Z.tertiary, fontSize = 15.sp)
        BasicTextField(
            value, onChange, minLines = minLines, cursorBrush = SolidColor(Z.accent),
            textStyle = TextStyle(color = Z.text, fontSize = 15.sp), modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Composer(
    text: String, onText: (String) -> Unit, placeholder: String,
    running: Boolean, canInterrupt: Boolean, canSteer: Boolean,
    staged: List<Staged>, chips: List<Chip>,
    onAttach: () -> Unit, onRemove: (Int) -> Unit, onChips: () -> Unit,
    onSend: () -> Unit, onSteer: () -> Unit, onStop: () -> Unit,
    progress: Double?,
    usage: uniffi.zeron_core.ContextUsage?,
    account: uniffi.zeron_core.AgentAccountView?,
    onUsage: () -> Unit,
    voiceApp: AppModel? = null,
    invocations: List<uniffi.zeron_core.InvocationView> = emptyList(),
) {
    val hasContent = text.isNotBlank() || staged.isNotEmpty()
    val token = trailingInvocation(text)
    val matches = matchInvocations(invocations, token)
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (token != null) InvocationPopup(matches, { onText(insertInvocation(text, token, it)) }, Modifier.padding(bottom = 6.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Z.elevated)
                .border(0.5.dp, Z.hairline, RoundedCornerShape(24.dp)).padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            if (staged.isNotEmpty()) {
                LazyRow(Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(staged.size) { i ->
                        Box(Modifier.size(60.dp)) {
                            val t = staged[i].thumb
                            if (t != null) Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
                            else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Z.chip))
                            Box(
                                Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Z.text.copy(alpha = 0.7f)).clickable { onRemove(i) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.Close, "Remove", tint = Z.background, modifier = Modifier.size(12.dp)) }
                            progress?.let {
                                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f)), contentAlignment = Alignment.Center) {
                                    Text("${(it * 100).toInt()}%", color = androidx.compose.ui.graphics.Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Add, "Attach", tint = Z.secondary) }
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (text.isEmpty()) Text(placeholder, color = Z.tertiary, fontSize = 16.sp)
                    BasicTextField(
                        text, onText, maxLines = 8, cursorBrush = SolidColor(Z.accent),
                        textStyle = TextStyle(color = Z.text, fontSize = 16.sp, lineHeight = 22.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(4.dp))
                // Send → Steer → Stop morph.
                when {
                    hasContent && running && canSteer -> RoundButton(Z.accent, Icons.Rounded.Send, "Steer", onSteer)
                    hasContent -> RoundButton(Z.text, Icons.Rounded.ArrowUpward, if (running) "Queue" else "Send", onSend, Z.background)
                    running && canInterrupt -> RoundButton(Z.text, Icons.Rounded.Stop, "Stop", onStop, Z.background)
                    voiceApp != null && voiceApp.voice.available -> VoiceButton(voiceApp)
                    else -> RoundButton(Z.controlFill, Icons.Rounded.ArrowUpward, "Send", {}, Z.tertiary)
                }
            }
        }
        if (chips.isNotEmpty() || hasContextWindow(usage) || planFraction(account) != null) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(Modifier.weight(1f).padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(chips.size) { i ->
                    val ch = chips[i]
                    Box(
                        Modifier.clip(CircleShape).background(if (ch.tint) Z.warning.copy(alpha = 0.12f) else Z.controlFill)
                            .clickable(onClick = onChips).padding(horizontal = 10.dp, vertical = 5.dp),
                    ) { Text(ch.title, color = if (ch.tint) Z.warning else Z.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
                }
            }
            UsageRings(usage, account, onUsage)
        }
    }
}

@Composable
private fun RoundButton(
    fill: androidx.compose.ui.graphics.Color, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White,
) {
    Box(
        Modifier.padding(bottom = 2.dp).size(40.dp).clip(CircleShape).background(fill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun ImageViewer(bitmap: Bitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.92f)).clickable(onClick = onDismiss)) {
            Image(
                bitmap.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offsetX += pan.x; offsetY += pan.y
                            if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).safeTop().padding(8.dp)) {
                Icon(Icons.Rounded.Close, "Close", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}

private fun readStaged(context: android.content.Context, uri: Uri): Staged? = try {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "image/jpeg"
    var name = "image"
    resolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) name = c.getString(i)
    }
    val raw = resolver.openInputStream(uri)!!.use { it.readBytes() }
    // Downscale large camera photos before they cross the relay.
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
    var sample = 1
    while (opts.outWidth / sample > 2400 || opts.outHeight / sample > 2400) sample *= 2
    val full = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
    val thumb = Bitmap.createScaledBitmap(full, 120, (120f * full.height / full.width).toInt().coerceAtLeast(1), true)
    if (sample > 1 || raw.size > 4_000_000) {
        val out = ByteArrayOutputStream()
        full.compress(Bitmap.CompressFormat.JPEG, 88, out)
        Staged(name.substringBeforeLast('.') + ".jpg", "image/jpeg", out.toByteArray(), thumb)
    } else Staged(name, mime, raw, thumb)
} catch (_: Exception) {
    null
}
