package sh.zeron.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class SheetAction(val icon: ImageVector, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * The press-and-hold menu: a bottom sheet with the subject's title, then rounded action rows with
 * tinted icon chips. Actions in the same group sit on one card; destructive ones get their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, subtitle: String?, groups: List<List<SheetAction>>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = Z.background, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Text(title, color = Z.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Z.subline, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
            Spacer(Modifier.height(14.dp))
            for (group in groups) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Z.elevated)) {
                    for (a in group) {
                        val tint = if (a.danger) Z.danger else Z.text
                        Row(
                            Modifier.fillMaxWidth().clickable { onDismiss(); a.onClick() }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(if (a.danger) Z.danger.copy(alpha = 0.14f) else Z.controlFill),
                                contentAlignment = Alignment.Center,
                            ) { Icon(a.icon, null, tint = tint, modifier = Modifier.size(19.dp)) }
                            Spacer(Modifier.width(14.dp))
                            Text(a.label, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
