package app.dc8.maconecopy.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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

@Composable
fun AcceptDialog(details: OfferDetails, onDecide: (Decision) -> Unit, onClose: () -> Unit) {
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(size = DpSize(640.dp, 560.dp)),
        title = "Incoming from ${details.job.peerName}",
        icon = MacIntegration.iconPainter,
    ) {
        AppTheme { Surface(Modifier.fillMaxSize()) { AcceptContent(details, onDecide) } }
    }
}

/** The dialog body, separate from the window so it can be tested. */
@Composable
fun AcceptContent(details: OfferDetails, onDecide: (Decision) -> Unit) {
    val tree = remember(details) { OfferNode.tree(details) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            for (s in FileStatus.entries) StatusBadge(s, counts[s] ?: 0)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            val rows = flatten(tree, expanded.value)
            items(rows, key = { it.second.path }) { (depth, node) ->
                TreeRow(depth, node, node.path in expanded.value) {
                    expanded.value = if (node.path in expanded.value) expanded.value - node.path else expanded.value + node.path
                }
            }
        }
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

@Composable
private fun TreeRow(depth: Int, node: OfferNode, open: Boolean, toggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = node.isDir, onClick = toggle).padding(start = (depth * 18).dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (node.isDir) (if (open) "▾ 📁" else "▸ 📁") else "    📄", Modifier.width(56.dp))
        Text(node.name, Modifier.weight(1f), fontWeight = if (node.isDir) FontWeight.Medium else FontWeight.Normal)
        if (node.isDir) for (s in FileStatus.entries) node.counts[s]?.let { StatusBadge(s, it, compact = true) }
        else node.status?.let { StatusBadge(it, null, compact = true) }
        Spacer(Modifier.width(8.dp))
        Text(Format.bytes(node.size), Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall)
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
