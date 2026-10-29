---
id: B-150
title: "Resume records live in the client's own directory, not beside every download"
status: done
priority: P3
size: S
stage: m3-storage
epic: feature-resume
blocked_by: []
---

# B-150 — Resume records live with the client

Asked for by the person using the client: a folder of films had a `<name>.<8 hex>.resume` beside
every one of them, which is the client's bookkeeping in somebody's library.

- **The decision and its reason.** `SetOptions.resumeDirectory` says where every torrent of a set
  keeps its record. The MCP engine and the window give it `resume/` beside `torrents/` in the
  client's own directory (`%APPDATA%\kachok`, `~/Library/Application Support/kachok`,
  `~/.config/kachok`), named `<info hash>.resume` — the full hash, because nobody reads that
  directory and the hash is what makes a record one torrent's (B-60). The default stays null,
  beside the files, for the same reason the DHT is off by default: a library, `kachok download` and
  the tests built on it write nothing outside the directory they were given.
- **What was already there.** A record beside the files is read when the new one does not exist yet,
  and deleted once the first save at the new place has succeeded — not before, so a save that fails
  leaves the old record rather than none. Only a record that decoded as this torrent's is deleted.
- **What a move does.** `TorrentSet.move` used to carry the record with the files; a record in the
  resume directory is not beside them and stays where it is.
- **What a removal does.** `TorrentSet.remove(runtime, forgetProgress = true)` waits for the stop —
  the stop is what writes the record — and then deletes it; the MCP `remove_torrent` with
  `delete_data`, the window's *remove with data* and `kachok serve`'s `removeWithData` ask for it.
  A removal from the list alone keeps the record, so adding the torrent again does not hash it all.
  Before this the record outlived even a removal with the data, beside nothing.

- AC: the window and the MCP engine write no record into a download folder. **Met.**
  **Automated:** `engine/src/jvmTest/.../runtime/TorrentSetResumeDirectoryTest.kt` —
  `theRecordIsKeptInTheSetsDirectoryAndNotBesideTheFiles`.
- AC: a record left beside the files is trusted, then moved. **Met.** **Automated:**
  `aRecordBesideTheFilesIsTrustedAndThenTakenAway`.
- AC: removing a torrent with its data removes its record, and without keeps it. **Met.**
  **Automated:** `removingATorrentWithItsDataTakesTheRecordAndWithoutItKeepsIt`.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/resume/FileResumeStore.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/mcp/Mcp.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/ClientModel.kt`.
