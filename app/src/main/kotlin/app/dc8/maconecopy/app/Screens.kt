package app.dc8.maconecopy.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import app.dc8.maconecopy.JobIssue
import app.dc8.maconecopy.IssueKind
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.nio.file.Files
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dc8.maconecopy.APP_VERSION
import app.dc8.maconecopy.Direction
import app.dc8.maconecopy.HistoryEntry
import app.dc8.maconecopy.JobState
import app.dc8.maconecopy.JobView
import app.dc8.maconecopy.Peer

@Composable
fun App(model: AppModel) {
    if (model.needsSetup) return SetupScreen(model)
    val jobs by model.engine.jobs.collectAsState()
    val offers = jobs.count { it.incomingOffer }
    Scaffold(snackbarHost = { SnackbarHost(model.snackbar) }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            NavigationRail {
                Spacer(Modifier.size(8.dp))
                for (s in Screen.entries) {
                    NavigationRailItem(
                        selected = model.screen == s,
                        onClick = { model.screen = s },
                        icon = {
                            BadgedBox(badge = { if (s == Screen.Transfers && offers > 0) Badge { Text("$offers") } }) {
                                Text(screenIcon(s), style = MaterialTheme.typography.titleLarge)
                            }
                        },
                        label = { Text(s.label) },
                    )
                }
            }
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                when (model.screen) {
                    Screen.Machines -> MachinesScreen(model)
                    Screen.Files -> FilesScreen(model)
                    Screen.Transfers -> TransfersScreen(model, jobs)
                    Screen.Settings -> SettingsScreen(model)
                    Screen.Help -> HelpScreen()
                }
            }
        }
    }
    model.openOffer?.let { details ->
        AcceptDialog(
            details, model.offerFolders, model.offerError,
            onDecide = { model.decide(details.job.id, it) },
            onFolders = { model.retarget(details.job.id, it) },
            onClose = { model.closeOffer(details.job.id) },
        )
    }
}

private fun screenIcon(s: Screen) = when (s) {
    Screen.Machines -> "🖥"
    Screen.Files -> "📁"
    Screen.Transfers -> "⇅"
    Screen.Settings -> "⚙"
    Screen.Help -> "?"
}

@Composable
fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = modifier)

// ---- Machines ------------------------------------------------------------------------------

@Composable
fun MachinesScreen(model: AppModel) {
    val peers by model.engine.peers.collectAsState()
    val s = model.settings
    // Free space of this Mac's home volume, refreshed with every peer scan.
    val free = remember(peers) { runCatching { Files.getFileStore(s.home).usableSpace }.getOrDefault(0L) }
    val me = Peer(model.engine.machineId, s.displayName ?: "", System.getProperty("user.name"), free, APP_VERSION, System.currentTimeMillis(), true)
    Column {
        Title("Machines")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "self") { PeerRow(me, self = true, details = "home ${s.home} · relay ${s.relayDir ?: "not set"}") }
            items(peers, key = { it.id }) { PeerRow(it) }
            if (peers.isEmpty()) item(key = "empty") {
                Muted("No other machines on the relay yet. Start mac-one-copy on another Mac with the same OneDrive folder.", Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun PeerRow(p: Peer, self: Boolean = false, details: String? = null) {
    val colors = if (self) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (p.online) Color(0xFF34C759) else Color.Gray, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.displayName, fontWeight = FontWeight.Medium)
                    if (self) {
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("This Mac", Modifier.padding(horizontal = 4.dp)) }
                    }
                }
                Muted("${p.user} · app ${p.appVersion}" + (details?.let { " · $it" } ?: ""))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (self) "Running" else if (p.online) "Online" else "Last seen ${Format.ago(p.lastSeen)}")
                Muted("${Format.bytes(p.freeBytes)} free")
            }
        }
    }
}

// ---- Transfers: running and waiting jobs, then finished ones -------------------------------

@Composable
fun TransfersScreen(model: AppModel, jobs: List<JobView>) {
    val history by model.engine.history.collectAsState()
    // A failed job is still resumable, so it stays under "In progress" rather than also showing as finished.
    val active = jobs.sortedWith(compareByDescending<JobView> { it.incomingOffer }.thenBy { it.createdAt })
    val activeIds = active.map { it.id }.toSet()
    val finished = history.filter { it.jobId !in activeIds }.sortedByDescending { it.endedAt }
    Column {
        Title("Transfers")
        if (history.isNotEmpty()) PeerTotals(history)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "active-header") { SectionHeader("In progress", active.size) }
            if (active.isEmpty()) item(key = "active-empty") { Muted("Nothing running or waiting. Pick files in Files and choose Send to.") }
            items(active, key = { it.id }) { JobRow(model, it) }
            item(key = "done-header") { SectionHeader("Completed", finished.size, Modifier.padding(top = 12.dp)) }
            if (finished.isEmpty()) item(key = "done-empty") { Muted("Nothing finished yet.") }
            items(finished, key = { "done-${it.jobId}" }) { Column { HistoryRow(it); HorizontalDivider() } }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.width(8.dp))
    Muted("$count")
}

/** Jobs and bytes per peer, over everything finished. */
@Composable
private fun PeerTotals(history: List<HistoryEntry>) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((peer, list) in history.groupBy { it.peerName }) {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text(peer, fontWeight = FontWeight.Medium)
                    val sent = list.filter { it.direction == Direction.Sent }.sumOf { it.bytesTransferred }
                    val received = list.filter { it.direction == Direction.Received }.sumOf { it.bytesTransferred }
                    Muted("${Format.count(list.size, "job")} · sent ${Format.bytes(sent)} · received ${Format.bytes(received)}")
                }
            }
        }
    }
}

fun stateLabel(j: JobView): String = when {
    j.pending != null -> "${j.pending!!.name}…"
    j.incomingOffer -> "Incoming offer"
    j.state == JobState.Offered -> "Waiting for answer"
    j.state == JobState.Paused && j.pauseReason != null -> "Paused (${j.pauseReason})"
    else -> j.state.name
}

@Composable
private fun JobRow(model: AppModel, j: JobView) {
    val colors = if (j.incomingOffer) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (j.direction == Direction.Sent) "→" else "←", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    RootsLabel(j.roots, FontWeight.Medium)
                    Muted("${if (j.direction == Direction.Sent) "to" else "from"} ${j.peerName} · ${Format.count(j.totalFiles, "file")} · ${Format.bytes(j.totalBytes)}")
                }
                Text(stateLabel(j), color = if (j.state == JobState.Failed) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
            if (j.state != JobState.Offered) {
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(progress = { j.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(4.dp))
                Row {
                    Muted("${Format.bytes(j.doneBytes)} of ${Format.bytes(j.totalBytes)} · ${j.doneFiles}/${Format.count(j.totalFiles, "file")} · ${Format.rate(j.bytesPerSecond)}" +
                        (j.etaSeconds?.let { " · ${Format.duration(it)} left" } ?: ""), Modifier.weight(1f))
                    j.currentFile?.let { Muted(it, Modifier.width(320.dp)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (j.issues.isNotEmpty()) Box(Modifier.weight(1f)) { IssueList(j.issues, subject = j.roots.firstOrNull() ?: "") }
                if (j.incomingOffer) OutlinedButton(onClick = { model.review(j.id) }) { Text("Review…") }
                if (j.state == JobState.Transferring) TextButton(onClick = { model.run { model.engine.pause(j.id) } }) { Text("Pause") }
                if (j.state == JobState.Paused || j.state == JobState.Failed) TextButton(onClick = { model.run { model.engine.resume(j.id) } }) { Text("Resume") }
                if (!j.state.isFinal) TextButton(onClick = { model.run { model.engine.cancel(j.id) } }) { Text(if (j.incomingOffer) "Decline" else "Cancel") }
            }
        }
    }
}

@Composable
private fun HistoryRow(h: HistoryEntry) = Column(Modifier.padding(vertical = 8.dp)) {
    HistoryLine(h)
    if (h.issues.isNotEmpty()) Box(Modifier.padding(start = 24.dp)) { IssueList(h.issues, subject = h.roots.firstOrNull() ?: "") }
}

@Composable
private fun HistoryLine(h: HistoryEntry) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (h.direction == Direction.Sent) "→" else "←", Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            RootsLabel(h.roots)
            Muted("${h.peerName} · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(h.startedAt))} · " +
                "${h.decision?.name ?: "–"} · ${Format.count(h.filesTransferred, "file")}, ${h.filesSkipped} skipped, ${h.filesFailed} failed")
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(h.outcome.name, color = if (h.outcome == JobState.Completed) Color.Unspecified else MaterialTheme.colorScheme.error)
            Muted("${Format.bytes(h.bytesTransferred)} in ${Format.duration(h.durationMs / 1000)} · ${Format.rate(h.avgBytesPerSecond)}")
        }
    }
}

/** The first item of a job, then "+ N more", which opens a list of all items. */
@Composable
fun RootsLabel(roots: List<String>, weight: FontWeight = FontWeight.Normal) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(roots.firstOrNull() ?: "", Modifier.weight(1f, fill = false), fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (roots.size > 1) Box {
            MoreLink(roots.size - 1) { open = true }
            DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                Muted(Format.count(roots.size, "item"), Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                for (r in roots) Text(r, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "+ N more" as a small link, so it doesn't make its row taller like a button would. */
@Composable
fun MoreLink(n: Int, onClick: () -> Unit) = Text(
    "+ $n more",
    Modifier.padding(start = 6.dp).clickable(onClick = onClick).padding(horizontal = 2.dp).testTag("more"),
    color = MaterialTheme.colorScheme.primary,
    style = MaterialTheme.typography.labelLarge,
)

