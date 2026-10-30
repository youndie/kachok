---
id: B-153
title: "The remembered torrents come back in the order they were added, so the download queue keeps its order across a restart"
status: done
priority: P2
size: S
stage: m3-storage
epic: feature-download
blocked_by: [B-151]
---

# B-153 — The queue order survives a restart

Seen an hour after B-151 went live: eleven films added in order, the first three downloading, and then
the MCP engine restarted — its process lives as long as the connection that started it. Afterwards the
4th, 5th and 8th were downloading and the 1st, 2nd and 3rd, each a few percent in, were queued.

- **The cause.** The set's queue gives turns in the order torrents were opened, and a restart opens the
  remembered list in the order `loadStoredTorrents` returns it — sorted by info hash, a stable order
  nobody chose. The three whose hashes sorted first got the turns.
- **The decision and its reason.** The torrent's `.torrent` copy is written once, when it is added:
  `rememberTorrent` now leaves a copy that is already there (same info hash, same torrent) instead of
  rewriting it, so the copy's modification time is when the torrent was added — for entries written
  before this too. `loadStoredTorrents` sorts by it, and by info hash where it cannot be read. No
  `added` key of its own, because the process's clock is not read for stored values in this
  repository; the filesystem's time is only ever compared with itself.

- AC: the list comes back in the order it was added, and remembering a torrent again keeps its place.
  **Met.** **Automated:** `control/src/test/.../store/ReopenTest.kt` —
  `theListComesBackInTheOrderItWasAdded` (fails without the change: the list came back by hash).
- Anchors: `control/src/main/kotlin/io/github/youndie/kachok/control/store/StoredTorrents.kt`.
