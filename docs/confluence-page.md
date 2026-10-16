# mac-one-copy: move files between your Macs through OneDrive

## In short

mac-one-copy moves files and folders of any size from one Mac to another, e.g. when you migrate to a new MacBook. You pick what to send on the old Mac and accept it on the new one; the tool does the rest, end to end, even for 100–200 GB. Your personal OneDrive folder is the only link between the two Macs. There is no network connection between them, and nothing goes outside the storage you already use.

## The problem

Moving to a new MacBook, or moving large project folders between two Macs, is harder than it should be:

- **No direct connection between the Macs.** They can't see each other on the network, and peer-to-peer tools, AirDrop and external drives are often not an option.
- **OneDrive is the only shared place, but it's slow and limited.** Copying 100–200 GB into OneDrive and out again needs that much free space in OneDrive, takes very long, and fails halfway with no easy way to continue.
- **So it's done by hand, piece by piece.** People split folders into chunks that fit, upload, wait, download, delete and repeat, for hours or days, and still end up unsure whether everything arrived intact.

## The solution

mac-one-copy automates that piece-by-piece process and makes it reliable.

1. **On the sending Mac** you select files or folders and choose the target Mac.
2. **On the receiving Mac** you see what's coming (files, size, what's new, changed or already there, and where it will go) and accept it, accept it as a copy, or decline.
3. **The tool cuts the data into chunks** and keeps only a few of them in your OneDrive at a time (by default 5 × 200 MB, about 1 GB). OneDrive syncs them, and the receiving Mac checks each chunk, writes it into place and deletes it from OneDrive. Then the next chunks follow.
4. **When it's done,** both Macs record the transfer in their history, including anything that was skipped or failed and why.

You don't have to keep watching it. Transfers continue after sleep, restarts or network interruptions, and can be paused, resumed or cancelled from either Mac.

## Benefits

| | |
|---|---|
| **Very large transfers, end to end** | 100–200 GB, or more, in one transfer, without splitting anything by hand. |
| **Uses almost no OneDrive space** | Only a small, fixed window of chunks (about 1 GB by default) is in OneDrive at any time, however big the transfer is. |
| **Resumes, doesn't restart** | After sleep, a restart or a network drop, it continues where it stopped. Only failed pieces are sent again, never the whole transfer. |
| **Verified data** | Every chunk and every file is checked with SHA-256. A damaged piece is detected and sent again on its own. |
| **You control what lands where** | You see each item's destination before accepting, can choose another folder, and can receive everything as a copy so nothing on the new Mac is overwritten. Replaced files are kept in the tool's trash for 14 days. |
| **Clear outcome** | Each transfer lists exactly what was skipped or failed, with the reason, on both Macs. |
| **Protects private macOS folders** | Desktop, Downloads, Pictures, Movies, Music, Library (including iCloud Drive) and the Trash are never read or written, so no macOS privacy prompts and no accidental copying of personal data. Documents is supported. |
| **Simple** | A Mac app with Machines, Files, Transfers, Settings and built-in Help. |

## Designed for compliance

- **No peer-to-peer connection.** The Macs never connect to each other. No ports are opened, no network services run, and nothing new is installed on the network.
- **Only your own OneDrive.** Data goes through your existing personal OneDrive folder, using the official OneDrive client already installed and managed on your Mac. There is no other cloud service, no third-party server, and no direct use of the OneDrive API.
- **Your account, your space.** Both Macs must be signed in to the same OneDrive, so data never leaves your own secured storage.
- **Short-lived data in OneDrive.** Chunks are deleted as soon as they have arrived. Only the small working window is ever in OneDrive.
- **Local records.** Each Mac keeps a history of what was sent and received, and when.

> **Information Security review:** _pending / approved on ____ by ____ (link)_

## Good to know

- **Speed** depends on OneDrive's upload and download speed. A 100–200 GB transfer can take many hours; let it run, e.g. overnight. If it seems stuck, check the OneDrive menu-bar icon: if it shows files syncing, the transfer is moving.
- **Deletion warnings:** OneDrive may warn that many files were deleted, because the tool deletes chunks as they arrive. Confirm it, and tick "don't show this again".
- **OneDrive recycle bin:** deleted chunks go to the OneDrive recycle bin and may count against your quota until it's emptied.
- **What isn't kept:** file permissions, Finder tags and some file attributes. Apps copied this way may not run; reinstall them instead. Symlinks are recreated only if they point inside the folder being sent.
- **Both Macs need the app running** for data to move, but not at the same time. An offer waits until the other Mac comes online.

## Getting started

1. Install mac-one-copy on both Macs (_link_).
2. On first start, choose the same relay folder inside your OneDrive on both Macs (the default suggestion is fine) and give each Mac a name.
3. On the old Mac, open **Files**, tick what you want to move, and choose **Send to ▸** your new Mac.
4. On the new Mac, review the offer and click **Accept**.
5. Follow progress under **Transfers**. When it's done, check "not transferred" for anything that needs attention.

**Questions or problems:** _contact / channel_
