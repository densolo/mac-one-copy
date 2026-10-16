# mac-one-copy

Copies files and folders between Macs that cannot reach each other, using a OneDrive folder that the official OneDrive client syncs on every Mac as the only channel. OneDrive is slow and has limited quota, so the folder is used as a relay with a small shared buffer of chunk files. Everything is plain file I/O; there is no Graph API.

Modules: `engine` (transfer logic, no UI), `cli` (headless), `app` (Compose Desktop UI).

## Build and test

```bash
./mvnw package
```

This needs JDK 21 or newer. It produces:

- `cli/target/mac-one-copy-cli.jar`, the CLI
- `app/target/mac-one-copy-app.jar`, the desktop app, run with `java -jar`

To build a real `mac-one-copy.app` with its own Java runtime in `app/target/dist/`, use the `app-image` profile:

```bash
./mvnw -pl app -am package -Papp-image
```

The app icon is drawn by `tools/icon/Icon.java`; `tools/icon/make-icns.sh` regenerates `app/src/main/packaging/mac-one-copy.icns` (for the bundle) and `app/src/main/resources/icon.png` (the Dock and window icon when run from IntelliJ or the jar). The app is built for Apple silicon. For an Intel Mac, add `-Dcompose.platform=macos-x64`.

Compose Multiplatform publishes Gradle metadata only, so `app/pom.xml` pins the resolved Compose Desktop runtime explicitly (between the `compose-deps` markers). To regenerate that list after a Compose upgrade, run `gradle -p tools/compose-deps -q deps`. The Compose compiler plugin is handed straight to `kotlinc`.

The test against a real OneDrive folder is opt-in. It starts two engines on this Mac, uses a fresh `run-<time>` folder and deletes it afterwards:

```bash
MOC_REAL_ONEDRIVE=~/Library/CloudStorage/OneDrive-Personal/mac-one-copy-test ./mvnw -pl engine test -Dtest=RealOneDriveTest
```

Both engines see the same local folder, so this test covers the File Provider behavior (renames, deletes, background uploads) but not sync between Macs. To test between Macs, run the CLI on each of them.

## App

- **First run:** pick the relay folder inside OneDrive (the app suggests `~/Library/CloudStorage/OneDrive-*/mac-one-copy`) and give this Mac a name. Received files go under the home folder, which is `~` unless you change it in Settings.
- **Screens:**
  - **Machines:** this Mac at the top (name, free space, home and relay folder), then each peer, online or last seen, with its free space.
  - **Files:** a home-folder browser. Click to select, Cmd-click or Shift-click for more, double-click to open a folder. Folder sizes are computed in the background. Symlinks are shown but can't be sent. Then choose "Send to ▸ machine".
  - **Transfers:** one list with totals per Mac at the top. **In progress** shows incoming offers first, then waiting, running, paused and failed jobs, with progress, rate, ETA, current file, and Pause, Resume and Cancel. **Completed** shows finished jobs, newest first, with their stats.
  - **Settings:** relay, name, home, window, chunk size, poll interval, verify from disk, trash retention, and Empty trash.
  - **Help:** how it works, sending and receiving, what can be transferred, protected folders, OneDrive notes.
- **Incoming offer:** the Dock icon bounces and its badge counts the waiting offers. The accept dialog opens with a tree that marks each file new, changed or same, and warns if there isn't enough free space.
- **There is no tray icon.** Closing the window only hides it, and the app keeps receiving. Click the Dock icon to bring the window back, or Cmd+Q to quit. The Dock menu and the window title show progress, e.g. "Studio — mac-one-copy · 2 jobs · 34%".
- **Options:** `--data-dir`, `--name` and `--home`, the same as the CLI. The run configurations `app`, `app (2)` and `app (3)` start three copies on one Mac.
- **Tests:** `ScreenshotTest` runs two real engines and renders every screen into `app/target/screenshots/`.

## CLI

```
mac-one-copy-cli [--data-dir DIR] [--name NAME] [--home DIR] <command>

  run [--relay DIR] [--auto accept|copy|decline]   run the engine in the foreground
  peers                                           other machines on the relay
  send --to PEER PATH...                          offer files or folders (PEER: name or id prefix)
  jobs                                            running and waiting jobs
  decide JOB accept|copy|decline                  answer an incoming offer
  pause JOB | resume JOB | cancel JOB             from either side
  history                                         finished jobs and totals per peer
```

- **`run`:** owns the app data folder, and a lock file stops a second process from using it. The relay folder is saved in the settings. If it was never set, `run` uses the first `~/Library/CloudStorage/OneDrive-*` folder plus `/mac-one-copy`.
- **Other commands:** they hand their request to the running engine through `<data-dir>/inbox/`, and read what it last published from `<data-dir>/snapshot.json`.
- **Paths:**
  - `send` paths are relative to the home folder.
  - An absolute path, or a path relative to the current folder, must be inside home.
  - Every path is stored relative to home, because account names differ between Macs.

### A second or third copy on one Mac

Each copy only needs its own app data folder. `--name` and `--home` are saved to that copy's settings. The environment variables `MAC_ONE_COPY_DATA_DIR`, `MAC_ONE_COPY_NAME` and `MAC_ONE_COPY_HOME` work too.

```bash
java -jar cli/target/mac-one-copy-cli.jar --data-dir "~/Library/Application Support/mac-one-copy-2" --name "{host} 2" --home ~/mac-one-copy-2 run
```

`{host}` expands to the host name. Every copy uses the same relay folder, so each one shows up to the others as a separate machine. Files it receives land under its own home folder.

The IntelliJ run configurations in `.run/` start the default copy and two test copies (`~/mac-one-copy-2`, `~/mac-one-copy-3`): `cli run`, `cli run (2)` and `cli run (3)` for the CLI, and `app`, `app (2)` and `app (3)` for the app.

## Relay protocol

The relay lives in `<relay folder>/relay/`:

```
peers/<machine-id>.json          displayName, user, freeBytes, appVersion, lastSeen (rewritten every 45 s)
jobs/<job-id>/
  manifest.json                  sender: from, to, roots, files [path, size, mtime] sorted by path, emptyDirs
  decision.json                  receiver: accept | accept-copy | decline, skip list (file indices)
  control-<machine-id>.json      each side: request seq + pause|resume|cancel (+ reason), ack of the peer's seq;
                                 receiver also: status (transferring|completed|failed), failed files, bad segments
  resend-NNNN.json               sender, on resume after a failure: files resent in full, segments resent, files given up on
  NNNNNN.chunk                   data
```

- **Writes:** every write is atomic: the engine writes `<name>.<writer>.tmp`, fsyncs it, then renames it.
- **What readers accept:**
  - Readers accept only the exact names above.
  - They ignore temp files, OneDrive conflict copies such as `000042-MacBook.chunk`, and anything they can't parse (logged once).
  - Reads run with a timeout, because opening a file OneDrive hasn't downloaded yet can block. A timeout means "retry on the next poll".
- **Online:** a peer counts as online if it was seen within the last 3 minutes.

### Chunks

- **Header:** magic `MOC1`, version, job id, seq, resend generation, record count.
- **`DATA` record:** file index, offset, length, bytes.
- **`FILE_END` record:** file index, flags, the file's segment list (offset, length, sha256), and a file hash, which is the sha256 over the segment hashes. The flag `SOURCE_CHANGED` tells the receiver the sender gave up on that file.
- **Trailer:** sha256 over everything before it. Only a matching trailer proves a chunk is complete. OneDrive may show a chunk at full size before its data has downloaded.

The layout is deterministic. Files are sorted by path and packed in order, so small files share chunks and big files span several. Each `DATA` record is one hashed segment. The sender keeps segment hashes in its job state and the receiver hashes each record as it applies it, so neither side ever re-reads a file to hash it, not even after a restart. A setting, `verifyFromDisk`, re-reads each finished file once.

The receiver works in one pass per chunk:

1. It writes the bytes into `<dest>.part` at their offsets.
2. The segments only count as received once the trailer checks out.
3. When some `FILE_END`'s segment list is complete, it compares the hashes.
4. It moves any original into `<data-dir>/trash/<job>/` (plain accept only), renames the `.part` into place, and sets the sender's mtime.

Deleting the chunk is the receiver's ack.

### Window

At most `windowChunks` chunk files (default 5 × 200 MB) exist in the whole relay, across all jobs and senders. Each sender also keeps to ⌈window / active senders⌉ chunks of its own, so sync lag can push the total over the limit by at most one chunk per sender. Accepted jobs take turns. An offer that hasn't been answered may pre-send one chunk, and only when there is room.

### Job lifecycle

Offered → Transferring ⇄ Paused → Completed | Cancelled | Failed, or Offered → Declined.

- **Accept:** replaces existing files and moves the originals to trash first. Files whose size and mtime already match are skipped.
- **Accept as copy:** touches nothing that's already there. It names the copies `name (copy).ext`, `Folder (copy)`, `(copy 2)`, and so on.
- **Pause, resume, cancel:** either side can trigger them through its control file. The side that asked shows "Pausing…" or "Cancelling…" until the other side acknowledges.
- **Disk full:** the receiver pauses the job with the reason "disk full" (the same pause is sent to the sender). This happens when free space drops below the remaining bytes plus a margin, or on ENOSPC. Resume tries again.
- **Failed:** the job can be resumed. A file whose source changed is resent in full, with its new size and mtime. A segment whose hash didn't match is resent on its own.
- **Restart:** after a restart or sleep, every job is rebuilt from local state plus the relay. Paused jobs stay paused.

History is kept in `<data-dir>/history.jsonl`, one JSON line per finished job. If a job has more than one line, the last one wins.

## What can be transferred

- **Supported:** regular files of any type and size, folders (including empty ones), modification dates, and any file names macOS allows.
- **Skipped:** symlinks (with a warning), special files, `.DS_Store`.
- **Not kept:** permissions and the executable flag, owner, creation date, Finder tags, extended attributes and resource forks. Apps and other packages arrive as plain folders, so apps may not run afterwards. Hard links become separate copies.

### Protected macOS folders

`Desktop`, `Downloads`, `Movies`, `Music`, `Pictures`, `Library` (which holds iCloud Drive, `Library/Mobile Documents`, and OneDrive itself) and `.Trash` need special macOS permission to read, so mac-one-copy never touches them:

- Sending from one of them is refused.
- An offer into one of them is declined automatically.
- The file browser shows them with a lock and never opens or sizes them.
- A home folder inside one of them is rejected in Settings.

`Documents` is supported.

## OneDrive caveats

- **Recycle bin and quota:** each chunk is deleted as soon as it's applied, and deleted files go to the OneDrive recycle bin. That may count against your quota until the recycle bin is emptied, so a large transfer can temporarily use about its own size in recycle-bin space.
- **Placeholders and sync order:** files show up as placeholders and sync in any order. This is why chunks carry a trailer hash and JSON files that can't be parsed are retried.
- **Deletion warnings:** because chunks are deleted all the time, OneDrive may warn that files were deleted and ask you to confirm. Confirm, and tick the option not to show the message again. Until you confirm, OneDrive may hold the deletions back, and senders wait because the window looks full.
- **A transfer that seems stuck:** open the OneDrive menu-bar icon. If it shows files syncing or a queue of pending changes, OneDrive is still moving chunks and the transfer only needs time. Also check that OneDrive isn't paused, signed out, or waiting for a confirmation, and that mac-one-copy is running on both Macs.
- **Polling:** the engine polls (every 5 s by default) instead of using WatchService, which is unreliable on File Provider folders.
- **Conflict copies:** they are never read. A stray one can be deleted by hand.
