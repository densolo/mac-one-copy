package app.dc8.maconecopy.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dc8.maconecopy.fs.ProtectedFolders
import kotlinx.coroutines.launch

/** A help section: paragraphs, and bullet lists written as lines starting with "• ". */
data class HelpSection(val title: String, val body: List<String>)

object Help {
    val sections = listOf(
        HelpSection(
            "How it works",
            listOf(
                "mac-one-copy copies files and folders between Macs that cannot reach each other directly. The only link between them is your OneDrive folder, which the OneDrive app keeps in sync on every Mac.",
                "The sending Mac cuts files into chunks and drops a few at a time into a relay folder inside OneDrive. OneDrive uploads them; the receiving Mac sees them appear, checks them, writes the files into place and deletes each chunk to say \"got it\". Only a small window of chunks (5 × 200 MB by default) is ever in OneDrive at once, so a transfer never needs much OneDrive space.",
                "Both Macs must have mac-one-copy running for data to move, but not at the same time: an offer waits until the other Mac shows up, and a transfer simply continues after sleep or a restart.",
            ),
        ),
        HelpSection(
            "Getting started",
            listOf(
                "• On each Mac, start mac-one-copy and pick the same relay folder inside OneDrive (for example OneDrive/mac-one-copy). Give each Mac a name you will recognise.",
                "• Machines shows this Mac at the top, then the other Macs using the same relay folder. A Mac counts as online if it was seen in the last 3 minutes.",
                "• Closing the window keeps mac-one-copy running in the Dock, so it can keep receiving. Click the Dock icon to bring the window back; Cmd+Q quits.",
            ),
        ),
        HelpSection(
            "Sending",
            listOf(
                "• Open Files, tick the files and folders to send (click their checkboxes; Shift-click a checkbox ticks a range; the checkbox in the header ticks everything in the folder; double-click a folder to open it) and choose Send to ▸ and a Mac.",
                "• Everything sent together comes from one folder, so it all lands in one folder on the other Mac. Opening another folder starts a new selection; send each folder's items as their own transfer.",
                "• Paths are relative to your home folder, so Documents/Report.xlsx lands in Documents/Report.xlsx on the other Mac even if the account names differ.",
                "• The job waits under Transfers → In progress as \"Waiting for answer\" until the other Mac accepts. It may already send its first chunk to save time.",
                "• Transfers lists everything in one place: incoming offers and running, paused or failed jobs under In progress, finished ones under Completed, with totals per Mac at the top. A job with several items shows the first one and \"+ N more\", which lists them all.",
            ),
        ),
        HelpSection(
            "Receiving",
            listOf(
                "When a Mac offers you files, the Dock icon bounces, its badge counts the waiting offers, and the offer dialog opens. It shows every file marked new, changed or same (by size and modification time) and warns if there is not enough free space.",
                "• Accept: files are written to the same place as on the sender. A file you already have with a different size or date is replaced; the old version is kept in the trash of mac-one-copy (Settings → trash, 14 days by default). Files that are already identical are skipped.",
                "• Accept as copy: nothing you have is touched. Each item arrives next to the original under a new name: Report (copy).xlsx, Projects (copy), then (copy 2), (copy 3), …",
                "• Decline: nothing is transferred and the sender is told.",
                "The dialog shows the first item (\"+ N more\" opens the full list), and under Goes to the folder everything lands in. Change… there moves everything into another folder of your choice (inside your home folder, not a protected one); items keep their names. Reset brings them back to the sender's folders. Show all files… opens every item with its own destination and its own Change… / Reset, and the folders' contents. The new / changed / same marks are always checked against where things will land.",
            ),
        ),
        HelpSection(
            "Pause, cancel and problems",
            listOf(
                "• Pause, Resume and Cancel work from either Mac. Until the other Mac has seen the request the job shows \"Pausing…\" or \"Cancelling…\".",
                "• Cancel keeps files that were already completed; half-received files are removed.",
                "• If this Mac runs low on disk space, the job pauses itself with \"disk full\". Free some space and press Resume.",
                "• If a file changes on the sender while it is being sent, or a piece fails its check, the job ends as Failed. Resume sends only what is missing: the changed files, or just the bad pieces.",
            ),
        ),
        HelpSection(
            "What can be transferred",
            listOf(
                "Supported:",
                "• Regular files of any type and size; big files are split into chunks and checked piece by piece with SHA-256.",
                "• Folders, including empty folders and everything inside them.",
                "• File modification dates.",
                "• Any file names your Mac allows, including spaces and non-Latin characters.",
                "Skipped or not kept:",
                "• Symbolic links inside a folder you send are recreated as links on the other Mac, if they point somewhere inside that folder (e.g. Current → A in an app bundle, or ../shared inside a project). Links pointing outside it are skipped and listed. A link can't be sent on its own; send what it points to instead. Finder aliases are copied as plain files and may no longer point anywhere.",
                "• Special files (sockets, pipes, devices) and .DS_Store files are skipped.",
                "• Permissions and owner, the executable flag, creation dates, Finder tags and colours, and other extended attributes and resource forks are not kept; files arrive with default permissions.",
                "• Apps and other packages (.app, .photoslibrary, …) are copied as ordinary folders, with their internal links. Because signatures, permissions and extended attributes are not kept, apps may not run afterwards.",
                "• Hard links arrive as separate copies.",
                "• Files outside your home folder, and the macOS-protected folders below.",
                "Anything left out or failed is listed with its job in Transfers (⚠ … not transferred), with the reason, on both Macs. Clicking it opens a window with the whole list, filters for failed and skipped items, and Copy list. Items that arrive fine after Resume drop off the list.",
            ),
        ),
        HelpSection(
            "Protected folders",
            listOf(
                "macOS asks for special permission before an app may read some folders in your home. mac-one-copy stays out of them entirely, so it never triggers those prompts: it does not send from them, does not write into them (such offers are declined automatically), and the file browser shows them with 🔒 without opening them.",
                "• " + ProtectedFolders.NAMES.joinToString(", ") { if (it == ".Trash") "Trash" else it },
                "• Library includes iCloud Drive (Library/Mobile Documents) and the OneDrive folder itself.",
                "Documents is supported. If \"Desktop & Documents Folders\" is turned on in iCloud settings, files in Documents may be stored only in iCloud; macOS then downloads them when mac-one-copy reads them, which can be slow.",
            ),
        ),
        HelpSection(
            "OneDrive notes",
            listOf(
                "• OneDrive must be signed in and syncing on every Mac. The relay folder does not need \"Always keep on this device\".",
                "• Each chunk is deleted as soon as it has arrived, and OneDrive keeps deleted files in its recycle bin, which may count against your storage until it is emptied. A large transfer can temporarily take about its own size there.",
                "• Because chunks are deleted all the time, OneDrive may warn you that files were deleted and ask you to confirm. Confirm that the deletions are intended, and tick the option not to show this message again so the warning does not interrupt every transfer. Until you confirm, OneDrive may hold the deletions back, and then senders wait because the shared window looks full.",
                "• OneDrive sometimes leaves conflict copies (e.g. 000042-MacBook.chunk). mac-one-copy ignores them; you may delete them.",
                "• Don't change files in the relay folder by hand while transfers run.",
                "If a transfer seems stuck:",
                "• Click the OneDrive icon in the menu bar. If it shows files uploading or downloading, or a queue of pending changes, OneDrive is still busy moving chunks, so the transfer is in progress and only needs time. Large transfers are limited by OneDrive's upload and download speed.",
                "• Also check there that OneDrive is signed in, not paused, and not waiting for you to confirm deletions or resolve an error.",
                "• Make sure mac-one-copy is running on both Macs; the Machines screen shows when the other Mac was last seen.",
            ),
        ),
        HelpSection(
            "Several copies on one Mac",
            listOf(
                "For testing, a second copy can run on the same Mac with its own data folder, name and home folder, e.g. --data-dir \"~/Library/Application Support/mac-one-copy-2\" --name \"{host} 2\" --home ~/mac-one-copy-2. It uses the same relay folder and shows up as another machine. The IntelliJ run configurations app (2) and app (3) do exactly this.",
            ),
        ),
    )
}

@Composable
fun HelpScreen() {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Row {
        Column(Modifier.width(190.dp).padding(end = 16.dp)) {
            Title("Help")
            Help.sections.forEachIndexed { i, s ->
                Text(
                    s.title,
                    Modifier.fillMaxWidth().clickable { scope.launch { list.animateScrollToItem(i) } }.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(Help.sections) { _, s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        for (p in s.body) {
                            if (p.startsWith("• ")) Row {
                                Text("•", Modifier.width(16.dp))
                                Text(p.removePrefix("• "), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Spacer(Modifier.size(2.dp))
                                Text(p, style = MaterialTheme.typography.bodyMedium, fontWeight = if (p.endsWith(":")) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}
