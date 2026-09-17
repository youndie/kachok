---
id: B-114
title: "Against the reference client this one downloads at half the rate or not at all: a peer that stops reading stops the whole session, and a lookup that finds nothing is kept for fifteen minutes"
status: done
priority: P1
size: M
stage: m9-swarm
epic: feature-download
---

# B-114 — Against the reference client this one downloads at half the rate or not at all: a peer that stops reading stops the whole session, and a lookup that finds nothing is kept for fifteen minutes

The owner asked for the download rate against the reference, and for the cause. Same public
torrent (`ubuntu-26.04-desktop-amd64.iso`, 6.5 GB, 2 211 seeds by the tracker's scrape), same
box, same link, one client at a time, upload capped at 800 KiB/s on both, download uncapped,
three minutes each, interleaved A/B so that a swarm which changes over ten minutes hits both.
qBittorrent 5.2.1 on Windows, driven through its WebUI; kachok as `download`, first on the build
machine (WSL 2, behind its NAT — nobody can dial it) and then on Windows beside the reference.

| run | client | where | 180 s | rate | what the line said |
|---|---|---|---|---|---|
| qbt A | qBittorrent 5.2.1 | Windows | 3.77 GB | **20.9 MB/s** | 65 seeds connected, 218 DHT nodes |
| qbt B | qBittorrent 5.2.1 | Windows | 3.27 GB | **18.1 MB/s** | 73 seeds, 246 DHT nodes |
| A | kachok | WSL | 1 piece | 0 | `1 of 1 peers` for three minutes |
| B | kachok | WSL | 1 piece | 0 | the same |
| C | kachok, `--down 1500` | WSL | 122 MiB / 120 s | 1.0 MiB/s | 487 known at 25 s, then 1 294; the cap |
| D | kachok | WSL | 245 MiB / 120 s | 2.0 MiB/s | 216 known, 5–7 connected of 474 dialled |
| E | kachok | Windows | 1.73 GiB | **10.2 MiB/s** | 158 connected, `window 250 @ 4.5 s`, 12.3 MiB/s in the steady stretch |
| F | kachok, `--pipeline 32` | Windows | 1.78 GiB | 10.6 MiB/s | 20 MiB/s for a minute, then **frozen from 115 s** with 152 requests out |
| G | kachok, window cap 1024 (trial) | Windows | 360 MiB | 2.0 MiB/s | **frozen from 60 s**: `144 unchoked, -39 out`, nothing moving |
| H | kachok, with this item's fix | Windows | 3.31 GiB | **19.7 MiB/s** | 23 MiB/s in the steady stretch, one peer cut as `not reading`, no freeze |

**Two defects, one per half of the table.**

**1. A peer that keeps the socket open and reads nothing stops the session.** `SocketPeerConnection`'s
writer is a blocking `socket.write`; its queue holds 64 messages; `send` was `outgoing.send`, which
*suspends* when the queue is full. So a peer that stops reading — dead, or merely slow, at 20 MiB/s
there is always one — fills the kernel's buffers, then the queue, and the next `send` to it
suspends the caller. The callers that matter are the loops over *every* peer: the timer's
keep-alives, the `have` broadcast after each verified piece, the choke pass, `ut_pex`, the
re-request after an expiry. The timer stops; with it stop request expiry, dialling, unchoking and
the counters. What that looks like from outside is runs F and G: a burst at the reference's rate,
then a session frozen with peers unchoked and requests outstanding for the rest of its life, the
`unchoked` figure never changing again because the thing that publishes it is the thing that is
stuck. `-39 out` is the same accounting after a choke zeroed a count that late blocks kept
decrementing. [B-19](B-19-end-to-end-download-acceptance.md) met this failure in the shape of a *closed* queue
and made `send` report it; the *full* queue is the same failure and was never handled.

**2. A DHT lookup that found nothing is kept for fifteen minutes.** Runs A and B: on the build
machine the first lookup — taken while two of the three bootstrap nodes were not answering this
address, checked by hand — found nothing, the tracker on this swarm hands out **one peer per
announce** whatever `numwant` says (checked by hand, three times, as D13 also found), and the next
lookup was `dhtInterval` away. One peer, no requests, three minutes, twice. Runs C and D, ten
minutes later with the bootstrap nodes back, found hundreds at 25 s and downloaded; the
difference between "stalled" and "working" was the luck of the first lookup.

**What is not a defect, and stays.** The rest of the gap between E and the reference is where
[B-105](B-105-connections-are-made-and-not-kept.md) said it is: `window 250 pieces @ 4.5 s` is
250 × 256 KiB / 4.5 s = 14 MiB/s, and E ran at 12. The window is at its cap and each piece takes
four seconds because its sixteen blocks go to one peer serving at 70 KB/s. Raising the cap (G) did
not help, because the freeze arrived first; it may help once nothing freezes, and that is a
measurement for after this item, not a change inside it. And half of D's gap is the build
machine's position: behind WSL 2's NAT it is dialled by nobody, and on this swarm the seeds do
the dialling — 158 connected on Windows against 7 on WSL with the same binary.

- **The decision and its reason.** A send to a peer never waits: `trySend`, and a full queue
  closes the connection and throws — sixty-four unread messages is not a slow peer but a dead
  one, and closing it is what frees the session. The session keeps the reason (`not reading`)
  so B-98's counters show it. And a lookup that leaves the client with fewer addresses than it
  could hold connections to is retaken after `dhtStarvedInterval` (30 s), doubling to
  `dhtInterval`; the announce keeps its own fifteen-minute clock. Two more bootstrap nodes, the
  two libtorrent ships beside the three here.
- Rejected: a timeout on the socket write. It bounds the writer, not the queue; the sender would
  still suspend for the timeout's length on every message to that peer.
- Rejected: dropping the message and keeping the peer. A peer that has not taken sixty-four
  messages will not take the sixty-fifth, and a `have` or `choke` silently dropped leaves the
  peer's picture of us wrong for the rest of the connection.
- Not covered: the window cap and the per-piece time, which bound the steady rate at about
  fourteen MiB/s on this torrent — a follow-up measurement with the freeze gone, filed if it
  shows the cap is now what is left.
- Not covered: a persistent DHT routing table across runs, which is how the reference client
  has 218 nodes ten seconds after start. Worth its own item if the retaken lookup is not enough.

- AC: `SocketPeerConnectionTest` shows a peer that reads nothing gets its connection closed and
  the sender an exception within seconds, never a wait; `SessionTest` shows the keep-alive tick
  survives such a peer, drops it under `not reading`, and reaches the next peer; `SessionTest`
  shows a lookup that leaves the client short is retaken after 30 s, then 60, and one that finds
  enough is kept for the full interval. Then the same three-minute run on Windows, against E's
  10.2 MiB/s and the reference's 18–21 MB/s, with no freeze.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerConnection.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt`,
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/io/SocketPeerConnectionTest.kt`,
  `engine/src/commonTest/kotlin/io/github/youndie/kachok/engine/session/SessionTest.kt`.

## Iteration 1 — 2026-09-18: the two fixes, and the run that closes the gap

**The queue.** `SocketPeerConnection.send` and `sendBlock` go through `trySend`; a full queue
closes the connection and throws `IOException("… stopped reading …")`, which `PeerLink.send`
keeps on the link so the teardown counts the peer under `not reading`. The block count a choke
zeroes no longer goes negative on the blocks that were already in flight.
`aPeerThatStopsReadingIsClosedRatherThanWaitedFor` parks a peer after the handshake and queues
megabyte frames at it: the sender gets the exception inside the timeout and the event stream
closes. `aPeerThatStoppedReadingIsDroppedAndCountedAndTheTimerGoesOn` does the session's half:
the keep-alive tick meets such a peer, drops it, counts it, and still reaches the next one.

**The lookup.** `SessionConfig.dhtStarvedInterval` (30 s); `dhtLoop` retakes a lookup after which
`known` is below `maxPeers`, doubling the wait up to `dhtInterval`, and announces on its own
fifteen-minute clock. Two tests on a one-node fake DHT count `get_peers` per lookup: short and
doubling when starving, the full interval when not. `dht.libtorrent.org` and `dht.aelitis.com`
join the bootstrap list.

**Run H, same conditions as E through G, the fixed binary:**

```
 60 s   3 247 pieces   178 of 1 341 peers   window 250 @ 2.2 s   not reading 1
150 s  11 650 pieces   127 of 1 672 peers   window 250 @ 2.6 s
172 s  13 579 pieces   3.31 GiB             19.7 MiB/s over the run, 23 MiB/s from 60 s to 150 s
```

Against E's 10.2 MiB/s and the reference's 18.1–20.9 MB/s in the same hour: the freeze is gone,
one peer was cut for not reading, and the rate is the reference's. The window sat at its cap of
250 the whole run at 2.2–2.6 s a piece — 25–29 MiB/s of headroom by B-105's arithmetic, which is
where the next ceiling is and why the cap is left alone here.

**What one run does not say.** One run each of E and H, in different minutes of a swarm that
changes; the reference's two runs differed by 15 % between themselves. That H is twice E and
level with the reference is beyond that noise; that it is 19.7 rather than 18 or 23 is not.
