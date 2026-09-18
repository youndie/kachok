---
id: B-110
title: "This client never uploads a block: no live connection is ever given the storage to serve from"
status: done
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
measured `transferTo` from the page cache to a socket; [B-21](B-21-choking-algorithm.md) unchokes the four
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

## Iteration 1 — 2026-09-17: wired, counted, and what the first real upload showed

**The wiring.** `BlockSource` is a required parameter of every `SocketPeerConnection` and of
`connect`, `accept`, `answer` and the dialer; `FileStorage` implements it, the runtime builds one
storage and hands it to the session, the dialer and — through `TorrentRuntime.blocks` — the set's
accept path. The one connection with nothing to serve by construction, a metadata fetch, gets
`NoBlocks`, which throws rather than dropping a request in silence. The `null` default is gone.

**The counter, which was wrong twice.** `uploaded` in the state was a sum over the *live*
connections taken at the moment a block was queued — before the writer had sent it, and gone the
moment the peer left — and the field the tracker was told was declared and never incremented. Both
are one accumulator now: what departed connections served, plus what the live ones report, read on
the tick and after every serve, and the tracker is told the same number. A resume record seeds it.

**The test, and its finding.** `KachokSeedsKachokTest`: two runtimes in one process, one holding
the file, a tracker between them; the second finishes with matching bytes and the first's
`uploaded` equals the second's `downloaded`. Both read **120 000 of a 60 000-byte file**: the
seeder heard the leecher's local-discovery announce and dialled it back, both sides held two
connections to one peer, and the leecher's endgame asked the second for everything. The counter is
right; the duplicate connection is [B-111](B-111-two-connections-to-the-same-peer.md), filed with
the table. The assertion here is the two-sided invariant and "at least the file", and says why.

[B-109](B-109-download-seed-closes-the-set-before-it-seeds.md)'s test passes in the same change
and is the other half of this acceptance: a `download --seed` process serving a second client
through the command line's own path.

**A third party, and the count that matches it.** kachok on the build machine seeding the 2 MiB
lab file through `kachok mcp`; qBittorrent 5.2.1 on the Windows machine told of it by a tracker
that names only kachok:

```
(N) 23:33:23 - Added new torrent. Torrent: "blob.bin"
(N) 23:33:35 - Torrent download finished. Torrent: "blob.bin"        ← qBittorrent's log
blob.bin  Length: 2097152                                            ← on its disk
pieces 128/128, uploaded 2.0 MiB, peers 2 connected                  ← kachok's torrent_status
```

Twelve seconds, the file, and the seeder's own counter reading exactly the file's length. That is
the acceptance's first clause against a client nobody here wrote.

**The public swarm, with the column the acceptance asked for.** The same Ubuntu torrent as B-98,
five minutes, rate-limited to 2 000 / 1 000 KiB/s so as not to take the household's uplink:

| | before (B-98, D13) | now |
|---|---|---|
| connected, median | 12–30 | 144 |
| unchoked us | — | 129 |
| dials handshaked / attempted | 303 / 4 423 | 561 / 3 641 |
| `connect timed out` | 2 856 | 2 223 |
| **uploaded** | *not counted* (was 0) | **0** |

So the column is there, and it reads zero for a client that has just been shown serving a whole
file to qBittorrent in twelve seconds. Those two facts are not in tension: a peer takes blocks only
if it is *interested*, and a peer is interested only if it lacks something we have. The reachable
part of this swarm — the 561 that answered a dial — is where the seeds are; the leechers that
would want our pieces are the 2 223 behind a NAT that an outgoing connection cannot reach, which is
[B-103](B-103-upnp-and-nat-pmp-port-mapping.md)'s whole premise and, on this network, its unmet
one. A second run with the number of *interested* peers on the progress line is below; it is the
figure that separates "nobody wants anything" from "we will not give it".

**The one thing the public run and the lab did not share, ruled out.** The public runs carried an
`--up` limit and the lab runs did not, so the token bucket was the remaining suspect. A kachok
seeding through `download --seed --up 800` — the path [B-109](B-109-download-seed-closes-the-set-before-it-seeds.md)
reopened — served a fresh 2 MiB file to qBittorrent 5.2.1 in fifteen seconds
(`Added 23:38:44` → `download finished 23:38:59`, 2 097 152 bytes on its disk). The limit is not
it. What is left is the swarm's side of the question — whether any reachable peer *wants* what we
hold — and that is measured next, with the count of peers interested in us on the progress line.

**The public swarm, three more runs, with the two columns that read the swarm's side.** Same
torrent, 150–200 s each, limited to 1 500 / 800 KiB/s; the progress line now says how many of the
connected peers *want ours* — `peerInterested`, the other direction from the client's own interest,
which a first attempt counted by mistake and which is trivially everyone for a leecher — and how
many are leechers at all:

| run | connected | leechers among them | want ours | uploaded |
|---|---|---|---|---|
| 2 (200 s) | 75–161 | — | — | 0 |
| 3 (200 s) | 61–127 | — | **0**, throughout | 0 |
| 4 (150 s) | 22–72 | 4–12 | **2**, in the first thirty seconds, then 0 | 0 |

So the reachable part of this swarm is nine parts seeds to one part leechers, and the few leechers
this client reaches want its pieces rarely and briefly. That is [B-103](B-103-upnp-and-nat-pmp-port-mapping.md)'s
premise read off the wire — the peers that would take our blocks are the ones behind a NAT that
our dials time out on — and, on this network, its unmet one. What it is *not* is a client that
cannot serve: that client served the whole file to qBittorrent twice, throttled and not, and to
itself. One thread is left hanging, deliberately: two peers said `interested` and were gone before
a byte moved. Whether a short-lived interest can be served at all with a choke pass every ten
seconds is [B-112](B-112-a-peer-interested-for-seconds-is-never-unchoked.md), filed as a
hypothesis and not a finding.

**Done, and what the word covers.** Every connection serves; the count is the connection's own;
the tracker is told the truth; a third-party client downloads from this one; the columns D13 lacked
are on the progress line and in this table. Every upload figure in D13 remains a leecher's, and
the research now says so at the point of divergence.

