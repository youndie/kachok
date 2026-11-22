---
id: B-191
title: "Removing a torrent with its data through MCP leaves its empty folder behind"
status: done
priority: P2
size: S
stage: phase-2-ui
---

# B-191 — Remove with data leaves the torrent's empty folder

Seen on 2026-11-22 in the owner's `D:\tvshows`: a ten-file torrent added beside another one to
compare their swarms, then `remove_torrent` with `delete_data: true` — the answer said it *deleted
its 10 file(s)*, and the torrent's folder stayed, empty, in the save directory.

- **The cause: two implementations.** The MCP server deleted `runtime.paths` and nothing else
  (`McpServer.remove`); the window's `deleteQuietly` deleted them and then each file's parent if it
  was empty. They had drifted, and the window's was wrong in the other direction: a single-file
  torrent's parent *is* the save directory, which it deleted whenever the torrent had been the only
  thing in it; and in a torrent with sub-folders it pruned only the innermost one.
- **The decision.** One implementation, in the engine: `DownloadedFiles.delete(files, root)` deletes
  the files and then every folder between them and `root` — the torrent's `directory` — that is
  empty, deepest first, and never `root` itself. Both the MCP server and the window call it. A move
  already pruned this way (B-134); its private `pruneEmpty` is now `DownloadedFiles.pruneEmptyFolders`,
  so there is one rule for which folders the client may take away.
- Rejected: copying the window's version into the MCP server. It was the one with the bug in it.

- AC: removing a multi-file torrent with its data takes its folder and every empty folder inside
  it; a folder holding anything else stays; the folder it was saved into stays even empty; a file
  already gone stops nothing. **Automated:**
  `engine/src/jvmTest/.../storage/DownloadedFilesTest.kt` (the window's `DeleteQuietlyTest`, moved
  and extended): `foldersInsideTheTorrentsFolderGoToo`, `theFolderItWasSavedIntoStaysEvenEmpty`,
  `nothingOutsideTheRootIsPruned`, and the three cases it had.
- AC: `remove_torrent` with `delete_data` and the window's *Remove and delete* go through the same
  function. **Automated:** `McpServerTest.removeWithDataDeletesWhatWasWritten` (the save directory
  stays), `CommandsEndToEndTest.removingWithTheBoxTickedTakesTheFilesToo`.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/storage/DownloadedFiles.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`.
