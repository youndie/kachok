---
id: B-147
title: "A connection closed while its block waited for the session kept the block's pool buffer"
status: done
priority: P1
size: S
stage: m3-storage
blocked_by: []
---

# B-147 — A closed connection keeps its buffers

Seen on Windows with the MCP engine running nine torrents overnight: every torrent at 0 B/s for
about seven hours, peers connected (39), 96 requests out on one torrent, tracker and port mapping
fine, the data disk idle, the Wi-Fi adapter receiving ~8 KB/s. A thread dump had six of the 39
readers in a blocking socket read and the rest nowhere — suspended coroutines. A restart of the
process brought ~22 MiB/s back within minutes. The same picture had been seen once before in the
window app after a network blip, and a restart was the only cure then too.

- **The cause.** A reader suspends in two places: `pool.acquire()` and `incoming.send(...)`. In the
  second it holds a block in a pooled buffer. `SocketPeerConnection.close()` cancels the reader and
  closes the queue; a cancelled send, or one that meets a closed queue, keeps its element, and the
  buffer never goes back. The session is behind — and so its readers wait in `send` — for as long
  as the writer waits on the disk, which after B-146 meant minutes at a time on files created
  before that fix; any timeout or close in that window leaked a buffer. A second, rarer path: the
  session's loop for a peer ends on an exception (a bitfield of the wrong length), and the events
  queued behind it, blocks included, stay in a closed channel nobody reads. A pool losing buffers
  one at a time works until the last one; then every reader waits in `acquire`, no socket is read,
  the peers' data sits in the receive windows, and the requests sit out.
- **The decision and its reason.** The incoming queue is built with an `onUndeliveredElement` that
  releases a block's buffer, which covers a cancelled send, a send to a closed queue and a receive
  cancelled after it took the element. The session drains the queue with `tryReceive` after it
  closes a connection, releasing the blocks it never read; the connection is closed by then, so
  nothing more arrives behind them, and a send that loses the race meets the closed queue and the
  handler. Draining in the session rather than cancelling the channel keeps the connection's
  `close` a close: a session that pauses still reads the blocks already queued.
- **Diagnosis for next time.** `torrent_status` says `buffers: N of M on loan`. A pool at its cap
  with 0 B/s is this; a pool near empty with 0 B/s is a swarm with nothing to give.
- The research said the reader suspends in exactly one place; it is amended at that point (D1,
  second correction).

- Not covered: the blocks in the writer's queue when a session stops are not released, which is
  harmless — the pool goes with the session.

- AC: a block waiting in `send` when its connection closes is back in the pool. **Met.**
  **Automated:** `engine/src/jvmTest/.../io/SocketPeerConnectionTest.kt` —
  `aBlockWaitingForTheSessionGoesBackToThePoolWhenTheConnectionCloses` (fails without the queue's
  handler, measured).
- AC: a block queued behind a message that ends the peer is released. **Met.** **Automated:**
  `engine/src/commonTest/.../session/SessionTest.kt` —
  `aBlockQueuedBehindAMessageThatEndsThePeerIsReleased` (fails without the drain, measured).
- AC: `torrent_status` reports the pool. **Met.** **Automated:**
  `control/src/test/.../mcp/McpServerTest.kt` — `theOrderIsSetOnAddAndSwitchedOnARunningTorrent`.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerConnection.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`.
