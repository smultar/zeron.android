package sh.zeron.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.app.AppModel
import uniffi.zeron_core.ConnectivityState
import uniffi.zeron_core.FrontPage
import uniffi.zeron_core.SessionRow

private sealed interface Entry {
    data class Header(val id: String, val title: String, val count: Int, val collapsible: Boolean, val collapsed: Boolean) : Entry
    data class Row(val row: SessionRow) : Entry
}

/** The Sessions page: pinned, sections and recent (or grouped by project), with search. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListScreen(app: AppModel, onOpen: (String) -> Unit, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val client = app.client ?: return
    val scope = rememberCoroutineScope()
    val revision = app.workspaceRevision
    val front: FrontPage = remember(revision, client) { client.frontPage() }
    val projects = remember(revision, client) { client.projects() }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var groupByProject by remember { mutableStateOf(false) }
    val collapsedGroups = remember { mutableStateOf(setOf<String>()) }
    val connectivity = app.connectivity

    val hits = remember(query, revision) { if (query.isBlank()) emptyList() else client.search(query.trim(), 40u) }

    val entries = remember(front, projects, groupByProject, collapsedGroups.value) {
        buildList {
            if (front.pinned.isNotEmpty()) {
                add(Entry.Header("pinned", "Pinned", front.pinned.size, false, false))
                front.pinned.forEach { add(Entry.Row(it)) }
            }
            for (s in front.sections) {
                add(Entry.Header("section:${s.id}", s.name, s.sessions.size, true, s.collapsed))
                if (!s.collapsed) s.sessions.forEach { add(Entry.Row(it)) }
            }
            if (groupByProject) {
                val byGroup = front.recent.groupBy { r -> r.project?.id ?: "home:${r.deviceId}" }
                for ((id, rows) in byGroup) {
                    val name = rows.first().project?.name ?: rows.first().deviceName ?: "Home"
                    val key = "project:$id"
                    val collapsed = key in collapsedGroups.value
                    add(Entry.Header(key, name, rows.size, true, collapsed))
                    if (!collapsed) rows.forEach { add(Entry.Row(it)) }
                }
            } else if (front.recent.isNotEmpty()) {
                if (front.pinned.isNotEmpty() || front.sections.isNotEmpty()) add(Entry.Header("recent", "Recent", front.recent.size, false, false))
                front.recent.forEach { add(Entry.Row(it)) }
            }
        }
    }

    Column(modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.background(Z.background))) {
        // Title row (the drawer supplies its own header)
        if (!embedded) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", color = Z.text, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            ConnectivityPill(connectivity?.state)
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, contentDescription = "Search", tint = Z.secondary)
            }
        }
        AnimatedVisibility(searching || embedded) {
            SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                app.onForeground()
                scope.launch { delay(900); refreshing = false }
            },
            modifier = Modifier.weight(1f),
        ) {
            if (query.isNotBlank()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (hits.isEmpty()) item { EmptyState("No matches", "Nothing in your sessions matches “$query”.") }
                    items(hits, key = { it.session.id }) { hit -> SessionItem(app, hit.session, onOpen) }
                }
            } else if (entries.isEmpty()) {
                EmptyState(
                    if (app.workspaceRevision == 0L || connectivity?.state == ConnectivityState.RECONNECTING) "Connecting…" else "No sessions yet",
                    "Start one with the + button. Your coding agents run on your computers; this phone is their remote.",
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(entries, key = { e -> when (e) { is Entry.Header -> "h:${e.id}"; is Entry.Row -> e.row.id } }) { e ->
                        when (e) {
                            is Entry.Header -> SectionHeader(e) {
                                if (e.id.startsWith("section:")) client.setSectionCollapsed(e.id.removePrefix("section:"), !e.collapsed)
                                else collapsedGroups.value = if (e.id in collapsedGroups.value) collapsedGroups.value - e.id else collapsedGroups.value + e.id
                            }
                            is Entry.Row -> SessionItem(app, e.row, onOpen)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { groupByProject = !groupByProject }) {
                                Text(if (groupByProject) "Show by recency" else "Group by project", color = Z.secondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectivityPill(state: ConnectivityState?) {
    val (text, tone) = when (state) {
        ConnectivityState.OFFLINE -> "Offline" to Z.warning
        ConnectivityState.RECONNECTING -> "Reconnecting" to Z.warning
        else -> return
    }
    Box(Modifier.padding(end = 4.dp).clip(CircleShape).background(tone.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = tone, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Z.controlFill).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = Z.tertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search sessions", color = Z.tertiary, fontSize = 16.sp)
            BasicTextField(
                value, onChange, singleLine = true, cursorBrush = SolidColor(Z.accent),
                textStyle = androidx.compose.ui.text.TextStyle(color = Z.text, fontSize = 16.sp),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Z.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(body, color = Z.secondary, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SectionHeader(e: Entry.Header, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().then(if (e.collapsible) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (e.collapsible) {
            Icon(
                Icons.Rounded.ChevronRight, null, tint = Z.tertiary,
                modifier = Modifier.size(16.dp).then(if (!e.collapsed) Modifier.rotate90() else Modifier),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(e.title.uppercase(), color = Z.tertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        Spacer(Modifier.width(8.dp))
        Text("${e.count}", color = Z.tertiary.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

private fun Modifier.rotate90() = this.rotate(90f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionItem(app: AppModel, row: SessionRow, onOpen: (String) -> Unit, showActions: Boolean = true) {
    val client = app.client ?: return
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { onOpen(row.id) }, onLongClick = { if (showActions) { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); menu = true } })
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 2.dp).size(20.dp), contentAlignment = Alignment.Center) {
                StatusGlyph(row.indicator, row.unseen, size = 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.title, color = Z.text, fontSize = 16.sp,
                        fontWeight = if (row.unseen) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    row.pullRequest?.let { pr ->
                        Spacer(Modifier.width(8.dp))
                        PrBadge(pr.number, pr.state)
                    }
                }
                val sub = listOfNotNull(row.project?.name, row.branch, row.deviceName?.takeIf { row.project == null }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = Z.subline, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                row.preview?.takeIf { it.isNotBlank() }?.let {
                    Text(it.replace('\n', ' '), color = Z.secondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(row.timeLabel, color = Z.subline, fontSize = 12.sp)
                if (!row.deviceOnline && row.project != null) Text("offline", color = Z.warning.copy(alpha = 0.8f), fontSize = 11.sp)
            }
        }
        if (menu) {
            ActionSheet(
                title = row.title,
                subtitle = listOfNotNull(row.project?.name, row.branch, row.harnessLabel).joinToString(" · "),
                groups = listOf(
                    listOf(
                        SheetAction(Icons.Rounded.PushPin, if (row.pinned) "Unpin" else "Pin") {
                            if (row.pinned) client.unpinSession(row.id) else client.pinSession(row.id)
                        },
                        SheetAction(Icons.Rounded.Edit, "Rename") { renaming = true },
                        SheetAction(if (row.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, if (row.archived) "Unarchive" else "Archive") {
                            if (row.archived) client.unarchiveSession(row.id) else client.archiveSession(row.id)
                        },
                    ),
                    listOf(SheetAction(Icons.Rounded.Delete, "Delete", danger = true) { confirmDelete = true }),
                ),
                onDismiss = { menu = false },
            )
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(row.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename session") },
            text = {
                androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = { renaming = false; if (text.isNotBlank()) client.renameSession(row.id, text.trim()) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("“${row.title}” will be removed from all your devices.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; client.deleteSession(row.id) }) { Text("Delete", color = Z.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
