---
id: B-141
title: "The DHT table fills near its own id, a resumed torrent looks at once, and a lookup ends at the closest eight"
status: done
priority: P2
size: S
stage: phase-3-server
blocked_by: []
---

# B-141 — The DHT table fills, and a resume looks at once

Found while measuring B-139 on a 4K film: after a restart `network_status` (B-140) reported 31–32
DHT nodes for ten minutes, and the resumed torrent held the trackers' 33 addresses while the DHT
had five times as many. Reported as [youndie/kachok#80](https://github.com/youndie/kachok/issues/80).

Measured with `./gradlew :engine:dhtProbe -PinfoHash=…` on the public network, before:

```
bootstrap: 18 nodes, table 18, 5124 ms
lookup 1: 172 peers, 19 tokens, table 25, 21086 ms
lookup 2: 176 peers, 21 tokens, table 28, 16280 ms
lookup 3: 172 peers, 21 tokens, table 34, 17194 ms
lookup of own id: 12 tokens, table 105, 30652 ms
```

Three faults, none of them in the lookup itself:

- **The table never walked towards its own id.** The bootstrap asked the hosts `find_node(self)`
  once and kept what they named. Lookups for info hashes only meet nodes far from this one's id,
  which land in the first few buckets — eight each — so the table stalled at about thirty. BEP 5
  has a joining node look itself up, and refresh a bucket after fifteen idle minutes.
- **A torrent opened paused slept through its first fifteen minutes after a resume.** `dhtLoop`
  waited `dhtInterval` while paused and `resume()` did not wake it, nor the wait after a lookup.
- **A lookup never ended early.** Its exit was `return@repeat`, which inside `repeat` is a
  `continue`, so every lookup ran all eight rounds.

- **The decision and its reason.** `Dht.refresh` is an iterative `find_node` towards the node's
  own id; `refreshIfStale` repeats it after `REFRESH_INTERVAL` (15 min). The session runs it
  *beside* the first lookup, not before: the walk took 30–40 s on the public network against 5 s
  for the bootstrap, and a new torrent's first peers must not wait for it. The time is marked when
  a walk starts, so the torrents of one set starting together walk once between them.
  A resume sends on a conflated `dhtWake` channel that both of the loop's waits listen to.
- **The lookup ends at Kademlia's rule, not the one its comment named.** Making the old exit
  work — "a round learned nothing closer and there are peers" — was tried and measured: it ended
  before the closest nodes, with 120 peers and 12 tokens where the full walk had 172 and 19, and
  the tokens are what the announce goes to. It ends now when the eight closest known nodes have
  all been asked, and a node that does not answer gives up its place among them.

After, the same probe:

```
bootstrap: 17 nodes, table 17, 5101 ms
walk towards own id: 93 nodes, table 93, 30315 ms
lookup 1: 178 peers, 16 tokens, table 96, 15830 ms
lookup 2: 181 peers, 17 tokens, table 104, 11309 ms
lookup 3: 176 peers, 16 tokens, table 108, 15849 ms
```

Lookup times on the public network vary between runs (4–26 s seen); the numbers above are one run
each, not a distribution.

- Not covered: the transport counts the queries other nodes send and answers none of them, so this
  client is a DHT client and not a node others can keep in their tables. Its own item.

- AC: a walk towards the node's own id meets the nodes near it and is repeated after fifteen
  minutes; resuming a torrent looks in the DHT at once; a lookup asks no more than the eight
  closest nodes it knows. **Met.**
  **Automated:** `engine/src/commonTest/.../dht/DhtTest.kt` —
  `theWalkTowardsItsOwnIdMeetsTheNodesNearIt`, `aStaleWalkIsWalkedAgain`,
  `aLookupEndsWhenTheClosestEightHaveBeenAsked`; `engine/src/commonTest/.../session/SessionTest.kt` —
  `aResumedTorrentLooksInTheDhtAtOnce`.
  **Manual:** `./gradlew :engine:dhtProbe -PinfoHash=<hash>` — table size after the walk, peers
  and time per lookup.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/dht/Dht.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/dht/DhtProbe.kt`.
