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
import androidx.compose.ui.text.style.TextOverflow
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
    var showAll by remember { mutableStateOf(false) }
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(640.dp, 520.dp)),
        title = "Incoming from ${details.job.peerName}",
        icon = MacIntegration.iconPainter,
    ) {
        AppTheme { Surface(Modifier.fillMaxSize()) { AcceptContent(details, onDecide, folders, onFolders, error, onShowAll = { showAll = true }) } }
    }
    if (showAll) DialogWindow(
        onCloseRequest = { showAll = false },
        state = rememberDialogState(size = DpSize(760.dp, 640.dp)),
        title = "All items from ${details.job.peerName}",
        icon = MacIntegration.iconPainter,
    ) {
        AppTheme { Surface(Modifier.fillMaxSize()) { AllItemsContent(details, folders, onFolders, error, onClose = { showAll = false }) } }
    }
}

/** An absolute folder as a path relative to home ("" for home itself), or null if it is not inside home. */
fun relativeToHome(folder: String, home: String): String? = when {
    folder == home -> ""
    folder.startsWith("$home/") -> folder.removePrefix("$home/")
    else -> null
}

/** Opens the folder picker and turns the choice into new folders, or an error if it is outside home. */
private class FolderPicker(val details: OfferDetails, val pickFolder: (String) -> String?, val onFolders: (Map<String, String>) -> Unit) {
    var error by mutableStateOf<String?>(null)
    fun pick(start: String, apply: (String) -> Map<String, String>) {
        val chosen = pickFolder(start) ?: return
        val rel = relativeToHome(chosen, details.home)
        if (rel == null) error = "Pick a folder inside your home folder (${Format.home(details.home)})."
        else { error = null; onFolders(apply(rel)) }
    }
}

private val defaultPicker: (String) -> String? = { chooseFolder("Choose where to put it", it) }

/**
 * The dialog body, separate from the window so it can be tested: a summary with the first item and
 * "+ N more", where everything goes, and the answer buttons. The full list lives in [AllItemsContent].
 * [folders] are the folders chosen so far (root → folder relative to home); [onFolders] asks for a
 * preview with new ones. [pickFolder] opens a folder picker and returns the chosen absolute path.
 */
@Composable
fun AcceptContent(
    details: OfferDetails,
    onDecide: (Decision) -> Unit,
    folders: Map<String, String> = emptyMap(),
    onFolders: (Map<String, String>) -> Unit = {},
    error: String? = null,
    pickFolder: (start: String) -> String? = defaultPicker,
    onShowAll: () -> Unit = {},
) {
    val tree = remember(details) { OfferNode.tree(details) }
    val picker = remember(details, onFolders) { FolderPicker(details, pickFolder, onFolders) }
    val tooBig = details.totalBytes > details.freeBytesHere
    val targets = details.targets
    Column(Modifier.padding(16.dp)) {
        Text("${details.job.peerName} wants to send you", style = MaterialTheme.typography.titleMedium)
        Text("${Format.count(details.files.size, "file")} · ${Format.bytes(details.totalBytes)}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${Format.bytes(details.freeBytesHere)} free here" + if (tooBig) " — not enough space!" else "",
            color = if (tooBig) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("free"),
        )
        val counts = tree.fold(mapOf<FileStatus, Int>()) { acc, n -> FileStatus.entries.associateWith { (acc[it] ?: 0) + (n.counts[it] ?: 0) } }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            for (s in FileStatus.entries) StatusBadge(s, counts[s] ?: 0)
        }

        // The first item, then "+ N more" for the rest.
        Section("Items")
        tree.firstOrNull()?.let { first ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (first.isDir) "📁" else if (first.isLink) "🔗" else "📄", Modifier.width(28.dp))
                Text(first.name.substringAfterLast('/'), Modifier.weight(1f, fill = false), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                if (first.isDir) for (s in FileStatus.entries) first.counts[s]?.let { StatusBadge(s, it, compact = true) }
                else first.status?.let { StatusBadge(it, null, compact = true) }
                Spacer(Modifier.width(8.dp))
                Muted(Format.bytes(first.size))
                if (tree.size > 1) MoreLink(tree.size - 1, onShowAll)
            }
        }
        TextButton(onClick = onShowAll, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Show all files…") }

        // Where it all goes: one folder in the normal case, with Change… and Reset for everything.
        Section("Goes to")
        val folder = targets.map { it.folder }.distinct().singleOrNull()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (folder != null) Format.home(if (folder.isEmpty()) details.home else "${details.home}/$folder") else "several folders, see all files",
                Modifier.weight(1f, fill = false).testTag("goes-to"), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val start = folder?.let { if (it.isEmpty()) details.home else "${details.home}/$it" } ?: details.home
                picker.pick(start) { rel -> targets.associate { it.root to rel } }
            }) { Text("Change…") }
            if (folders.isNotEmpty()) TextButton(onClick = { onFolders(emptyMap()) }) { Text("Reset") }
        }
        val existing = targets.count { it.exists }
        Muted(
            when {
                existing == 0 -> if (targets.size == 1) "New here." else "All new here."
                targets.size == 1 && targets[0].isDir -> "Already exists here: Accept merges into it."
                targets.size == 1 -> "Already exists here: Accept replaces it, the old one goes to the trash."
                else -> "$existing of ${targets.size} already exist here: Accept merges into or replaces them."
            } + " As copy: " + if (targets.size == 1) Format.home(targets[0].copyPath).substringAfterLast('/') else "taken names get (copy), (copy 2), …",
        )
        Spacer(Modifier.weight(1f))
        if (details.skipped.isNotEmpty()) IssueList(details.skipped, "${Format.count(details.skipped.size, "item")} left out by the sender", subject = "offer from ${details.job.peerName}")
        (picker.error ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("error")) }
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

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))

/**
 * Every item of the offer as an expandable tree, each with its destination (Accept and as copy) and its
 * own Change… / Reset, plus "Put everything in…".
 */
@Composable
fun AllItemsContent(
    details: OfferDetails,
    folders: Map<String, String> = emptyMap(),
    onFolders: (Map<String, String>) -> Unit = {},
    error: String? = null,
    pickFolder: (start: String) -> String? = defaultPicker,
    onClose: () -> Unit = {},
) {
    val tree = remember(details) { OfferNode.tree(details) }
    val targets = remember(details) { details.targets.associateBy { it.root } }
    val picker = remember(details, onFolders) { FolderPicker(details, pickFolder, onFolders) }
    // Folders start collapsed when there are many items, so the list of items is what you see first.
    val expanded = remember(details.job.id) { mutableStateOf(if (tree.size <= 3) tree.map { it.path }.toSet() else emptySet()) }
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${Format.count(tree.size, "item")} · ${Format.count(details.files.size, "file")} · ${Format.bytes(details.totalBytes)}",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (details.targets.size > 1) TextButton(onClick = {
                picker.pick(details.home) { rel -> details.targets.associate { it.root to rel } }
            }) { Text("Put everything in…") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            val rows = flatten(tree, expanded.value)
            items(rows, key = { it.second.path }) { (depth, node) ->
                val target = if (depth == 0) targets[node.path] else null
                TreeRow(
                    depth, node, node.path in expanded.value, target,
                    onChange = { t -> picker.pick(java.io.File(t.path).parent ?: details.home) { rel -> folders + (t.root to rel) } },
                    onReset = { t -> onFolders(folders - t.root) },
                ) {
                    expanded.value = if (node.path in expanded.value) expanded.value - node.path else expanded.value + node.path
                }
            }
        }
        (picker.error ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp).testTag("error")) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = onClose) { Text("Done") }
        }
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
