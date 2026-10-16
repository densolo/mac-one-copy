package app.dc8.maconecopy.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.dc8.maconecopy.fs.ProtectedFolders
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

data class FileItem(
    val name: String, val rel: String, val path: Path, val isDir: Boolean, val isLink: Boolean, val size: Long, val mtime: Long,
    val isProtected: Boolean = false, val linkTarget: String? = null,
) {
    /** Symlinks (sent only as part of a folder) and macOS-protected folders cannot be selected on their own. */
    val sendable get() = !isLink && !isProtected
}

/**
 * Lists one folder: folders first, then files, case-insensitive. Symlinks and macOS-protected folders
 * (Desktop, Pictures, Library with iCloud Drive, …) are included but marked; their contents are never read,
 * because even listing them triggers a macOS privacy prompt.
 */
fun listFolder(home: Path, rel: String, showHidden: Boolean): List<FileItem> {
    val dir = if (rel.isEmpty()) home else home.resolve(rel)
    return runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())
        .filter { showHidden || !it.fileName.toString().startsWith(".") }
        .mapNotNull { p ->
            val a = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }.getOrNull() ?: return@mapNotNull null
            val name = p.fileName.toString()
            val itemRel = if (rel.isEmpty()) name else "$rel/$name"
            val target = if (a.isSymbolicLink) runCatching { Files.readSymbolicLink(p).toString() }.getOrNull() else null
            FileItem(name, itemRel, p, a.isDirectory, a.isSymbolicLink, a.size(), a.lastModifiedTime().toMillis(), ProtectedFolders.of(itemRel) != null, target)
        }
        .sortedWith(compareBy<FileItem>({ !it.isDir }, { it.name.lowercase() }))
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun FilesScreen(model: AppModel) {
    val home = model.settings.home
    var rel by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<FileItem>()) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val sizes by model.folderSizes.sizes.collectAsState()
    val peers by model.engine.peers.collectAsState()
    val windowInfo = LocalWindowInfo.current

    LaunchedEffect(home, rel, showHidden, refresh) {
        items = withContext(Dispatchers.IO) { listFolder(home, rel, showHidden) }
        items.filter { it.isDir && it.sendable }.forEach { model.folderSizes.request(it.path) }
        selected = selected.filter { s -> items.any { it.rel == s } }.toSet()
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("Files")
            Spacer(Modifier.weight(1f))
            Checkbox(showHidden, { showHidden = it }); Text("Hidden files")
            TextButton(onClick = { items.filter { it.isDir }.forEach { model.folderSizes.invalidate(it.path) }; refresh++ }) { Text("Refresh") }
            SendToButton(peers.map { it.id to (it.displayName + if (it.online) "" else " (offline)") }, enabled = selected.isNotEmpty()) { peerId ->
                val roots = selected.toList()
                model.run {
                    val r = model.engine.send(roots, peerId)
                    r.warnings.forEach { model.snackbar.showSnackbar(it) }
                    selected = emptySet()
                    model.screen = Screen.Transfers
                }
            }
        }
        // Breadcrumb: Home / a / b
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { rel = "" }) { Text("Home") }
            val parts = if (rel.isEmpty()) emptyList() else rel.split('/')
            parts.forEachIndexed { i, part ->
                Text("/")
                TextButton(onClick = { rel = parts.take(i + 1).joinToString("/") }) { Text(part) }
            }
            Spacer(Modifier.weight(1f))
            Muted(if (selected.isEmpty()) "${items.size} items" else "${selected.size} selected")
        }
        HorizontalDivider()
        LazyColumn {
            itemsIndexed(items, key = { _, it -> it.rel }) { index, item ->
                val isSelected = item.rel in selected
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .combinedClickable(
                            onDoubleClick = { if (item.isDir && item.sendable) { rel = item.rel; anchor = null } },
                            onClick = {
                                if (!item.sendable) return@combinedClickable
                                val mods = windowInfo.keyboardModifiers
                                selected = when {
                                    mods.isShiftPressed && anchor != null -> {
                                        val (a, b) = listOf(anchor!!, index).sorted()
                                        selected + items.subList(a, b + 1).filter { it.sendable }.map { it.rel }
                                    }
                                    mods.isMetaPressed -> if (isSelected) selected - item.rel else selected + item.rel
                                    else -> setOf(item.rel)
                                }
                                anchor = index
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val alpha = if (item.sendable) 1f else 0.4f
                    Text(if (item.isProtected) "🔒" else if (item.isLink) "🔗" else if (item.isDir) "📁" else "📄", Modifier.width(28.dp))
                    Text(item.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                    Muted(
                        when {
                            item.isProtected -> "macOS-protected, not synced"
                            item.isLink -> "symlink → ${item.linkTarget ?: "?"}"
                            else -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(item.mtime))
                        },
                        Modifier.width(if (item.isProtected) 220.dp else 140.dp),
                    )
                    Muted(
                        when {
                            !item.sendable -> ""
                            item.isDir -> sizes[item.path]?.let { Format.bytes(it) } ?: "…"
                            else -> Format.bytes(item.size)
                        },
                        Modifier.width(80.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SendToButton(peers: List<Pair<String, String>>, enabled: Boolean, onSend: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { open = true }, enabled = enabled && peers.isNotEmpty()) { Text("Send to ▸") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            for ((id, label) in peers) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSend(id) })
        }
    }
}
