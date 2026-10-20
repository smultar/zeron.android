package sh.zeron.android.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.zeron.android.design.Glyphs
import uniffi.zeron_core.ChatIndicator

/** Animation clock for glyphs drawn on a canvas. */
@Composable
fun rememberGlyphClock(running: Boolean): Long {
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (running) while (true) androidx.compose.runtime.withFrameMillis { now = it }
    }
    return now
}

/** The sidebar status glyph: spinner when working, dot/check otherwise. */
@Composable
fun StatusGlyph(indicator: ChatIndicator, unseen: Boolean, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val working = indicator == ChatIndicator.WORKING
    val now = rememberGlyphClock(working)
    val dark = Z.dark
    val accent = Z.accent.copy(alpha = 0.6f)
    val failed = Z.danger.copy(alpha = 0.65f)
    val done = Z.success.copy(alpha = 0.9f)
    val idle = (if (dark) Color.White else Color.Black).copy(alpha = 0.14f)
    val density = LocalDensity.current.density
    Canvas(modifier.size(size)) {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.scale(density, density)
            val r = RectF(0f, 0f, this.size.width / density, this.size.height / density)
            when (indicator) {
                ChatIndicator.WORKING -> Glyphs.spinner(nc, r, dark, now)
                ChatIndicator.AWAITING_INPUT -> Glyphs.dot(nc, r, accent.toArgb(), 7f)
                ChatIndicator.ERRORED -> Glyphs.dot(nc, r, failed.toArgb(), 7f)
                ChatIndicator.COMPLETED -> if (unseen) Glyphs.check(nc, r, done.toArgb()) else Glyphs.dot(nc, r, idle.toArgb(), 7f)
                ChatIndicator.IDLE -> Glyphs.dot(nc, r, idle.toArgb(), 7f)
            }
            nc.restore()
        }
    }
}

/** A project monogram tile. */
@Composable
fun ProjectTile(name: String, colorIndex: Int, size: Dp = 28.dp) {
    val tone = Z.project(colorIndex)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tone.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull()?.uppercase() ?: "·",
            color = tone, fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().size(width = 0.dp, height = 0.5.dp).background(Z.hairline))
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    Box(
        modifier
            .clip(CircleShape)
            .background(if (filled) Z.text else Z.controlFill)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) Z.background else Z.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** PR badge: the PR icon and number tinted by state. */
@Composable
fun PrBadge(number: ULong, state: uniffi.zeron_core.PullRequestState) {
    val tone = when (state) {
        uniffi.zeron_core.PullRequestState.OPEN -> Z.success
        uniffi.zeron_core.PullRequestState.MERGED -> Z.accent
        uniffi.zeron_core.PullRequestState.CLOSED -> Z.danger
    }
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(tone.copy(alpha = 0.08f)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text("#$number", color = tone.copy(alpha = 0.85f), fontSize = 11.sp, fontFamily = LocalMono.current, fontWeight = FontWeight.Medium)
    }
}
