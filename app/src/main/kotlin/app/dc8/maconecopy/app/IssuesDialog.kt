package app.dc8.maconecopy.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import app.dc8.maconecopy.IssueKind
import app.dc8.maconecopy.JobIssue

/**
 * "⚠ 3 not transferred": a small link that opens the full list in its own window, which copes with
 * any number of entries. [subject] names the job in the window title. [onOpen] replaces the window
 * (used by tests).
 */
@Composable
fun IssueList(issues: List<JobIssue>, title: String = "${issues.size} not transferred", subject: String = "", onOpen: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Text(
        "⚠ $title ▸",
        Modifier.clickable { if (onOpen != null) onOpen() else open = true }.padding(vertical = 6.dp).testTag("issues"),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.labelLarge,
    )
    if (open) DialogWindow(
        onCloseRequest = { open = false },
        state = rememberDialogState(size = DpSize(720.dp, 560.dp)),
        title = if (subject.isEmpty()) title.replaceFirstChar { it.uppercase() } else "${title.replaceFirstChar { it.uppercase() }} — $subject",
        icon = MacIntegration.iconPainter,
    ) {
        AppTheme { Surface(Modifier.fillMaxSize()) { IssuesContent(issues, onClose = { open = false }) } }
    }
}

/** Compares like Finder: case-insensitive, with runs of digits by value, so out-4 comes before out-12. */
val NATURAL_ORDER: Comparator<String> = Comparator { a, b ->
    val pa = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
    val pb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(pa.size, pb.size)) {
        val x = pa[i]; val y = pb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
        else x.compareTo(y)
        if (c != 0) return@Comparator c
    }
    pa.size.compareTo(pb.size)
}

/** Failures first (they can be fixed with Resume), then skipped items; each by path. */
private val FAILED_FIRST = compareBy<JobIssue> { it.kind != IssueKind.Failed }.thenBy(NATURAL_ORDER) { it.path }

/** The list as plain text, one entry per line, for the clipboard. */
fun issuesText(issues: List<JobIssue>): String =
    issues.sortedWith(FAILED_FIRST).joinToString("\n") { "${it.kind.name.lowercase()}\t${it.path}\t${it.message}" }

/** Every entry with its path and reason, filterable by kind, with Copy list. */
@Composable
fun IssuesContent(issues: List<JobIssue>, onClose: () -> Unit = {}) {
    var filter by remember { mutableStateOf<IssueKind?>(null) }
    val clipboard = LocalClipboardManager.current
    val failed = issues.count { it.kind == IssueKind.Failed }
    val skipped = issues.size - failed
    val shown = issues.filter { filter == null || it.kind == filter }.sortedWith(FAILED_FIRST)
    Column(Modifier.padding(16.dp)) {
        Text(Format.count(issues.size, "item") + " not transferred", style = MaterialTheme.typography.titleMedium)
        Muted("Failed items can be sent again with Resume; skipped ones were left out on purpose.")
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, onClick = { filter = null }, label = { Text("All ${issues.size}") })
            FilterChip(filter == IssueKind.Failed, onClick = { filter = IssueKind.Failed }, label = { Text("Failed $failed") }, enabled = failed > 0)
            FilterChip(filter == IssueKind.Skipped, onClick = { filter = IssueKind.Skipped }, label = { Text("Skipped $skipped") }, enabled = skipped > 0)
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(shown, key = { "${it.kind}:${it.path}" }) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Text(
                        if (i.kind == IssueKind.Failed) "failed" else "skipped", Modifier.width(64.dp).padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i.kind == IssueKind.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        Text(i.path, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Muted(i.message)
                    }
                }
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(issuesText(shown))) }) { Text("Copy list") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onClose) { Text("Done") }
        }
    }
}
