---
id: B-110
title: "This client never uploads a block: no live connection is ever given the storage to serve from"
status: open
priority: P1
size: S
stage: m5-seeding
epic: feature-download
---

# B-110 — This client never uploads a block: no live connection is ever given the storage to serve from

`SocketPeerConnection` takes `blocks: FileStorage? = null` and its writer does
`val source = blocks ?: continue` when asked for a block — a request it cannot serve is dropped
without a word. Nothing ever passes the storage in. `SocketPeerDialer.connect` calls
`SocketPeerConnection.connect(scope, address, infoHash, peerId, pool, reserved)`; the set's accept
path calls `SocketPeerConnection.answer(scope, socket, handshake, infoHash, peerId, pool, reserved)`;
`TorrentRuntime` builds a `FileStorage` and hands it to the *session*, for writing. So every real
connection this client has ever held serves nothing: `Session.serveNow` sends the block into the
void, adds the length to its own upload meter, and publishes `uploaded` from
`connection.uploaded` — which is the one counter that tells the truth, and stays at zero.

**The whole upload side is built and tested and wired to nothing.** [B-20](B-20-upload-read-path.md)
measured `transferTo` from the page cache to a socket; [B-21](B-21-choker.md) unchokes the four
best interested peers; [B-22](B-22-rate-limits.md) budgets what they get. Every one of them is
exercised against a fake connection, and the one line that would have joined them to a socket was
never written. No test asserts that a real peer received a byte from this client, on any surface.
[B-105](B-105-connections-are-made-and-not-kept.md) even says it, as a scoping note: *"this client
is a leecher throughout the measurement"* — true, and true of every measurement in M9, which
means every peer count in [research D13](../research/research-architecture.md) was taken by a
client the swarm had every reason to choke.

**Found by [B-108](B-108-an-mcp-server-for-agents.md)'s acceptance**, the first time one kachok
was made to download from another. Two `kachok mcp` processes on one machine and a tracker
between them:

| side | pieces | uploaded | peers | requests outstanding | bytes moved |
|---|---|---|---|---|---|
| seeder | 128/128 | 0 | 1 connected | — | 0 |
| leecher | 0/128 | — | 1 connected, unchoked | 16 | 0 |

The leecher was unchoked, asked, and waited; the seeder received every request and served none.
The magnet's metadata *did* come across — BEP 9 goes through `send(Message)`, not `sendBlock` —
which is why the failure looks like a seeder that talks and never delivers, and why it was not
found sooner: everything but the block itself works.

- **The decision and its reason.** Hand the runtime's `FileStorage` to every connection the
  runtime makes or accepts — the dialer takes it at construction, the set's accept path takes it
  off the runtime it routed the handshake to — so that `sendBlock` reaches `transferTo` the way
  B-20 measured it. The default of `null` goes: a connection that cannot serve is not a
  configuration, it is the bug, and a parameter with that default is how it stayed invisible.
- **Then the test that would have caught it, through the real path**: two runtimes in one process,
  one holding a complete file, the other downloading it from the first over a real socket, and the
  assertion that the *first* one's `uploaded` grew and the second one's file matches. That is the
  seam every upload test skipped. The MCP server's test harness already has both halves.
- **And a reading of M9's numbers in that light**, in the research: the dial and hold figures were
  those of a client that never reciprocated. Whether they improve once it does is a measurement to
  re-run, not a conclusion to draw.
- Rejected: keeping the default and adding a warning when it is null. A warning nobody reads is
  the state this has been in.
- Not covered: [B-109](B-109-download-seed-closes-the-set-before-it-seeds.md), which is the other
  reason `download --seed` serves nobody and is its own line of code.

- AC: two clients on one machine, one with the file and one without, connected through a tracker;
  the second finishes and its file matches byte for byte; the first's `uploaded` equals the file's
  length, read from its state and not inferred. Then the B-98 measurement re-run on the same public
  torrent, with the `uploaded` column added, so that the next reader of D13 knows which numbers
  were taken by a leecher and which by a peer.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerConnection.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerDialer.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `cli/src/test/kotlin/io/github/youndie/kachok/cli/mcp/McpServerTest.kt`.
