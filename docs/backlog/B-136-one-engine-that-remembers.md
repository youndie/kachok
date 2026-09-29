---
id: B-136
title: "A headless kachok mcp is the machine's one engine, and it remembers its torrents"
status: done
priority: P2
size: M
stage: phase-3-server
blocked_by: [B-133]
---

# B-136 — One engine that remembers

`kachok mcp` with no window running kept its torrents in memory only. A 42 GiB season was being
downloaded through it when the agent runtime restarted its MCP servers, and the next
`list_torrents` had only the torrent added after the restart: the files and the record were on the
disk, and nothing would have added the season again had a person not noticed. On the same machine,
every agent session had started a `kachok mcp` of its own — eight engines at once, each with its own
peer port and DHT node, none knowing about the others' torrents. Reported as
[youndie/kachok#59](https://github.com/youndie/kachok/issues/59).

- **The decision and its reason.** The headless engine takes the single-instance lock the window
  already uses (B-84, B-117) and keeps the list the window already keeps (B-81). Taking the lock is
  what makes the next `kachok mcp` attach instead of building an engine; keeping the list is what
  makes a restart reopen the torrents where they were. Both are existing mechanisms: the store moved
  from `:ui` to `:control` so that `:cli` can use it, and the server is handed the same
  `StoredTorrentsKeeper` the window hands its own (B-133).
- **Exactly one engine opens the list**, which is what the lock is for. A window starting while a
  headless engine holds the lock would, until now, have handed its paths over and exited — the
  person would have seen no window at all. So a window asks first (`claimForWindow`, the word
  `window` after the secret): a headless holder answers `kachok/yielding`, stops its torrents so
  their records are written, lets go and exits, and the window takes the lock and opens the same
  list. A holder that is a window answers `kachok/stay`, and an older one hangs up on the word;
  either way the new window hands over and exits, as before.
- **Every torrent is stopped on the way out**, not only closed. `TorrentSet.close` closes files; the
  stop is what writes a torrent's record, and the next engine trusts the record.
- **Stdin is read on a daemon thread of its own.** It used to be on the engine's executor, and
  closing the executor waits for its threads: an engine yielding with its agent still connected
  would never have finished closing, because a read of stdin cannot be interrupted.
- **`--standalone` neither takes the lock nor keeps the list.** It is the second engine asked for by
  name, and two engines on one list would open the same files twice.
- **The alternative that was rejected.** A separate list for headless engines. It avoids the lock
  question and splits one person's torrents between two lists that each surface sees half of —
  the problem B-117 solved for the window and the agent, reintroduced for the agent and itself.
- Not covered: the agents attached to a headless engine that yields lose their session; their
  runtime starts `kachok mcp` again, and that one attaches to the window.

- AC: a torrent added through a headless `kachok mcp` is listed after the process is restarted; a
  second `kachok mcp` attaches to the first; a window started while a headless engine runs is given
  the engine and the list. **Met.**
  **Automated:** `cli/src/test/.../mcp/RememberTest.kt` — `whatWasAddedIsThereAfterARestart`,
  `aSecondServerAttachesToTheFirstRatherThanBuildingAnEngine`,
  `aWindowIsGivenTheEngineAndTheListIsLeftForIt`; `control/src/test/.../SingleInstanceTest.kt` —
  `aWindowMeetingAWindowHandsItsTorrentOverAsBefore`, `aHolderThatYieldsGivesTheWindowTheLock`.
- Anchors: `cli/src/main/kotlin/io/github/youndie/kachok/cli/mcp/Mcp.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/SingleInstance.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/store/StoredTorrents.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`.
