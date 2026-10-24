package sh.zeron.android.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.zeron.android.app.AppModel
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.DeviceAccountInfo
import uniffi.zeron_core.LoginOptionInfo
import uniffi.zeron_core.agentLoginRules

/** The page the provider's sign-in ends on; Android routes it back to the app (see the manifest). */
private const val DONE_URL = "zeron://signed-in"

/** A sign-in under way on a computer. */
private class Pending(val loginId: String, val needsCode: Boolean)

/**
 * One agent's logins on a computer: who it runs as, switching between them, removing one, and
 * signing in. The computer starts the sign-in; the provider's page opens here, and the browser's
 * return reaches the computer through the phone (the core relays it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentAccountsSheet(app: AppModel, deviceId: String, deviceName: String?, harness: String, onDismiss: () -> Unit) {
    val client = app.client ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rules = remember(harness) { agentLoginRules(harness) }
    var accounts by remember { mutableStateOf<List<DeviceAccountInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var code by remember { mutableStateOf("") }

    suspend fun reload() {
        try {
            accounts = client.deviceAgentAccounts(deviceId).filter { it.harness == harness }
        } catch (e: Exception) {
            error = e.message
            if (accounts == null) accounts = emptyList()
        }
    }
    LaunchedEffect(deviceId, harness) { reload() }

    fun open(url: String) {
        if (url.isEmpty()) return
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            error = "Couldn't open the sign-in page."
        }
    }

    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun signIn(option: LoginOptionInfo) {
        error = null
        note = null
        job?.cancel()
        job = scope.launch {
            var started: Pending? = null
            try {
                val start = client.deviceLoginStart(deviceId, harness, option.provider, DONE_URL)
                started = Pending(start.loginId, start.pasteCode)
                pending = started
                open(start.url)
                if (start.pasteCode) return@launch // the code comes back through the field below
                var opened = start.url.isNotEmpty()
                val deadline = System.currentTimeMillis() + 10 * 60_000L
                while (System.currentTimeMillis() < deadline) {
                    delay(1500)
                    val poll = client.deviceLoginPoll(deviceId, start.loginId, DONE_URL)
                    when (poll.status) {
                        "done" -> { pending = null; reload(); return@launch }
                        "error" -> { pending = null; error = poll.message ?: "The sign-in failed."; return@launch }
                        else -> {
                            poll.url?.takeIf { !opened }?.let { open(it); opened = true }
                            poll.message?.let { note = it }
                        }
                    }
                }
                client.deviceLoginCancel(deviceId, start.loginId)
                pending = null
                error = "The sign-in timed out."
            } catch (e: CancellationException) {
                started?.let { client.deviceLoginCancel(deviceId, it.loginId) }
                throw e
            } catch (e: Exception) {
                pending = null
                error = (e as? uniffi.zeron_core.CoreException)?.message?.removePrefix("message=") ?: e.message
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Z.elevated) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(rules.name.ifEmpty { harness }, color = Z.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(deviceName?.let { "Signed in on $it" } ?: "Signed in on this computer", color = Z.subline, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))

            val list = accounts
            when {
                list == null -> Text("Loading…", color = Z.subline, fontSize = 14.sp)
                list.isEmpty() -> Text("Not signed in.", color = Z.secondary, fontSize = 15.sp)
                else -> Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Z.controlFill)) {
                    for (a in list) AccountRow(
                        a, rules.switches,
                        onUse = { scope.launch { try { accounts = client.deviceActivateAccount(deviceId, harness, a.id).filter { it.harness == harness } } catch (e: Exception) { error = e.message } } },
                        onForget = { scope.launch { try { accounts = client.deviceForgetAccount(deviceId, harness, a.id).filter { it.harness == harness } } catch (e: Exception) { error = e.message } } },
                    )
                }
            }

            rules.note?.let { Text(it, color = Z.subline, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }

            val p = pending
            if (p != null) {
                Spacer(Modifier.height(14.dp))
                if (p.needsCode) {
                    Text("Copy the code the sign-in page shows, then paste it here.", color = Z.secondary, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    FieldBox(code, { code = it }, "Authorization code")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            client.deviceLoginCancel(deviceId, p.loginId)
                            pending = null
                            code = ""
                        }) { Text("Cancel", color = Z.secondary) }
                        TextButton(onClick = {
                            val entered = code.trim()
                            if (entered.isEmpty()) return@TextButton
                            scope.launch {
                                try {
                                    accounts = client.deviceLoginComplete(deviceId, p.loginId, entered).filter { it.harness == harness }
                                    pending = null
                                    code = ""
                                } catch (e: Exception) {
                                    error = (e as? uniffi.zeron_core.CoreException)?.message?.removePrefix("message=") ?: e.message
                                }
                            }
                        }) { Text("Continue", color = Z.accent, fontWeight = FontWeight.SemiBold) }
                    }
                } else {
                    Text(note ?: "Finish signing in in the browser…", color = Z.secondary, fontSize = 14.sp)
                    TextButton(onClick = { job?.cancel(); pending = null }) { Text("Cancel", color = Z.secondary) }
                }
            } else if (rules.signsIn && !(rules.keepsOneLogin && !accounts.isNullOrEmpty())) {
                Spacer(Modifier.height(14.dp))
                val has = !accounts.isNullOrEmpty()
                for (o in rules.options) {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clip(CircleShape).background(Z.accent)
                            .clickable { signIn(o) }.padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (has) o.moreTitle else o.firstTitle, color = androidx.compose.ui.graphics.Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else if (!rules.signsIn) {
                Text("Zeron doesn't sign in to this agent. Set it up on the computer.", color = Z.subline, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }

            error?.let { Text(it, color = Z.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
private fun AccountRow(a: DeviceAccountInfo, canSwitch: Boolean, onUse: () -> Unit, onForget: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                a.email ?: a.displayName ?: if (a.apiKey) "API key" else "Account",
                color = Z.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            val sub = listOfNotNull(a.planLabel, a.organization, a.provider).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = Z.subline, fontSize = 12.sp, maxLines = 1)
        }
        if (a.active) {
            Text("In use", color = Z.success, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 8.dp))
        } else if (canSwitch && a.switchable) {
            TextButton(onClick = onUse) { Text("Use", color = Z.accent, fontWeight = FontWeight.SemiBold) }
        }
        if (a.switchable) TextButton(onClick = onForget) { Text("Remove", color = Z.danger, fontSize = 13.sp) }
        Spacer(Modifier.width(2.dp))
    }
}

/** Settings: every agent on every computer that runs them, each opening its logins. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun AgentAccountsCard(app: AppModel) {
    val client = app.client ?: return
    val hosts = remember(app.workspaceRevision) { client.executionDevices().sortedBy { it.name.lowercase() } }
    var open by remember { mutableStateOf<Triple<String, String?, String>?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp)).background(Z.elevated)) {
        if (hosts.isEmpty()) Text("No computers yet.", color = Z.subline, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        for (h in hosts) {
            val agents by androidx.compose.runtime.produceState(emptyList<uniffi.zeron_core.HarnessInfo>(), h.id, h.online) {
                if (h.online) value = try { client.listHarnesses(h.id).filter { it.offered && agentLoginRules(it.id).signsIn } } catch (_: Exception) { emptyList() }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(h.name, color = Z.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (!h.online) Text("Offline", color = Z.subline, fontSize = 12.sp)
                else androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    for (a in agents) OptionChip(a.label, false, mark = a.id) { open = Triple(h.id, h.name, a.id) }
                }
            }
        }
    }
    open?.let { (device, name, harness) -> AgentAccountsSheet(app, device, name, harness) { open = null } }
}
