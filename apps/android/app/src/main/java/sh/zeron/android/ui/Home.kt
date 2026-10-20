package sh.zeron.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.zeron.android.app.AppModel

/**
 * The slide-over menu (hamburger, or swipe from the left edge): new session,
 * every session, archive and settings. It sits above whichever screen is
 * showing, so a chat opens it from its top-left button too. No wallpaper here.
 */
@Composable
fun AppDrawerContent(
    app: AppModel,
    close: (then: () -> Unit) -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onArchived: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = Z.background, windowInsets = WindowInsets(0), modifier = Modifier.width(332.dp)) {
        Column(Modifier.fillMaxSize().safeTop().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ZeronIcon(Modifier.size(30.dp))
                Spacer(Modifier.width(10.dp))
                Text("Zeron", color = Z.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            DrawerAction("New session", { Icon(Icons.Rounded.Edit, null, tint = Z.text, modifier = Modifier.size(20.dp)) }) { close(onNew) }
            SessionListScreen(app, onOpen = { id -> close { onOpen(id) } }, modifier = Modifier.weight(1f), embedded = true)
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Z.hairline))
            DrawerAction("Archived", { Icon(Icons.Rounded.Archive, null, tint = Z.secondary, modifier = Modifier.size(20.dp)) }) { close(onArchived) }
            DrawerAction(
                app.accountName.ifBlank { "Settings" },
                { Icon(Icons.Rounded.Settings, null, tint = Z.secondary, modifier = Modifier.size(20.dp)) },
            ) { close(onSettings) }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Home: a new chat, full-bleed. The menu button opens the slide-over. */
@Composable
fun HomeScreen(app: AppModel, onMenu: () -> Unit, onSettings: () -> Unit, onCreated: (String) -> Unit) {
    NewSessionScreen(app, onMenu = onMenu, onProfile = onSettings, onCreated = onCreated)
}

@Composable
private fun DrawerAction(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(14.dp))
        Text(label, color = Z.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
