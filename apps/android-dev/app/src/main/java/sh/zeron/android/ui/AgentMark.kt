package sh.zeron.android.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.zeron.android.design.Icons

/** Claude's brand orange, as on the desktop. */
val CLAUDE_ORANGE = Color(0xFFD97757)

/** The mark's tint: Claude keeps its orange, everything else takes `fallback`. */
fun agentMarkTintArgb(harness: String, fallback: Int): Int =
    if (harness == "claude-code" || harness == "mock") CLAUDE_ORANGE.toArgb() else fallback

/**
 * The harness brand marks the desktop bundles (the `-mark.svg` icons in `crates/ui/assets/icons`).
 * `claude-code` and `mock` share the Claude mark, like the desktop.
 */
fun agentMarkAsset(harness: String): String = when (harness) {
    "codex" -> "brand-openai"
    "cursor" -> "brand-cursor"
    "devin" -> "brand-devin"
    "grok" -> "brand-grok"
    "hermes" -> "brand-hermes"
    "pi" -> "brand-pi"
    "opencode" -> "brand-opencode"
    "antigravity" -> "brand-antigravity"
    else -> "brand-claude"
}

/** An agent's mark: Claude in its brand orange, the rest tinted with `tint`. */
@Composable
fun AgentMark(harness: String, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = Z.text) {
    val color = if (harness == "claude-code" || harness == "mock") CLAUDE_ORANGE else tint
    val density = LocalDensity.current.density
    val dark = Z.dark
    Canvas(modifier.size(size)) {
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.scale(density, density)
            Icons.draw(nc, agentMarkAsset(harness), RectF(0f, 0f, this.size.width / density, this.size.height / density), density, color.toArgb(), dark)
            nc.restore()
        }
    }
}
