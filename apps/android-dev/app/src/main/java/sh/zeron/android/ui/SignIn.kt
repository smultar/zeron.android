package sh.zeron.android.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import sh.zeron.android.app.AppModel
import uniffi.zeron_core.AuthCallback
import uniffi.zeron_core.AuthOrg
import uniffi.zeron_core.parseAuthCallback
import uniffi.zeron_core.workosAuthorizeUrl
import java.util.UUID

/** Sign in with WorkOS (Custom Tab). */
@Composable
fun SignInScreen(app: AppModel, callbacks: Flow<Uri>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf("") }
    var chooser by remember { mutableStateOf<Pair<List<AuthOrg>, CompletableDeferred<AuthOrg?>>?>(null) }

    LaunchedEffect(Unit) {
        callbacks.collect { uri ->
            val parsed = parseAuthCallback(uri.toString())
            when (parsed) {
                null -> {}
                is AuthCallback.Error -> { error = parsed.description ?: parsed.error; busy = false }
                is AuthCallback.Code -> {
                    if (parsed.state != null && parsed.state != state) { error = "Sign-in was interrupted. Try again."; busy = false; return@collect }
                    busy = true
                    try {
                        app.signIn(parsed.code) { orgs ->
                            val pick = CompletableDeferred<AuthOrg?>()
                            chooser = orgs to pick
                            pick.await().also { chooser = null }
                        }
                        error = null
                    } catch (e: Exception) {
                        error = (e as? uniffi.zeron_core.CoreException)?.let { it.message?.removePrefix("message=") } ?: e.message
                    }
                    busy = false
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Z.background).safeTop().navigationBarsPadding()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                ZeronIcon(Modifier.size(104.dp))
                Spacer(Modifier.height(18.dp))
                Text("Zeron", color = Z.text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Your coding agents, from anywhere.", color = Z.secondary, fontSize = 17.sp, textAlign = TextAlign.Center)
            }
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { Text(it, color = Z.danger, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                if (busy) {
                    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Z.text, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                } else {
                    PillButton("Sign In", modifier = Modifier.fillMaxWidth(), onClick = {
                        error = null
                        state = UUID.randomUUID().toString()
                        CustomTabsIntent.Builder().setShowTitle(true).build()
                            .launchUrl(context, Uri.parse(workosAuthorizeUrl(state)))
                    })
                }
            }
        }
    }

    chooser?.let { (orgs, pick) ->
        AlertDialog(
            onDismissRequest = { pick.complete(null) },
            title = { Text("Choose an organization") },
            text = {
                Column {
                    for (org in orgs) {
                        TextButton(onClick = { pick.complete(org) }, modifier = Modifier.fillMaxWidth()) { Text(org.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pick.complete(null) }) { Text("Cancel") } },
        )
    }
}
