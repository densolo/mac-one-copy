Build `mac-one-copy`: a Kotlin desktop app (Compose Multiplatform for Desktop) that transfers files and folders between my Macs that can't connect to each other. The only shared channel is my personal OneDrive folder, synced on every Mac by the official OneDrive client. It is slow and has limited quota, so it's used as a relay with a small, shared buffer of chunk files. No Graph API, only plain file I/O on the synced folder.

Work in two phases. Before writing code for each phase, give me a short plan (modules, key classes, file formats, state machine) and wait for my OK.
- Phase 1: `engine` module + headless `cli` module + tests against a simulated relay.
- Phase 2: `app` module (Compose Desktop UI) on top of the engine.

## Tech
- Kotlin, JVM 21, Maven (with wrapper), groupId `app.dc8.maconecopy`, multi-module: `engine`, `cli`, `app`.
- `engine`: pure Kotlin and coroutines, no UI deps. Exposes `StateFlow<List<Peer>>`, `StateFlow<List<JobView>>`, `StateFlow<List<HistoryEntry>>` and commands: `send(roots, peerId)`, `decide(jobId, Accept|AcceptCopy|Decline)`, `pause(jobId)`, `resume(jobId)`, `cancel(jobId)`.
- kotlinx.serialization (JSON) for relay and state files; Clikt for the CLI; JUnit 5.
- Local app data in `~/Library/Application Support/mac-one-copy/`: machine id, settings, job state, history, trash.

## Concepts
- Machine: a stable UUID generated on first run, plus a display name (default: hostname, editable).
- All transfer paths are relative to the user's home directory, because account names differ between Macs. Reject absolute paths, `..`, and anything that resolves outside home (including through symlinks). Symlinks inside a sent folder whose target stays inside that folder are recreated as relative links (absolute targets inside it are made relative); other symlinks are skipped and recorded. The receiver creates links only after all files of the job are in place and re-checks each target against the actual destination.
- A machine never lists itself as a target (the Machines screen shows it at the top as "This Mac", but it is never offered in "Send to").
- macOS-protected home folders (Desktop, Downloads, Movies, Music, Pictures, Library incl. iCloud Drive, .Trash) are never read or written: sending from them is refused, offers into them are declined automatically, the file browser marks them with a lock and never opens or sizes them. Documents is allowed.
- Provider: OneDrive only (`~/Library/CloudStorage/OneDrive-*` is offered as the default in the relay picker).

## Dev instances
Display name and home folder are ordinary settings (home defaults to `~`; all relative paths, the file browser and every received transfer resolve against it). A second copy on the same Mac only needs its own app data:
- `--data-dir <dir>` (app and CLI, or env `MAC_ONE_COPY_DATA_DIR`) selects the app data folder, so it gets its own machine id, settings, jobs, history and trash. Default: `~/Library/Application Support/mac-one-copy/`.
- Then either change display name and home folder in that copy's Settings, or pass `--name <name>` and `--home <dir>` (env `MAC_ONE_COPY_NAME`, `MAC_ONE_COPY_HOME`); flags override and are saved to settings. Home is created if missing.
- A lock file in the data dir prevents two processes from using the same app data.
- The repo ships IntelliJ run configurations in `.run/`: `cli run` (default) plus `cli run (2)` and `cli run (3)` with `--data-dir "~/Library/Application Support/mac-one-copy-N" --name "{host} N" --home ~/mac-one-copy-N` (`{host}` expands to the host name), and the same three for the app: `app`, `app (2)`, `app (3)`.
- Both copies use the same relay folder, so each shows up as a separate machine to the other.

## Relay layout (inside the chosen OneDrive folder)
```
relay/
  peers/<machine-id>.json        # displayName, user, freeBytes (home volume), appVersion, lastSeen; rewritten every 30–60 s
  jobs/<job-id>/
    manifest.json                # from, to, createdAt, roots, files: [relPath, size, mtime], emptyDirs, links: [relPath, target], skipped: [path, reason]
    decision.json                # written by receiver: accept | accept-copy | decline, optional skip list
    control-<machine-id>.json    # pause / resume / cancel requests (+ reason) and acks from that side; receiver status and failures
    resend-NNNN.json             # written by the sender on resume after failure: files resent in full (new size/mtime), single segments, files given up on
    NNNNNN.chunk
```
- A peer counts as online if seen within ~3 minutes; otherwise show "last seen X ago".
- All writes into the relay are atomic: write `.tmp`, fsync, rename. Readers accept only exact expected names (`^\d{6}\.chunk$`, `<uuid>.json`, `manifest.json`, `decision.json`, `control-<uuid>.json`); everything else (`.tmp`, OneDrive conflict copies like `000042-MacBook.chunk`, unparseable files) is ignored and logged once.
- Reads of relay files can block while OneDrive downloads a placeholder: run them on the IO dispatcher with limited concurrency and a timeout; a timeout means "not ready, retry next poll".
- Caveat (README): deleted chunks go to the OneDrive recycle bin, which may count against quota until emptied.

## Chunks
- Binary: header (magic, version, jobId, seq, resend generation, recordCount), then records, then a SHA-256 trailer over everything before it.
- Record types:
    - DATA: fileIndex, offset, length, bytes
    - FILE_END: fileIndex, list of segment sha256s, file hash = sha256 over that list (computed by the sender while streaming; no up-front hashing)
- Segment hashes: a file is hashed in fixed segments aligned to the layout (one segment per DATA record, at most one chunk size). The sender keeps finished segment hashes in its job state and the receiver hashes each DATA record as it applies it, also keeping the result in job state. So neither side ever re-reads a file to rebuild a hash: not after a restart or sleep, and not at the end (the receiver compares its segment list with FILE_END). Optional setting "verify from disk" re-reads each finished file once before the final rename.
- A mismatch pinpoints the bad segments: the file stays as `.part`, the job is marked Failed, and resume resends only those byte ranges (a new layout generation containing just those segments), not the whole file.
- Deterministic layout from manifest + skip list: files sorted by path and packed in sequence. Small files share chunks; big files span several. Each layout generation has a start seq; pre-sent chunks and older generations still apply harmlessly because records carry fileIndex + offset. The sender can resume from its last acked seq.
- Source checks: before writing each chunk the sender re-stats every file it touches; a size or mtime change against the manifest fails that file immediately (not after streaming all of it), and resume resends it in full with a new manifest entry.
- Receiver applies each chunk in one pass: stream it once, write DATA bytes to the `.part` while hashing records and the trailer, and only mark the ranges as received once the trailer verifies. A failed trailer leaves garbage at unmarked ranges, which is overwritten later. This avoids reading every chunk twice from OneDrive.
- Disk full on the receiver (ENOSPC, or free space below the remaining bytes plus a margin, re-checked before each chunk) pauses the job with reason "disk full" instead of failing it; resume retries.
- On OneDrive, a chunk may appear at full size before its data has downloaded (placeholder), and files sync in any order. Only the trailer hash proves a chunk is complete. If it fails, leave the chunk alone and retry next poll.
- The receiver writes records into `<dest>.part` at their offsets (order-independent). On FILE_END plus all bytes present, it compares segment hashes (and optionally re-reads from disk), renames to the final name, and sets the sender's mtime. Then it deletes the chunk; deletion is the ack.
- Use polling, not WatchService (unreliable on macOS File Provider folders). Default poll every 5 s.

## Shared chunk window
- One relay-wide limit (default 5 × 200 MB, configurable): the total number of `.chunk` files across ALL jobs and ALL senders in the relay folder. Each sender counts every chunk before writing a new one, and also keeps to a fair share of ⌈window / active senders⌉, so senders that cannot yet see each other's newest chunks overshoot by at most one chunk each. A slight overshoot from sync lag is acceptable.
- Several jobs run at once and share that window round-robin among accepted, non-paused jobs.
- Offered (not yet accepted) jobs may pre-send at most 1 chunk each, and only when the window has room, so waiting offers never starve running jobs.

## Job lifecycle
States: Offered → Transferring ⇄ Paused → Completed | Cancelled | Failed; Offered → Declined.
- Send: the sender writes the manifest, then pre-sends as allowed above. The receiver applies nothing before a decision.
- Accept: plain accept. Before replacing an existing file, move it to `<appdata>/trash/<job-id>/<relPath>` (configurable retention, default 14 days). With plain accept, the receiver may put files whose size and mtime already match into the skip list; the sender then excludes them from the layout. Pre-sent chunks still apply harmlessly.
- Accept as copy: nothing local is touched. Each root is placed next to the original under a new name:
    - file root: `report.xlsx` → `report (copy).xlsx`
    - directory root: `Projects/` → `Projects (copy)/` with the whole tree inside
    - if taken: `(copy 2)`, `(copy 3)`, …
    - no skip list in copy mode
- Decline: the sender deletes its chunks and the job folder.
- Pause / resume / cancel can be triggered from either side via control files and take effect after sync. The UI shows "Pausing…" / "Cancelling…" until the other side confirms.
    - Pause: the sender stops writing, the receiver stops consuming.
    - Cancel: the sender deletes its chunks and the job folder; the receiver deletes its `.part` files (completed files and trashed originals stay).
- After an app restart or sleep, rebuild every job's state from local state plus the relay and continue. Paused jobs stay paused.
- Failed (e.g. segment hash mismatch, source file changed during send): mark the job resumable; on resume, resend only the bad segments (hash mismatch) or the affected files (source changed).
- Paused can also be entered automatically with a reason (e.g. "disk full"); the UI shows the reason.

## Issues per job
Every job keeps a list of what did not make it, with the reason: skipped by the sender (symlink outside the folder, special or unreadable file; sent along in the manifest so the receiver shows it too) or failed (changed while sending, damaged pieces, could not be written, given up, link not created). Items that arrive fine after Resume drop off. The list is part of the job view and the history entry, shown in Transfers per job and by the CLI's `issues JOB`.

## History and stats
Persist every finished job (Completed, Cancelled, Failed, Declined) locally on both sides:
- job id, direction (sent/received), peer name, root names, decision mode
- files transferred / skipped / failed, bytes transferred
- start, end, duration, average rate, outcome

Keep it in a small append-only JSON-lines file and expose it as a flow.

## CLI (Phase 1, for testing and headless use)
`mac-one-copy-cli run --relay <dir>` (engine loop with auto-decision flags for tests), `send`, `jobs`, `decide`, `pause`, `resume`, `cancel`, `history`, `peers`.

## Tests (Phase 1)
- Unit: chunk codec, deterministic layout with and without a skip list, offset writes, copy-name generation, path safety checks.
- Integration, with two or three engine instances using separate fake homes and one fake relay directory that simulates OneDrive:
    - random sync delays
    - files appearing truncated or placeholder-like first and completed later
    - out-of-order delivery
    - conflict-copy files
    - a process killed and restarted mid-transfer
- Scenarios:
    - accept, accept-copy (file root and dir root, including an existing "(copy)"), decline
    - pause/resume and cancel from either side
    - three concurrent jobs, two in each direction, never exceeding the shared window
    - resend with a skip list
    - large file spanning many chunks: restart mid-file on both sides without re-reading, a corrupted segment resent alone, source modified mid-send failing fast, disk-full pause and resume
- Assert the target trees are byte-identical, with mtimes preserved and originals in trash where expected.

## UI (Phase 2)
- First run: pick the relay folder inside OneDrive and set a display name (home folder defaults to `~`).
- No tray icon. Closing the window hides it and the app keeps running in the Dock (the receiver must keep running); clicking the Dock icon reopens it; Cmd+Q quits. The window title shows the display name, so two copies are easy to tell apart.
- Dock badge with the number of incoming offers; progress summary ("2 jobs · 34%") in the window title / Dock menu.
- For each incoming offer: bounce the Dock icon (request user attention) and open the accept dialog when the window is shown.
- Main window:
    - Machines: peer list with name, online / last seen, free space.
    - Files: home-directory browser with breadcrumb, list view, multi-select, background folder sizes, and "Send to ▸ <machine>".
    - Transfers (jobs and history in one list): per-peer totals at the top; "In progress" with incoming offers first, then waiting, running, paused and failed (resumable) jobs, each with direction, peer, state, progress bar, bytes, rate, ETA, current file, and Pause / Resume / Cancel; then "Completed" with finished jobs and their stats, newest first. Incoming offers are badged.
    - Settings: relay folder, display name, home folder, window count, chunk size, poll interval, verify from disk, trash retention, trash size, "empty trash".
- Accept dialog:
    - sender, total files and size, free space here (warn if insufficient)
    - an expandable tree with badges new / changed / same (by size + mtime)
    - each root's destination with Accept and with Accept as copy; "Change…" puts a root into another folder inside home (keeping its name), "Put everything in…" moves all, "Reset" restores the sender's folder; statuses and the skip list are computed against the chosen place; the engine refuses protected or outside folders and two roots at one path
    - buttons: Accept, Accept as copy, Decline

Keep the code compact and readable. Add a README covering usage, the relay protocol, and the OneDrive caveats.