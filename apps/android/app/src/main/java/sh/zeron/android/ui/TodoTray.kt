package sh.zeron.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.zeron_core.TodoState
import uniffi.zeron_core.TodoView

/**
 * The agent's checklist, docked above the composer like the desktop's: a header with progress and
 * the item being worked on (else the next one), expanding to the whole list.
 */
@Composable
fun TodoTray(items: List<TodoView>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val done = items.count { it.status == TodoState.COMPLETED }
    val headline = items.firstOrNull { it.status == TodoState.IN_PROGRESS } ?: items.firstOrNull { it.status != TodoState.COMPLETED }
    Column(
        modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 6.dp).clip(RoundedCornerShape(16.dp)).background(Z.elevated)
            .border(0.5.dp, Z.hairline, RoundedCornerShape(16.dp)).animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$done/${items.size}", color = Z.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(
                headline?.text ?: "All done", color = Z.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.KeyboardArrowDown, if (open) "Collapse" else "Expand", tint = Z.tertiary, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
        }
        if (open) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in items) {
                    Row(verticalAlignment = Alignment.Top) {
                        TodoMark(item.status)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            item.text, fontSize = 14.sp, lineHeight = 19.sp,
                            color = if (item.status == TodoState.COMPLETED) Z.tertiary else Z.text,
                            textDecoration = if (item.status == TodoState.COMPLETED) TextDecoration.LineThrough else null,
                            fontWeight = if (item.status == TodoState.IN_PROGRESS) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoMark(status: TodoState) {
    val m = Modifier.padding(top = 2.dp).size(16.dp)
    when (status) {
        TodoState.COMPLETED -> Box(m.clip(CircleShape).background(Z.success), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = Z.background, modifier = Modifier.size(11.dp))
        }
        TodoState.IN_PROGRESS -> Box(m.border(1.5.dp, Z.accent, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Z.accent))
        }
        TodoState.PENDING -> Box(m.border(1.5.dp, Z.tertiary.copy(alpha = 0.6f), CircleShape))
    }
}
