package sh.zeron.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.zeron.android.app.AppModel
import uniffi.zeron_core.OrbFrame
import uniffi.zeron_core.OrbPreset
import uniffi.zeron_core.OrbRenderer
import uniffi.zeron_core.VoiceCallPhase
import uniffi.zeron_core.VoiceEndReason
import uniffi.zeron_core.VoiceOrb
import uniffi.zeron_core.VoiceSpeaker

/**
 * The desktop voice orb (`zeron-orb`): the core runs the same geometry, clock,
 * audio response and 300 ms state crossfades as the desktop widget; this only
 * paints its frames — lines, then disks back to front, in monochrome ink on a
 * transparent canvas, at 30 fps while visible.
 */
@Composable
fun Orb(orb: VoiceOrb, microphone: Float, speaker: Float, modifier: Modifier = Modifier, preset: OrbPreset = OrbPreset.HERO) {
    val renderer = remember(preset) { OrbRenderer(preset, orb) }
    LaunchedEffect(orb) { renderer.setOrb(orb) }
    LaunchedEffect(microphone, speaker) { renderer.setAudioLevels(microphone, speaker) }
    var frame by remember(renderer) { mutableStateOf<OrbFrame?>(null) }
    val dark = Z.dark
    LaunchedEffect(renderer) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameMillis { now ->
                if (now - last >= 33) {
                    last = now
                    frame = renderer.nextFrame(true, false)
                }
            }
        }
    }
    Canvas(modifier) {
        val f = frame ?: return@Canvas
        if (f.size <= 0f) return@Canvas
        val scale = minOf(size.width, size.height) / f.size
        val ox = (size.width - f.size * scale) / 2f
        val oy = (size.height - f.size * scale) / 2f
        fun ink(white: Float, alpha: Float): Color {
            val w = white.coerceIn(0f, 1f).let { if (dark) 1f - it else it }
            return Color(w, w, w, alpha.coerceIn(0f, 1f))
        }
        val lines = f.lines
        var i = 0
        while (i + 6 < lines.size) {
            drawLine(
                ink(lines[i + 5], lines[i + 6]),
                Offset(ox + lines[i] * scale, oy + lines[i + 1] * scale),
                Offset(ox + lines[i + 2] * scale, oy + lines[i + 3] * scale),
                strokeWidth = lines[i + 4] * scale,
            )
            i += 7
        }
        val dots = f.dots
        i = 0
        while (i + 4 < dots.size) {
            drawCircle(ink(dots[i + 3], dots[i + 4]), dots[i + 2] * scale, Offset(ox + dots[i] * scale, oy + dots[i + 1] * scale))
            i += 5
        }
    }
}

/** A tick every second while visible, for the call timer. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The full-screen call: orb, live caption, mute and hang up. */
@Composable
fun VoiceStage(app: AppModel, onOpenChat: (String) -> Unit) {
    val voice = app.voice
    val state = voice.state
    val now = rememberNow()
    // A call keeps the screen awake, like the phone app.
    val view = LocalView.current
    LaunchedEffect(voice.live) { view.keepScreenOn = voice.live }

    Box(Modifier.fillMaxSize().background(Z.background).safeTop().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Top: minimize · host
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (voice.live) voice.showStage = false else voice.dismissEndReason() }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = Z.text)
                }
                Spacer(Modifier.weight(1f))
                if (voice.hostName.isNotEmpty()) Text(voice.hostName, color = Z.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(12.dp))
            }

            Spacer(Modifier.weight(0.7f))
            Orb(
                voice.orb, state?.microphone ?: 0f, state?.speaker ?: 0f,
                Modifier.size(280.dp),
            )
            Spacer(Modifier.height(18.dp))

            val reason = voice.endReason
            if (reason != null && !voice.live) {
                Text(voice.message(reason), color = Z.text, fontSize = 18.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (reason != VoiceEndReason.MICROPHONE_DENIED) PillButton("Call again", onClick = { voice.dismissEndReason(); voice.showStage = true; voice.start() })
                    PillButton("Close", filled = false, onClick = { voice.dismissEndReason() })
                }
            } else {
                Text(voice.statusText, color = Z.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                voice.elapsedText(now)?.let { Text(it, color = Z.subline, fontSize = 14.sp, fontFamily = LocalMono.current, modifier = Modifier.padding(top = 2.dp)) }
                Spacer(Modifier.height(18.dp))
                // Live caption: the current utterance, labelled by who said it.
                val caption = state?.caption.orEmpty()
                if (caption.isNotEmpty()) {
                    val who = when (state?.captionSpeaker) {
                        VoiceSpeaker.USER -> "You"
                        VoiceSpeaker.ASSISTANT -> "Codex"
                        null -> if (state?.speaking == true) "Codex" else null
                    }
                    who?.let { Text(it.uppercase(), color = Z.tertiary, fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold) }
                    AnimatedContent(caption, label = "caption") { text ->
                        Text(text, color = Z.text.copy(alpha = 0.9f), fontSize = 17.sp, textAlign = TextAlign.Center, lineHeight = 24.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            if (voice.live) {
                val chat = state?.chatId
                if (chat != null) {
                    Row(
                        Modifier.clip(CircleShape).background(Z.controlFill).clickable { voice.showStage = false; onOpenChat(chat) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ChatBubbleOutline, null, tint = Z.secondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open transcript", color = Z.secondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(20.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    CallButton(if (voice.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (voice.muted) "Unmute" else "Mute", if (voice.muted) Z.text else Z.controlFill, if (voice.muted) Z.background else Z.text) { voice.toggleMute() }
                    CallButton(Icons.Rounded.CallEnd, "Hang up", Z.danger, Color.White, large = true) { voice.stop() }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, fill: Color, tint: Color, large: Boolean = false, onClick: () -> Unit) {
    val side = if (large) 72.dp else 60.dp
    Box(Modifier.size(side).clip(CircleShape).background(fill).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(if (large) 32.dp else 26.dp))
    }
}

/** A minimized call: a bar at the top of whatever is showing. Tap to return, or hang up. */
@Composable
fun VoiceBar(app: AppModel, modifier: Modifier = Modifier) {
    val voice = app.voice
    if (!voice.live || voice.showStage) return
    val now = rememberNow()
    Row(
        modifier.fillMaxWidth().safeTop().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp)).background(Z.text)
            .clickable { voice.showStage = true }.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.GraphicEq, null, tint = Z.background, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Voice call · ${voice.elapsedText(now) ?: voice.statusText}", color = Z.background, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.size(34.dp).clip(CircleShape).background(Z.danger).clickable { voice.stop() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CallEnd, "Hang up", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The composer's voice button: a waveform in a circle that starts a call.
 * With several hosts and none chosen it asks which to call first.
 */
@Composable
fun VoiceButton(app: AppModel, modifier: Modifier = Modifier) {
    val voice = app.voice
    var pick by remember { mutableStateOf(false) }
    var none by remember { mutableStateOf(false) }
    Box(
        modifier.size(42.dp).clip(CircleShape).background(Z.text).clickable {
            when {
                voice.startHost != null -> voice.start()
                voice.hosts.size > 1 -> pick = true
                else -> none = true
            }
        },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.GraphicEq, "Voice call", tint = Z.background, modifier = Modifier.size(22.dp)) }

    if (pick) {
        PickerSheet(
            title = "Call which computer?",
            rows = voice.hosts.map { PickerRow(it.id, it.name, subtitle = it.platform) },
            selectedKey = voice.selectedHost,
            onPick = { id -> pick = false; voice.hosts.firstOrNull { it.id == id }?.let { voice.start(it) } },
            onDismiss = { pick = false },
        )
    }
    if (none) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { none = false },
            title = { Text("No computer to call") },
            text = { Text("Voice runs on a computer signed in to Codex with ChatGPT. Open Zeron there, make sure remote voice is on, and it will show up here.") },
            confirmButton = { TextButton(onClick = { none = false }) { Text("OK") } },
        )
    }
}
