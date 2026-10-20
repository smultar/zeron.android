package sh.zeron.android.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.zeron_core.AgentAccountView
import uniffi.zeron_core.ContextUsage
import uniffi.zeron_core.UsageWindowView
import java.text.NumberFormat
import kotlin.math.max

/**
 * The desktop's context-window popover and plan-usage card in one sheet:
 * token counts for the conversation, and the signed-in account's rate-limit
 * windows ("5h", "Week") with when each resets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageSheet(
    usage: ContextUsage?,
    account: AgentAccountView?,
    harnessLabel: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Z.elevated) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 20.dp)) {
            Text("Usage", color = Z.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)

            Heading("Context window")
            ContextBlock(usage)

            if (account != null && reportsPlanUsage(account.harness)) {
                Heading("Plan usage")
                val who = listOfNotNull(account.displayName ?: account.email, account.planLabel).joinToString(" · ")
                Text(harnessLabel + if (who.isNotEmpty()) " — $who" else "", color = Z.secondary, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                if (account.windows.isEmpty()) {
                    Text(account.usageError ?: "Usage isn't reported for this account yet.", color = Z.subline, fontSize = 13.sp)
                } else {
                    for (w in account.windows) WindowMeter(w)
                }
                account.usageError?.takeIf { account.windows.isNotEmpty() }?.let {
                    Text(it, color = Z.warning, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.usageFetchedAtMs?.let { "Updated ${ago(it)}" } ?: "Not probed yet",
                        color = Z.subline, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRefresh, enabled = !refreshing) {
                        Text(if (refreshing) "Refreshing…" else "Refresh", color = if (refreshing) Z.tertiary else Z.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(), color = Z.tertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ContextBlock(usage: ContextUsage?) {
    val n = NumberFormat.getIntegerInstance()
    val tokens = usage?.tokens
    val window = usage?.window
    val f = contextFraction(usage)
    when {
        tokens != null && window != null && window > 0uL && f != null -> {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${(f * 100).toInt()}%", color = contextColor(f), fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("of the window used", color = Z.secondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Meter(f, contextColor(f))
            Spacer(Modifier.height(12.dp))
            Stat("Used", "${n.format(tokens.toLong())} tokens")
            Stat("Window", "${n.format(window.toLong())} tokens")
            Stat("Remaining", "${n.format((window - minOf(tokens, window)).toLong())} tokens")
            if (f >= 0.9f) {
                Text("Nearly full: the agent will have to compact or drop earlier turns.", color = Z.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else if (f >= 0.75f) {
                Text("Getting full.", color = Z.warning, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        tokens != null -> {
            Stat("Used", "${n.format(tokens.toLong())} tokens")
            Text("Context limit not reported", color = Z.subline, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        window != null && window > 0uL -> {
            Stat("Capacity", "${n.format(window.toLong())} tokens")
            Text("Waiting for context usage", color = Z.subline, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        else -> Text("Context usage not reported by this agent yet.", color = Z.subline, fontSize = 13.sp)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Z.secondary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Z.text, fontSize = 14.sp, fontFamily = LocalMono.current)
    }
}

@Composable
private fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Z.tertiary.copy(alpha = 0.2f))) {
        // A 1.5 % floor keeps tiny non-zero usage visible (the desktop's rule).
        if (fraction > 0f) Box(Modifier.fillMaxWidth(max(fraction, 0.015f)).height(6.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun WindowMeter(w: UsageWindowView) {
    val f = w.usedFraction.coerceIn(0f, 1f)
    val color = planColor(f)
    val accent = Z.accent
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.label, color = Z.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("${(f * 100).toInt()}%", color = if (color == accent) Z.text else color, fontSize = 15.sp, fontFamily = LocalMono.current)
        }
        Spacer(Modifier.height(6.dp))
        Meter(f, color.copy(alpha = if (color == accent) 0.8f else 0.9f))
        w.resetsAtMs?.let { Text("Resets ${until(it)}", color = Z.subline, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** "in 1h 34m", "in 3d", or "now". */
private fun until(ms: Long): String {
    val mins = (ms - System.currentTimeMillis()) / 60_000
    return when {
        mins <= 0 -> "now"
        mins < 60 -> "in ${mins}m"
        mins < 48 * 60 -> "in ${mins / 60}h ${mins % 60}m"
        else -> "in ${mins / (24 * 60)}d"
    }
}

private fun ago(ms: Long): String {
    val mins = (System.currentTimeMillis() - ms) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        else -> "${mins / 60}h ago"
    }
}
