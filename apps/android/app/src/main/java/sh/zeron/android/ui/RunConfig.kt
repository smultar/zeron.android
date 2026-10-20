package sh.zeron.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.zeron_core.HarnessInfo
import uniffi.zeron_core.ModelInfo
import uniffi.zeron_core.ModelOption
import uniffi.zeron_core.reasoningLabel

/** What a run is configured with: the agent, model, thinking level and per-model options. */
data class RunConfig(
    val harness: String,
    val model: String?,
    val effort: String?,
    val options: Map<String, String>,
)

/** New chats start at Medium thinking. */
const val DEFAULT_EFFORT = "medium"

fun defaultEffort(info: ModelInfo?): String? {
    val levels = info?.reasoningLevels.orEmpty()
    return when {
        levels.isEmpty() -> null
        DEFAULT_EFFORT in levels -> DEFAULT_EFFORT
        else -> info?.defaultReasoning?.takeIf { it in levels } ?: levels.first()
    }
}

/** The fast side of a speed option (Fast / On / priority tier). */
fun isFastChoice(c: uniffi.zeron_core.ModelOptionChoice): Boolean {
    val id = c.id.lowercase()
    return id == "fast" || id == "on" || id == "true" || id == "priority" || c.label.contains("fast", true)
}

/** Where a new run starts: speed is always the slow choice (fast is opt-in), anything else its default. */
fun startChoice(o: ModelOption): String =
    if (isSpeed(o)) o.choices.firstOrNull { !isFastChoice(it) }?.id ?: o.defaultChoice else o.defaultChoice

/** Each option where a new run starts — for speed that is Standard, i.e. slow. */
fun defaultOptions(info: ModelInfo?): Map<String, String> = info?.options?.associate { it.id to startChoice(it) } ?: emptyMap()

/**
 * Starting options, overridden by `saved` picks the model still offers (a remembered choice never
 * outlives its model). Speed is never remembered: fast mode is opted into per session.
 */
fun sanitizeOptions(info: ModelInfo?, saved: Map<String, String>): Map<String, String> =
    info?.options?.associate { o ->
        o.id to (saved[o.id]?.takeIf { c -> !isSpeed(o) && o.choices.any { it.id == c } } ?: startChoice(o))
    } ?: emptyMap()

/** Fast mode / speed / service tier: the options the user sees as Slow / Fast. */
fun isSpeed(o: ModelOption): Boolean {
    val id = o.id.lowercase()
    return id == "fastmode" || id == "speed" || id == "servicetier" || o.label.contains("fast", true) || o.label.contains("speed", true)
}

/** Does `to` differ from `from` in a way that changes what the provider caches? */
fun affectsCache(from: RunConfig, to: RunConfig): Boolean =
    from.model != to.model || from.effort != to.effort || from.options != to.options

fun speedIsFast(info: ModelInfo?, options: Map<String, String>): Boolean {
    val o = info?.options?.firstOrNull(::isSpeed) ?: return false
    val chosen = options[o.id] ?: startChoice(o)
    return o.choices.firstOrNull { it.id == chosen }?.let(::isFastChoice) == true
}

/** Switch model, keeping the thinking level if the new model has it, else Medium. Options reset (slow). */
fun withModel(cfg: RunConfig, info: ModelInfo): RunConfig {
    val levels = info.reasoningLevels
    val effort = cfg.effort?.takeIf { it in levels } ?: defaultEffort(info)
    return cfg.copy(model = info.id, effort = effort, options = defaultOptions(info))
}

const val CACHE_WARNING =
    "Changing the model, thinking level, speed or context size in the middle of a conversation breaks the prompt cache. " +
        "The next message re-reads the whole conversation at full price and can be noticeably slower."

/**
 * The run settings panel: agent, model, thinking level, speed and any other
 * model options. In a live conversation `warnCache` shows the cache warning
 * once the pending change would affect it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunConfigPanel(
    config: RunConfig,
    onChange: (RunConfig) -> Unit,
    harnesses: List<HarnessInfo>,
    models: List<ModelInfo>,
    agentLocked: Boolean,
    warnCache: Boolean,
    modifier: Modifier = Modifier,
    /** Pick a starred model from any agent (new chats only): switches the agent too. */
    onPickFavorite: ((Favorites.Fav) -> Unit)? = null,
) {
    val info = models.firstOrNull { it.id == config.model }
    val favorites = Favorites.version.let { Favorites.all() }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
        Section("Agent")
        if (agentLocked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OptionChip(harnesses.firstOrNull { it.id == config.harness }?.label ?: config.harness, true, mark = config.harness) {}
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Rounded.Lock, null, tint = Z.tertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Fixed for this session. You can still switch models.", color = Z.subline, fontSize = 12.sp)
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in harnesses) OptionChip(h.label, h.id == config.harness, mark = h.id) {
                    if (h.id != config.harness) onChange(RunConfig(h.id, null, null, emptyMap()))
                }
            }
        }

        // Favorites across agents (a chat's agent is fixed, so only new chats mix them).
        if (onPickFavorite != null && favorites.isNotEmpty()) {
            Section("Favorites")
            for (f in favorites) {
                val current = f.harness == config.harness && f.model == config.model
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (!current) onPickFavorite(f) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentMark(f.harness, size = 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.label, color = Z.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(harnesses.firstOrNull { it.id == f.harness }?.label ?: f.harness, color = Z.subline, fontSize = 12.sp)
                    }
                    if (current) Icon(Icons.Rounded.Check, null, tint = Z.accent)
                }
            }
        }

        Section("Model")
        // Starred models float to the top, otherwise in catalog order.
        val ordered = models.sortedByDescending { m -> favorites.any { it.harness == config.harness && it.model == m.id } }
        for (m in ordered) {
            val starred = favorites.any { it.harness == config.harness && it.model == m.id }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { if (m.id != config.model) onChange(withModel(config, m)) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.label, color = Z.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    m.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Z.subline, fontSize = 12.sp) }
                }
                if (m.id == config.model) Icon(Icons.Rounded.Check, null, tint = Z.accent)
                androidx.compose.material3.IconButton(onClick = { Favorites.toggle(config.harness, m.id, m.label) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        if (starred) "Remove ${m.label} from favorites" else "Add ${m.label} to favorites",
                        tint = if (starred) Z.warning else Z.tertiary, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        val levels = info?.reasoningLevels.orEmpty()
        if (levels.isNotEmpty()) {
            Section("Thinking")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (l in levels) OptionChip(reasoningLabel(l), l == config.effort) { onChange(config.copy(effort = l)) }
            }
        }

        for (o in info?.options.orEmpty()) {
            val current = config.options[o.id] ?: startChoice(o)
            if (isSpeed(o) && o.choices.size == 2) {
                Section("Speed")
                Row(Modifier.clip(CircleShape).background(Z.controlFill).padding(3.dp)) {
                    for (c in o.choices) {
                        Segment(if (isFastChoice(c)) "Fast" else "Slow", c.id == current) { onChange(config.copy(options = config.options + (o.id to c.id))) }
                    }
                }
                Text(
                    if (current == startChoice(o)) "Standard speed. The default." else "Faster responses, at a higher price.",
                    color = Z.subline, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                )
            } else {
                Section(o.label)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in o.choices) OptionChip(c.label, c.id == current) { onChange(config.copy(options = config.options + (o.id to c.id))) }
                }
            }
        }

        if (warnCache) CacheWarning(Modifier.padding(top = 18.dp))
    }
}

@Composable
fun CacheWarning(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Z.warning.copy(alpha = 0.12f))
            .border(0.5.dp, Z.warning.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Warning, null, tint = Z.warning, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("This breaks the prompt cache", color = Z.warning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(CACHE_WARNING, color = Z.text.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(), color = Z.tertiary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp, start = 2.dp),
    )
}

@Composable
private fun OptionChip(text: String, selected: Boolean, mark: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(if (selected) Z.accentSoft else Z.controlFill)
            .then(if (selected) Modifier.border(1.dp, Z.accent.copy(alpha = 0.5f), CircleShape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) {
            AgentMark(mark, size = 16.dp, tint = if (selected) Z.accent else Z.text)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (selected) Z.accent else Z.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(if (selected) Z.elevated else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 8.dp),
    ) { Text(text, color = if (selected) Z.text else Z.secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** A bottom sheet around [RunConfigPanel] with Apply / Cancel — for changing a live session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunConfigSheet(
    initial: RunConfig,
    draft: RunConfig,
    onDraft: (RunConfig) -> Unit,
    harnesses: List<HarnessInfo>,
    models: List<ModelInfo>,
    agentLocked: Boolean,
    hasHistory: Boolean,
    applyLabel: String,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val changed = draft != initial
    val warn = hasHistory && affectsCache(initial, draft)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Z.elevated) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Run settings", color = Z.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp))
            RunConfigPanel(draft, onDraft, harnesses, models, agentLocked, warn, Modifier.weight(1f, fill = false))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Z.secondary) }
                TextButton(onClick = onApply, enabled = changed) {
                    Text(applyLabel, color = if (changed) (if (warn) Z.warning else Z.accent) else Z.tertiary, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
