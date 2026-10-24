package sh.zeron.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.zeron.android.app.AppModel

/** Rename a device (this phone or a computer), as the desktop's device switcher does. */
@Composable
fun RenameDeviceDialog(app: AppModel, deviceId: String, currentName: String, onDone: () -> Unit) {
    val client = app.client ?: return
    var name by remember(deviceId) { mutableStateOf(currentName) }
    var error by remember(deviceId) { mutableStateOf<String?>(null) }
    fun save() {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        try {
            client.renameDevice(deviceId, trimmed)
            app.refresh()
            onDone()
        } catch (e: Exception) {
            error = (e as? uniffi.zeron_core.CoreException)?.message?.removePrefix("message=") ?: e.message
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Rename device") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    name, { name = it; error = null }, singleLine = true, label = { Text("Name") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
                )
                error?.let { Text(it, color = Z.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = name.isNotBlank() && name.trim() != currentName) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
