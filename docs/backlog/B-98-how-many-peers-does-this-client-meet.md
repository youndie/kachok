---
id: B-98
title: "How many peers does this client meet? Measure it against a reference client, then set the cap"
status: done
priority: P1
size: M
stage: m9-swarm
epic: feature-download
blocked_by: []
---

# B-98 — How many peers does this client meet? Measure it against a reference client, then set the cap

The rest of this stage was found by reading the code after an owner reported that a mature client
on Windows showed several times as many peers on the same torrent. Every item in it names a
mechanism that plainly costs peers, and not one of them names a number. The ordering between them
is therefore a hypothesis: the dial loop
([B-95](B-95-the-dial-loop-only-runs-when-something-else-happens.md)) looks like the whole of it,
and it is entirely possible that after the fix this client is still short and the missing peers are
the ones that will only speak encrypted ([B-100](B-100-protocol-encryption.md)) or the ones that
can only reach a port nobody forwarded ([B-103](B-103-upnp-and-nat-pmp-port-mapping.md)).

`maxPeers = 50` is in the same position. It is the placeholder `SessionConfig` opens with — *"every
number here is a placeholder until B-26 measures it"* — and B-26 measured allocation on the hot
path, not the swarm. Fifty has never bound, because the client has never reached it.

- **The decision and its reason.** One public torrent with a large swarm, four runs of thirty
  minutes: this client before the stage, this client after it, and a reference client, each run
  twice. Record `knownPeers`, `connectedPeers`, dials attempted, dials that reached a handshake, and
  the reason each failure gave, sampled every ten seconds. The point of measuring before as well as
  after is that "it got better" is the claim least worth making — what the stage needs to know is
  which of six mechanisms the gap was actually made of, and only the before-run can say.
- **Two clients on one machine is not two independent samples**, and the design has to say so. They
  share one NAT binding, one uplink and one public address; a peer already connected to the
  reference client will refuse a second connection from the same address, so running them at once
  makes each look worse than it is. Run them one after the other on the same torrent within the
  same hour, and record the swarm size the tracker reported in each run so that a swarm that
  emptied between them is visible rather than invisible.
- Rejected: `swarm`, this repository's own test seeder, as the subject. It answers every dial
  immediately and has no address that refuses, which is the one property the measurement is about.
  It stays what it is — the harness for correctness, not for reach.
- Rejected: taking the reference client's peer count off its own window. Its number counts what it
  counts; the comparable figure is connections on the wire, from `netstat`/`ss` on the same
  machine, for both.
- Not covered: throughput. A client with more peers is not automatically a faster client, and
  conflating the two here would let a good bytes-per-second number close an item about reach.
- Not covered: choosing `maxPeers` before the runs. The number the measurement produces goes into
  `SessionConfig` with the run that produced it named beside it, the way every other default here
  is written.

- AC: the research document carries a table of the four runs with the five figures each, the swarm
  size each tracker reported, and a sentence naming which mechanism the gap was made of. The
  `maxPeers` default is either changed or explicitly kept, with the measurement cited either way.
- Anchors: `docs/research/research-architecture.md`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/SessionState.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt`.

## Iteration 1 — 2026-09-17: instrumented, and stopped where the runs begin

**Done, and it is everything that is not a run.**

`SessionState` gained `dialsAttempted`, `dialsHandshaked` and `dialFailures`. The last is bucketed
by a closed set of labels — *connect timed out*, *handshake timed out*, *refused*, *unreachable*,
*reset*, *closed during the handshake*, *another torrent*, *bad handshake*, *other* — and not by
message, because a dial failure's message names the address it failed to reach and counting
messages gives one bucket per peer. The mapping matches on the exception text as well as its type
and says so where it is written: the platform throws `IOException` for cases a person needs told
apart, and everything unrecognised lands in `other` rather than earning a bucket, so that a label
set does not grow with the wording of somebody's libc and make two runs incomparable.

`scripts/peer_reach.py` samples established TCP connections for any process id — `ss` on Linux,
`lsof` on macOS, `netstat` on Windows — every ten seconds into a CSV whose header carries the
client, the torrent and the swarm size the tracker reported. It counts **distinct remote
endpoints**, not sockets. `summarise` prints the comparison table, including *time to half peak*,
which is the column that separates a client that reaches its ceiling in a minute from one that
takes twenty.

**The sampler was checked against a process whose connection count was known** rather than trusted
because it parses. Eight sockets to a listener in the same process reported nine, which is the
right answer and the reason the check was worth running: the process holds both ends, the eight
outbound sockets share one remote endpoint, the eight accepted ones have eight distinct ephemeral
ones, and 1 + 8 is 9. A sampler that counted sockets would have said sixteen; one that deduplicated
by host would have said one. The number it prints is peers.

The empty table and the reasoning behind it are in the research as D13, so that a filled-in row
lands where the rest of this project's measurements live.

**What stopped the item, and it is not a blocker to be removed.** The remaining work is six
thirty-minute runs on a real public torrent with a reference client, on a machine behind a real
NAT — the owner's Windows box is where the symptom was seen, so it is where the runs belong. That
is not something to simulate and not something `swarm`, this repository's own test seeder, can
stand in for: it answers every dial immediately and has no address that refuses, which is the one
property the measurement is about.

**What the owner needs to do, in full:**

1. Pick one public torrent with a large swarm, and note what the tracker reports for it.
2. `git stash` or check out the commit before B-95 for runs 1–2, then this branch for runs 3–4.
3. For each run: start the client, find its process id, and
   `python3 scripts/peer_reach.py sample --pid <pid> --minutes 30 --out runs/<name>.csv --client "<what>" --torrent "<which>" --swarm <n>`.
4. Same for the reference client, twice, sequentially — **never at the same time as kachok**, for
   the reason in the item above.
5. `python3 scripts/peer_reach.py summarise runs/*.csv`, and paste the rows into D13 along with
   `dialsAttempted` / `dialsHandshaked` off the client's own state for its four runs.

**One thing this item was asked to do first and did not.** It said confirming the trackers' own
`numwant` default came first. That means announcing to public trackers to find out a number, which
is not something to do from a development machine for curiosity — it costs nothing to read off run
1's announce instead, and [B-97](B-97-the-announce-never-says-how-many-peers-it-wants.md) removed
the dependency on the answer by having the client name its own number.

The item stays `wip`. It is not blocked and not a question: the work is defined, the tools exist,
and it needs a swarm.

## Done 2026-09-17 — four runs, one mechanism, and the cap set at 250

The runs are in the research as D13. What they measured, in order of how much it mattered:

1. **`torrent.ubuntu.com` hands out one peer per announce**, at `numwant` unset, 50 and 200 alike,
   against a scrape reporting 526 in the swarm. On such a torrent a client with the DHT off has one
   address and nothing else in this stage can matter — which is
   [B-99](B-99-the-dht-is-off-and-its-reason-for-being-off-expired.md)'s whole argument, delivered
   as run 4's single-peer row, and the owner turned the DHT on the same day.
2. **[B-95](B-95-the-dial-loop-only-runs-when-something-else-happens.md) does what it was written
   to do**: median 12 to 23, distinct peers met 27 to 48, half the peak at 40 s instead of 102 s,
   and the fall-back-and-stay shape gone.
3. **The gap to the reference client was neither discovery nor the dial loop.** 1 059 addresses
   known, 4 423 dials, 303 handshakes, 22 held — which became
   [B-105](B-105-connections-are-made-and-not-kept.md), and B-105 turned out to be the whole of the
   remaining difference in throughput and most of it in peer count.

**The cap is now 250, and it is a decision rather than a number.** Fifty was the placeholder
`SessionConfig` opens with and it never bound — against a 526-peer swarm this client held 30, well
under it. Raising it would have been pointless *until* B-105, which derives the download window
from `maxPeers`: the cap is no longer only a ceiling on connections, it is the term that sizes the
window and therefore the throughput. The reference client held 183–195 on the same swarm.

**What 250 costs, and the flag that makes the cost stated rather than accidental.** The block pool
caps at `window x blocksPerPiece + maxPeers` — 4 250 buffers, 68 MB of *direct* memory. HotSpot's
default limit on direct memory is whatever `-Xmx` says, so until now the pool's real ceiling was a
side effect of a heap number chosen for an unrelated reason, and the failure mode would have been
`OutOfMemoryError: Direct buffer memory` under exactly the load the cap was raised to handle.
`-XX:MaxDirectMemorySize=192m` is now explicit in the headless flags and the desktop distribution.
The pool allocates lazily, so it is a limit and not a reservation.

**Four runs and not eight**, and the reason is in D13: the measuring instrument's host is the
owner's own connection and a reference client with no rate limit takes all of it. One sample per
variant is weaker than the design asked for; the differences it found are large enough to survive
that, and the one it could not have found without the counters — B-105 — was confirmed separately by
a fix that moved the number sevenfold.
