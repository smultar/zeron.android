package sh.zeron.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One selectable row of a [PickerSheet]. */
class PickerRow(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    /** Dimmed, e.g. a device that is offline. */
    val muted: Boolean = false,
    /** Extra text the search also matches (a path, a device name). */
    val searchText: String = "",
    val leading: (@Composable () -> Unit)? = null,
)

/** Ranked substring match like the desktop's `popover::filter_indices`: prefix hits first, then word starts, then anywhere. */
fun <T> filterRanked(query: String, items: List<T>, text: (T) -> String): List<T> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return items
    return items.mapNotNull { item ->
        val t = text(item).lowercase()
        val at = t.indexOf(q)
        if (at < 0) null else item to (if (at == 0) 0 else if (t[at - 1] == ' ' || t[at - 1] == '/' || t[at - 1] == '-') 1 else 2)
    }.sortedBy { it.second }.map { it.first }
}

/**
 * The desktop's picker popover as a bottom sheet: a search box, the rows with
 * a check on the current one, and optional footer actions (rows of their own).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    title: String,
    rows: List<PickerRow>,
    selectedKey: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    searchable: Boolean = rows.size > 6,
    placeholder: String = "Search",
    footer: @Composable () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, rows) { filterRanked(query, rows) { it.title + " " + it.searchText } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Z.elevated) {
        Column(Modifier.navigationBarsPadding()) {
            Text(title, color = Z.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 22.dp))
            if (searchable) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).clip(RoundedCornerShape(12.dp)).background(Z.controlFill)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Z.tertiary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(placeholder, color = Z.tertiary, fontSize = 16.sp)
                        BasicTextField(
                            query, { query = it }, singleLine = true, cursorBrush = SolidColor(Z.accent),
                            textStyle = TextStyle(color = Z.text, fontSize = 16.sp), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                Spacer(Modifier.padding(top = 8.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (shown.isEmpty()) item { Text("No matches", color = Z.subline, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) }
                items(shown, key = { it.key }) { row ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(row.key) }.padding(horizontal = 22.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.leading?.let { it(); Spacer(Modifier.width(12.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.title, color = if (row.muted) Z.secondary else Z.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            row.subtitle?.let { Text(it, color = Z.subline, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        if (row.key == selectedKey) Icon(Icons.Rounded.Check, null, tint = Z.accent)
                    }
                }
            }
            footer()
            Spacer(Modifier.padding(bottom = 8.dp))
        }
    }
}

/** A footer action row for a [PickerSheet] (Add project…, No project). */
@Composable
fun PickerAction(
    title: String, subtitle: String? = null, selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = Z.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            subtitle?.let { Text(it, color = Z.subline, fontSize = 12.sp) }
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = Z.accent)
    }
}
