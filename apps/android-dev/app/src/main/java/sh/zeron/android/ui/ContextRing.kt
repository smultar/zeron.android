package sh.zeron.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.zeron_core.AgentAccountView
import uniffi.zeron_core.ContextUsage

/** 0..1 of the context window used, or null without both numbers. */
fun contextFraction(u: ContextUsage?): Float? {
    val tokens = u?.tokens ?: return null
    val window = u.window ?: return null
    return if (window > 0uL) (tokens.toDouble() / window.toDouble()).toFloat().coerceIn(0f, 1f) else null
}

/** Harnesses that never report a window get no indicator, rather than a permanently empty ring. */
fun hasContextWindow(u: ContextUsage?) = (u?.window ?: 0uL) > 0uL

/** Agents whose CLI reports plan rate limits (everything but Antigravity). */
fun reportsPlanUsage(harness: String) = harness != "antigravity"

/** The binding limit: the most-used window of the account. */
fun planFraction(account: AgentAccountView?): Float? =
    account?.windows?.maxOfOrNull { it.usedFraction.coerceIn(0f, 1f) }

/** Context: muted, warning from 75 %, danger from 90 % (desktop `context_usage::chip`). */
@Composable
fun contextColor(f: Float?): Color = when {
    f == null -> Z.tertiary
    f >= 0.9f -> Z.danger
    f >= 0.75f -> Z.warning
    else -> Z.secondary
}

/** Plan usage: accent, warning from 80 %, danger from 95 % (desktop `usage_level`). */
@Composable
fun planColor(f: Float?): Color = when {
    f == null -> Z.tertiary
    f >= 0.95f -> Z.danger
    f >= 0.80f -> Z.warning
    else -> Z.accent
}

/** A 16 dp progress ring (faint track, arc from twelve o'clock) with its percent beside it. */
@Composable
fun RingChip(fraction: Float?, color: Color, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val track = Z.tertiary.copy(alpha = 0.25f)
    val accent = Z.accent
    val textColor = if (color == accent) Z.secondary else color
    Row(
        modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
            val inset = 2.dp.toPx() + 0.1.dp.toPx()
            val box = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = stroke)
            if ((fraction ?: 0f) > 0f) drawArc(color, -90f, 360f * fraction!!, false, Offset(inset, inset), box, style = stroke)
        }
        Spacer(Modifier.width(5.dp))
        Text(label, color = textColor, fontSize = 12.sp)
    }
}

/** The footer ring cluster: plan usage beside the context window. Either opens the usage sheet. */
@Composable
fun UsageRings(usage: ContextUsage?, account: AgentAccountView?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val plan = planFraction(account)
    val ctx = contextFraction(usage)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (plan != null) RingChip(plan, planColor(plan), "${(plan * 100).toInt()}%", onClick)
        if (hasContextWindow(usage)) RingChip(ctx, contextColor(ctx), ctx?.let { "${(it * 100).toInt()}%" } ?: "—", onClick)
    }
}
