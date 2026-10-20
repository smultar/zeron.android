package sh.zeron.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.InvocationView

/** The `/command` or `$skill` word being typed at the end of the composer, if any. */
class InvocationToken(val prefix: Char, val query: String, val start: Int)

fun trailingInvocation(text: String): InvocationToken? {
    val start = text.indexOfLast { it.isWhitespace() } + 1
    val word = text.substring(start)
    if (word.isEmpty() || (word[0] != '/' && word[0] != '$')) return null
    return InvocationToken(word[0], word.substring(1), start)
}

/** What the popup lists for `token`: `/` offers commands and skills, `$` only skills; prefix matches first. */
fun matchInvocations(all: List<InvocationView>, token: InvocationToken?): List<InvocationView> {
    if (token == null) return emptyList()
    val q = token.query.lowercase()
    return all
        .filter { token.prefix == '/' || it.isSkill }
        .filter { q.isEmpty() || it.name.lowercase().contains(q) }
        .sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 }
}

/** The text with the typed word replaced by the picked entry. */
fun insertInvocation(text: String, token: InvocationToken, pick: InvocationView) =
    text.substring(0, token.start) + pick.insertion + " "

/**
 * The agent's commands and skills for a workspace, fetched the first time the user starts typing
 * `/` or `$` and kept until the workspace or agent changes. A host that can't answer yields none.
 */
@Composable
fun rememberInvocations(
    client: CoreClient, deviceId: String?, harness: String, chatId: String?, spaceId: String?, wanted: Boolean,
): List<InvocationView> {
    var items by remember(deviceId, harness, chatId, spaceId) { mutableStateOf<List<InvocationView>?>(null) }
    LaunchedEffect(deviceId, harness, chatId, spaceId, wanted) {
        if (wanted && items == null && deviceId != null && harness.isNotEmpty()) {
            items = try {
                client.listInvocations(deviceId, harness, chatId, spaceId, null)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    return items.orEmpty()
}

@Composable
fun InvocationPopup(matches: List<InvocationView>, onPick: (InvocationView) -> Unit, modifier: Modifier = Modifier) {
    if (matches.isEmpty()) return
    Column(
        modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(16.dp)).background(Z.elevated)
            .border(0.5.dp, Z.hairline, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) {
        for (m in matches) {
            Column(Modifier.fillMaxWidth().clickable { onPick(m) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text((if (m.isSkill) "$" else "/") + m.name, color = Z.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    m.inputHint?.let { Text(it, color = Z.tertiary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (m.description.isNotBlank()) Text(m.description, color = Z.secondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
