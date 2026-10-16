package app.dc8.maconecopy.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.dc8.maconecopy.Settings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** A native folder picker (macOS FileDialog in directory mode). */
fun chooseFolder(title: String, start: String?): String? {
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    try {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        start?.let { dialog.directory = it }
        dialog.isVisible = true
        return dialog.file?.let { File(dialog.directory, it).absolutePath }
    } finally {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}

@Composable
private fun FolderField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { chooseFolder(label, value.ifBlank { null })?.let(onChange) }) { Text("Choose…") }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) =
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) }, label = { Text(label) }, singleLine = true, modifier = modifier)

/** First run: pick the relay folder in OneDrive and a display name. */
@Composable
fun SetupScreen(model: AppModel) {
    var relay by remember { mutableStateOf(Settings.defaultRelayDir()?.toString() ?: "") }
    var name by remember { mutableStateOf(model.settings.displayName ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Welcome to mac-one-copy", style = MaterialTheme.typography.headlineMedium)
            Text("Pick a folder inside your OneDrive. Every Mac that should exchange files uses the same folder.")
            FolderField("Relay folder (inside OneDrive)", relay) { relay = it }
            OutlinedTextField(name, { name = it }, label = { Text("Name of this Mac") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Muted("Received files go under ${model.settings.home}; change it later in Settings.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = relay.isNotBlank() && name.isNotBlank(), onClick = {
                error = null
                if (!relay.contains("CloudStorage/OneDrive")) error = "Note: this folder does not look like it is inside OneDrive."
                model.updateSettings({ it.copy(relayDir = relay.trim(), displayName = name.trim()) })
            }) { Text("Continue") }
        }
    }
}

@Composable
fun SettingsScreen(model: AppModel) {
    val s = model.settings
    var relay by remember(s) { mutableStateOf(s.relayDir ?: "") }
    var name by remember(s) { mutableStateOf(s.displayName ?: "") }
    var home by remember(s) { mutableStateOf(s.homeDir ?: s.home.toString()) }
    var window by remember(s) { mutableStateOf(s.windowChunks.toString()) }
    var chunkMb by remember(s) { mutableStateOf(Format.plain(s.chunkSize / (1024.0 * 1024))) }
    var pollSec by remember(s) { mutableStateOf(Format.plain(s.pollMillis / 1000.0)) }
    var verify by remember(s) { mutableStateOf(s.verifyFromDisk) }
    var retention by remember(s) { mutableStateOf(s.trashRetentionDays.toString()) }
    var trashSize by remember { mutableStateOf<Long?>(null) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { trashSize = model.engine.trashSize() }

    val draft = runCatching {
        s.copy(
            relayDir = relay.trim().ifEmpty { null }, displayName = name.trim(), homeDir = home.trim().ifEmpty { null },
            windowChunks = window.toInt(), chunkSize = (chunkMb.toDouble() * 1024 * 1024).toLong(),
            pollMillis = (pollSec.toDouble() * 1000).toLong(), verifyFromDisk = verify, trashRetentionDays = retention.toInt(),
        )
    }.getOrNull()
    val problems = draft?.problems() ?: listOf("Numbers are not valid")

    Column(Modifier.verticalScroll(rememberScrollState()).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Title("Settings")
        FolderField("Relay folder (inside OneDrive)", relay) { relay = it; saved = false }
        OutlinedTextField(name, { name = it; saved = false }, label = { Text("Display name ({host} = host name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FolderField("Home folder (transfers are relative to it)", home) { home = it; saved = false }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Window (chunks)", window, { window = it; saved = false }, Modifier.weight(1f))
            NumberField("Chunk size (MB)", chunkMb, { chunkMb = it; saved = false }, Modifier.weight(1f))
            NumberField("Poll interval (s)", pollSec, { pollSec = it; saved = false }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(verify, { verify = it; saved = false })
            Text("Verify from disk: re-read each received file once before the final rename")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Trash retention (days)", retention, { retention = it; saved = false }, Modifier.width(220.dp))
            Muted("Trash: ${trashSize?.let { Format.bytes(it) } ?: "…"}")
            OutlinedButton(onClick = { model.run { model.engine.emptyTrash(); trashSize = model.engine.trashSize() } }) { Text("Empty trash") }
        }
        Muted("A new chunk size applies to new jobs. Machine id ${model.engine.machineId}, data in ${model.engine.paths.appData}.")
        problems.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = problems.isEmpty() && draft != s, onClick = { model.updateSettings({ draft!! }) { saved = true } }) { Text("Save") }
            Spacer(Modifier.size(12.dp))
            if (saved) Muted("Saved")
        }
    }
}
