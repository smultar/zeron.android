package sh.zeron.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.FileMatch
import uniffi.zeron_core.fileMentionLink

/** The `@query` being typed at the end of the composer (an `@` that starts a word). */
class MentionToken(val query: String, val start: Int)

fun trailingMention(text: String): MentionToken? {
    val start = text.indexOfLast { it.isWhitespace() } + 1
    val word = text.substring(start)
    if (word.isEmpty() || word[0] != '@') return null
    return MentionToken(word.substring(1), start)
}

/**
 * The files picked into one message. The composer shows a short `@name`; the message goes out
 * with the canonical mention links the host understands.
 */
class MentionIndex {
    private val tokens = HashMap<String, FileMatch>()

    /** `@basename`, or `@parent/basename` when another picked file already took the name. */
    fun insert(text: String, at: MentionToken, file: FileMatch): String {
        val parts = file.path.trim('/').split('/')
        var token = "@" + parts.last()
        val taken = tokens[token]
        if (taken != null && taken.path != file.path && parts.size > 1) token = "@" + parts.takeLast(2).joinToString("/")
        tokens[token] = file
        return text.substring(0, at.start) + token + " "
    }

    /** Live tokens become links, longest first; picks no longer in the text are forgotten. */
    fun encode(text: String): String {
        var out = text
        for ((token, file) in tokens.entries.sortedByDescending { it.key.length }) {
            if (out.contains(token)) out = out.replace(token, fileMentionLink(file.path, file.isDir))
        }
        return out
    }

    fun reset() = tokens.clear()
}

/** Files matching the typed `@query`, debounced; a host that can't answer yields none. */
@Composable
fun rememberMentionFiles(client: CoreClient, deviceId: String?, chatId: String?, spaceId: String?, token: MentionToken?): List<FileMatch> {
    val query = token?.query
    val files by produceState(emptyList<FileMatch>(), deviceId, chatId, spaceId, query) {
        if (query == null || deviceId.isNullOrEmpty()) {
            value = emptyList()
            return@produceState
        }
        delay(120)
        value = try {
            client.searchFiles(deviceId, chatId, spaceId, query).take(8)
        } catch (_: Exception) {
            emptyList()
        }
    }
    return files
}

@Composable
fun MentionPopup(files: List<FileMatch>, onPick: (FileMatch) -> Unit, modifier: Modifier = Modifier) {
    if (files.isEmpty()) return
    Column(
        modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(16.dp)).background(Z.elevated)
            .border(0.5.dp, Z.hairline, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) {
        for (f in files) {
            val path = f.path.trim('/')
            val name = path.substringAfterLast('/') + if (f.isDir) "/" else ""
            val dir = path.substringBeforeLast('/', "")
            Row(Modifier.fillMaxWidth().clickable { onPick(f) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                Text(name, color = Z.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (dir.isNotEmpty()) {
                    Text(
                        "  $dir", color = Z.tertiary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(top = 1.dp),
                    )
                }
            }
        }
    }
}
