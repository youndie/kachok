---
id: B-81
title: "The list of torrents survives a restart"
status: done
priority: P1
size: L
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-81 — The list of torrents survives a restart

**Where the client keeps its state today, in full:**

| What | Where | Since |
|---|---|---|
| settings | `~/Library/Application Support/kachok/settings.properties`, `%APPDATA%\kachok\`, `$XDG_CONFIG_HOME/kachok/` | [B-71](B-71-settings-that-survive-a-restart.md) |
| which pieces are verified | `<name>.<8 hex of info hash>.resume`, **beside the data** | [B-60](B-60-two-torrents-one-path.md) |
| which torrents there are | **nowhere** | — |

Closing the window loses every torrent. `main` reopens exactly one thing — the path in `argv[0]` —
and `Client` opens nothing else. The resume records are still on the disk and still good; there is
simply nothing that knows they exist, so the work they represent is only recoverable by adding the
same `.torrent` again by hand.

- **The decision this needs.** What a torrent *is*, on disk. A resume record vouches for pieces and
  identifies its torrent by info hash; it does not carry the metainfo, the directory, the unwanted
  files or whether it was paused. Either the client keeps its own list — a file naming each
  torrent's metainfo path, directory and per-torrent choices — or it keeps a *copy* of each
  `.torrent`, which is what most clients do and is the only version that survives the original file
  being moved or deleted.
- Rejected in advance: scanning the download directory for `.resume` files. The record does not say
  where the metainfo is, and a torrent cannot be reopened without it.
- Rejected in advance: storing the list inside the settings file. Settings are a handful of scalars
  that a person edits; this is a growing list the client owns, and one damaged entry must not cost
  the other nine.
- Not covered: the order of the list, and the selection — both cheap once there is a list, and
  neither is worth an item.

**This blocks autostart being worth anything.** An application that starts with the operating system
and comes up with an empty list ([B-83](B-83-autostart-and-its-setting.md)) has started for no
reason.

- AC: a client that is closed with three torrents — one paused, one with a file skipped — comes back
  with the same three, in the same states, without re-verifying anything the records vouch for; a
  list entry whose metainfo has gone says so on the row rather than disappearing.
- Anchors:
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/StoredTorrents.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/StoredTorrents.kt),
  [`engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/MetainfoWriter.kt`](../../engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/metainfo/MetainfoWriter.kt),
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt),
  [`docs/services/ui.md`](../services/ui.md) §7.

## Done — a copy of the torrent, not a pointer to it

**The decision was the copy.** A path to the file somebody added turns "I tidied my Downloads
folder" into "my client forgot what it was doing", and half the torrents here have no file at all —
after BEP 9 a magnet's metainfo exists only in memory. So `MetainfoWriter` writes a `.torrent` back
out of a `Metainfo`, and one door serves both sources.

That writer is where the only real hazard in this item lives, and it is silent. The info hash is the
torrent's identity: it names the resume record beside the data and it is what a peer asks for in a
handshake. `Bencode.encode` is canonical, and **torrents whose info keys are not sorted circulate** —
`MetainfoParserTest`'s own fixture is one. Re-encoding such a torrent produces a file that parses,
names the same files, has the same size, and *is a different torrent*: its resume record belongs to
somebody else and its swarm has never heard of it. So the info dictionary is spliced in as bytes and
the surrounding keys are put in byte order by hand — `announce` < `announce-list` < `info`.

**Where the state is, in full**, now that the third row of the table at the top has an answer:

| What | Where |
|---|---|
| the settings | `<config>/settings.properties` |
| the list of torrents | `<config>/torrents/<info hash>.torrent` — the copy |
| each torrent's own choices | `<config>/torrents/<info hash>.properties` — name, directory, paused, unwanted, sequential |
| which pieces are verified | `<name>.<8 hex>.resume`, beside the data |

`<config>` is the platform's, written down in [docs/services/ui.md](../services/ui.md) §7. One pair
of files per torrent and not one list file, because one damaged entry must not cost the other nine —
a directory listing *is* the list.

**A paused torrent is started paused, which is not the same as started and then paused.** Coming up
normally would announce `started`, dial peers, and only then announce `stopped` and hang all of them
up: a round of swarm churn on every restart, per paused torrent, over a decision taken before the
process began. `Session.start(startPaused = true)` sets the flag before the loops launch, and the
announce loop already says nothing while it is true.

**An entry that will not open is a row, not a silence.** The name is written into the properties
file rather than read from the metainfo — because the case this exists for is the one where there is
no metainfo, and `b9f1cc…0625 could not be opened` is a row nobody can act on. The copy's file name
is also checked against the hash inside it: an unchecked name would open a torrent whose resume
record belongs to a different one, and re-check the wrong data without a word.

Not covered, as the item said: the order of the list beyond being stable, and the selection.

**Automated:** `ui/src/desktopTest/.../session/RestartTest.kt` — a window opened with no arguments
comes up holding what the last one had, a paused torrent comes back paused, and a torrent whose copy
was deleted appears as a row and a reason. Verified by making the start-up read return an empty list,
at which point three of its four fail ·
`ui/src/desktopTest/.../session/StoredTorrentsTest.kt` ·
`engine/src/commonTest/.../metainfo/MetainfoWriterTest.kt`, which is where the unsorted-info hazard
is pinned ·
`ui/src/desktopTest/.../session/SessionRowTest.kt#aTorrentThatCannotBeOpenedIsAnErrorRowOfDashes`.
