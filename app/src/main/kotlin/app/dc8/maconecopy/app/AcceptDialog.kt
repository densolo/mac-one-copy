package app.dc8.maconecopy.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.dc8.maconecopy.Decision
import app.dc8.maconecopy.FileStatus
import app.dc8.maconecopy.OfferDetails
import app.dc8.maconecopy.OfferTarget

@Composable
fun AcceptDialog(
    details: OfferDetails,
    folders: Map<String, String>,
    error: String?,
    onDecide: (Decision) -> Unit,
    onFolders: (Map<String, String>) -> Unit,
    onClose: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(720.dp, 600.dp)),
        title = "Incoming from ${details.job.peerName}",
        icon = MacIntegration.iconPainter,
    ) {
        AppTheme { Surface(Modifier.fillMaxSize()) { AcceptContent(details, onDecide, folders, onFolders, error) } }
    }
}

/** An absolute folder as a path relative to home ("" for home itself), or null if it is not inside home. */
fun relativeToHome(folder: String, home: String): String? = when {
    folder == home -> ""
    folder.startsWith("$home/") -> folder.removePrefix("$home/")
    else -> null
}

/**
 * The dialog body, separate from the window so it can be tested. [folders] are the folders chosen so far
 * (root → folder relative to home); [onFolders] asks for a preview with new ones. [pickFolder] opens
 * a folder picker at a start folder and returns the chosen absolute path.
 */
@Composable
fun AcceptContent(
    details: OfferDetails,
    onDecide: (Decision) -> Unit,
    folders: Map<String, String> = emptyMap(),
    onFolders: (Map<String, String>) -> Unit = {},
    error: String? = null,
    pickFolder: (start: String) -> String? = { chooseFolder("Choose where to put it", it) },
) {
    val tree = remember(details) { OfferNode.tree(details) }
    val targets = remember(details) { details.targets.associateBy { it.root } }
    var pickError by remember { mutableStateOf<String?>(null) }
    fun pick(start: String, apply: (String) -> Map<String, String>) {
        val chosen = pickFolder(start) ?: return
        val rel = relativeToHome(chosen, details.home)
        if (rel == null) pickError = "Pick a folder inside your home folder (${Format.home(details.home)})."
        else { pickError = null; onFolders(apply(rel)) }
    }
    val expanded = remember(details) { mutableStateOf(tree.map { it.path }.toSet()) }
    val tooBig = details.totalBytes > details.freeBytesHere
    Column(Modifier.padding(16.dp)) {
        Text("${details.job.peerName} wants to send you", style = MaterialTheme.typography.titleMedium)
        Text("${Format.count(details.files.size, "file")} · ${Format.bytes(details.totalBytes)}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${Format.bytes(details.freeBytesHere)} free here" + if (tooBig) " — not enough space!" else "",
            color = if (tooBig) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("free"),
        )
        val counts = tree.fold(mapOf<FileStatus, Int>()) { acc, n -> FileStatus.entries.associateWith { (acc[it] ?: 0) + (n.counts[it] ?: 0) } }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (s in FileStatus.entries) StatusBadge(s, counts[s] ?: 0)
            Spacer(Modifier.weight(1f))
            if (details.targets.size > 1) TextButton(onClick = {
                pick(details.home) { rel -> details.targets.associate { it.root to rel } }
            }) { Text("Put everything in…") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            val rows = flatten(tree, expanded.value)
            items(rows, key = { it.second.path }) { (depth, node) ->
                val target = if (depth == 0) targets[node.path] else null
                TreeRow(
                    depth, node, node.path in expanded.value, target,
                    onChange = { t -> pick(java.io.File(t.path).parent ?: details.home) { rel -> folders + (t.root to rel) } },
                    onReset = { t -> onFolders(folders - t.root) },
                ) {
                    expanded.value = if (node.path in expanded.value) expanded.value - node.path else expanded.value + node.path
                }
            }
        }
        if (details.skipped.isNotEmpty()) IssueList(details.skipped, "${Format.count(details.skipped.size, "item")} left out by the sender")
        (pickError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("error")) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = { onDecide(Decision.Decline) }) { Text("Decline") }
            OutlinedButton(onClick = { onDecide(Decision.AcceptCopy) }) { Text("Accept as copy") }
            Button(onClick = { onDecide(Decision.Accept) }) { Text("Accept") }
        }
        Text(
            "Accept replaces changed files (originals go to the trash) and skips identical ones. Accept as copy touches nothing here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun flatten(nodes: List<OfferNode>, expanded: Set<String>, depth: Int = 0): List<Pair<Int, OfferNode>> =
    nodes.flatMap { n -> listOf(depth to n) + if (n.isDir && n.path in expanded) flatten(n.children, expanded, depth + 1) else emptyList() }

/** One tree line; a root also shows where it lands with Accept and with Accept as copy. */
@Composable
private fun TreeRow(
    depth: Int,
    node: OfferNode,
    open: Boolean,
    target: OfferTarget?,
    onChange: (OfferTarget) -> Unit = {},
    onReset: (OfferTarget) -> Unit = {},
    toggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = node.isDir, onClick = toggle).padding(start = (depth * 18).dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (node.isDir) (if (open) "▾ 📁" else "▸ 📁") else if (node.isLink) "    🔗" else "    📄", Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, fontWeight = if (node.isDir || target != null) FontWeight.Medium else FontWeight.Normal)
            if (target != null) {
                val note = when {
                    !target.exists -> "new here"
                    target.isDir -> "exists: Accept merges into it"
                    else -> "exists: Accept replaces it, the old one goes to the trash"
                }
                Text("→ ${Format.home(target.path)} · $note", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("target"))
                Text("as copy → ${Format.home(target.copyPath)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { onChange(target) }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Change…") }
                    if (target.moved) TextButton(onClick = { onReset(target) }) { Text("Reset") }
                }
            }
        }
        if (node.isDir) for (s in FileStatus.entries) node.counts[s]?.let { StatusBadge(s, it, compact = true) }
        else node.status?.let { StatusBadge(it, null, compact = true) }
        Spacer(Modifier.width(8.dp))
        Text(if (node.isLink) "link" else Format.bytes(node.size), Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusBadge(status: FileStatus, count: Int?, compact: Boolean = false) {
    val color = when (status) {
        FileStatus.New -> Color(0xFF34C759)
        FileStatus.Changed -> Color(0xFFFF9500)
        FileStatus.Same -> Color.Gray
    }
    val label = status.name.lowercase() + (count?.let { " $it" } ?: "")
    Surface(color = color.copy(alpha = 0.18f), shape = MaterialTheme.shapes.small, modifier = Modifier.padding(horizontal = 2.dp)) {
        Text(label, color = color, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
    if (!compact) Spacer(Modifier.size(2.dp))
}
