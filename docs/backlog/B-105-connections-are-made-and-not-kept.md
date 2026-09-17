---
id: B-105
title: "Three hundred handshakes, twenty-two peers held — and the client asks nineteen of them for nothing"
status: done
priority: P0
size: M
stage: m9-swarm
epic: feature-download
blocked_by: []
---

# B-105 — Three hundred handshakes, twenty-two peers held — and the client asks nineteen of them for nothing

Measured on `ubuntu-26.04-desktop-amd64.iso` (526 in the swarm by the tracker's scrape), twenty
minutes, DHT on, this branch's code. The client's own counters at the end of the run:

```
1709/24868 pieces (6%), 22 of 1059 peers (19 unchoked, 14 out),
dials 303/4423 (connect timed out 2856, refused 850, closed during the handshake 349,
                reset 29, other 7, handshake timed out 6)
```

Read across, that is four facts and the last two are the item:

1. **Finding peers is not the problem.** 1 059 addresses known. The DHT works.
2. **Reaching them is a problem with a known name.** 4 423 dials, 303 handshakes — 6.9 %. Of the
   failures, 2 856 are `connect timed out`: peers behind a NAT that an outgoing connection cannot
   reach at all, and that could have reached *us*. That is
   [B-103](B-103-upnp-and-nat-pmp-port-mapping.md) and is already written down.
3. **Three hundred handshakes succeeded and twenty-two peers are held.** `maxPeers` is 50 and is
   not binding at 22, so nothing is refusing these connections — roughly 280 of them ended during
   the run. Connections are made and not kept, and nothing in the client counts why.
4. **Nineteen peers had unchoked this client and it had fourteen requests outstanding in total.**
   `pipelineDepth` is 16 *per peer*; nineteen unchoked peers is room for around three hundred. The
   same run before this stage's changes held 8 peers, 7 of them unchoking, and had **45**
   outstanding — fewer peers, three times the work in flight, and 61 % of the file downloaded
   against 6 %.

**The hypothesis this item exists to test, and it joins 3 and 4 into one mechanism.** A peer that is
asked for nothing is a peer with no reason to keep the connection: a seed with five hundred
leechers to choose from drops the one that is not pulling. If the request pipeline is starving,
then the connections dying and the download crawling are the same defect seen from two sides, and
the peer count is a symptom rather than the disease.

What the pipeline could be starving on, in the order they are worth eliminating:

- `maxStartedPieces = 8` bounds the whole session to eight pieces in flight — 128 blocks at this
  torrent's piece length — however many peers are connected. It was chosen with the buffer pool
  and has never been looked at against a peer count; with 22 peers it is six blocks each, and the
  observed fourteen is far below even that, so it is a ceiling and not the whole answer.
- `requestMore` is driven by block arrival. A pipeline that has emptied has nothing to refill it
  except the next block, which is the same circle `refillRateLimits` was written to break for rate
  limits — and that path returns immediately when there is no limit set, which is the default.
- This stage's own changes are a suspect and must be cleared rather than assumed innocent. The
  session confines its state to one thread by design; after
  [B-95](B-95-the-dial-loop-only-runs-when-something-else-happens.md) that thread also runs a dial
  pass every tick and, after [B-98](B-98-how-many-peers-does-this-client-meet.md), publishes a new
  state with a copied failure map on every dial that fails — 4 120 times in twenty minutes. The
  before/after pair above is one run each and cannot tell a regression from a swarm that changed.

- **The first thing to build is not a fix.** Disconnections are counted nowhere: `dialFailures`
  buckets dials that never became connections and says nothing about connections that ended. Add
  the mirror — how many connections ended, and why, by the same closed-label rule — and the guessing
  above becomes a reading. It is the same instrument as B-98's and it is what makes the rest of this
  item decidable.
- Rejected: raising `maxPeers`. The cap is not binding at 22 and a client that cannot keep the
  connections it has does not want more of them.
- Rejected: treating the throughput number as the goal. A client that downloads faster while
  holding eight peers is what this stage set out to change; the two have to be read together, and
  the acceptance below asks for both.
- Not covered: choking and the upload side. This client is a leecher throughout the measurement and
  what it serves is not what these peers are deciding on.
- Not covered: the reference client's own figures. qBittorrent held 183–195 peers in the same
  swarm, which is what makes 22 worth chasing, but its internals are not the subject.

- AC: a twenty-minute run on the same torrent with the DHT on reports why connections ended, in
  buckets; the client holds a number of peers that does not fall while `maxPeers` is unreached; and
  outstanding requests scale with unchoked peers rather than sitting an order of magnitude below
  them. Both figures are recorded in the research beside the run that produced them.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/SessionState.kt`.

**Done 2026-09-17.** The instruments were built first, as the item said, and they answered both
questions in one eight-minute run before a line of the fix was written:

```
15%, 13 of 967 peers (9 unchoked, 32 out), window 8 pieces @ 959ms,
lost 44 (peer closed, never asked 40, peer closed 4)
```

`window 8 pieces @ 959ms` is 8 x 256 KiB / 0.959 s = **2.13 MB/s**, and the run downloaded at
**2.03 MB/s**. The measured throughput *was* the window: `maxStartedPieces` was a constant 8, a
piece holds its picker slot from its first requested block until the writer has hashed it, and no
number of peers can widen that. And `never asked 40` is the same fact seen from the peers' side —
with the window full the picker hands out nothing, most connected peers are asked for nothing, and
a peer that is asked for nothing leaves.

**So it was one defect and not two, and it explains the thing that looked like a regression.**
[B-95](B-95-the-dial-loop-only-runs-when-something-else-happens.md) brought more peers; each one's
share of an unchanged window was smaller; the client held three times the peers and downloaded a
tenth as much. Nothing was wrong with the dial loop.

The window is now derived rather than constant: `maxPeers x pipelineDepth` blocks, divided by the
blocks a piece holds, floored at the old 8 and capped at 256. It is a number of blocks and only
incidentally a number of pieces — what has to fit is every peer's pipeline, and the same fifty
peers need fifty slots on a 256 KiB piece and four on a 4 MiB one. The cap is there because every
started piece is a partially written one and a client that opens thousands turns one sequential
write into a scattered many. The buffer pool is sized from the same figure and peaked at 772 of
850, so it is neither starving nor wasteful.

The confirming run, same torrent, same eight minutes, same machine:

| | before | after |
|---|---|---|
| window | 8 pieces @ 959 ms | 50 pieces @ 657 ms |
| requests outstanding | 32–57 | 258–279 |
| peers held, median | 17 | 30 |
| unchoked / connected | 9 of 13 | 27 of 28 |
| downloaded in eight minutes | 15 % | **100 %** — the torrent finished |
| lost, never asked | 40 of 44 | 17 of 19 |

2.03 MB/s to roughly 13.9 MB/s, and the peer count went up with it because peers now have a reason
to stay. Both figures moved together, which is what the acceptance criterion asked for and the
reason it asked for both.

**What is not fixed and is not this item.** `peer closed, never asked` is still 17 of 19, on a run
that finished and then had nothing to ask anyone for — a completed torrent sheds peers, so that
tail is expected here and would need a run that does not complete to read properly. The dial
success rate is unchanged at under 5 %, because 608 of 1 007 dials still time out against peers
behind a NAT: that is [B-103](B-103-upnp-and-nat-pmp-port-mapping.md) and nothing here touches it.

Three tests on the rule itself, in `DownloadWindowTest`: fifty peers of sixteen requests get fifty
slots on a 256 KiB piece, the same peers get the floor on a 4 MiB one, and the window grows with
the peer count and stops at the cap.
