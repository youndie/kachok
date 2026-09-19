---
id: B-118
title: "A peer that never answers is redialled every thirty seconds for the life of the torrent"
status: done
priority: P1
size: S
stage: m9-swarm
epic: feature-download
blocked_by: []
---

# B-118 — A peer that never answers is redialled every thirty seconds for the life of the torrent

`connectMore` skips an address while `failed[address].elapsedNow() < config.reconnectDelay`, and
`reconnectDelay` is a flat thirty seconds whatever the address has done before. Nothing counts
consecutive failures and nothing ever gives up: an address enters `known` from one announce and
stays there for the life of the session, so every thirty seconds, for as long as the torrent is
loaded, it is dialled again. Since [B-95](B-95-the-dial-loop-only-runs-when-something-else-happens.md)
the timer dials every tick, which is what turned a slow leak into a steady one — the top-up loop
that item added is correct, and this is the schedule it now runs on.

The cost is not hypothetical and it is not small. Research
[D14](../research/research-architecture.md#d14-µtp-is-deferred-and-the-number-that-would-change-that-is-not-the-obvious-one)
already records that **2 856 of 4 423 dials end in `connect timed out`, 65 %**, and explains why:
those are peers behind a NAT with nothing forwarded, which an outgoing TCP connection cannot reach
*and will not be able to reach on the next attempt either*. The client rediscovers this about the
same addresses twice a minute, each attempt holding a socket for the ten-second connect timeout.

Measured on the author's machine, three seeding torrents idle at 0 B/s with `maxPeers = 250`:

| | |
|---|---|
| sockets held by the process | 726 |
| of them in `SYN_SENT` | 342 |
| host-wide outgoing connection attempts, 14.8 h uptime | 1 880 068 |
| of them failed | 1 586 520 (84 %) |
| sustained rate | ~37 failed connections a second |

Three sessions × 250 slots is 750, and 726 sockets is that cap with the dial budget spent almost
entirely on addresses that have never completed a handshake. The torrents were **seeding and
complete** — the client had nothing to gain from any of it. A consumer router's NAT table is one
to four thousand entries, so this is also a client that degrades the network it runs on: the
symptom its owner reported was "the network is slow again", with new connections from other
programs stalling while established transfers ran at full speed.

- **The decision: the wait doubles per consecutive failed dial, to a ceiling.** `reconnectDelay`
  becomes the *first* wait rather than the only one, and `SessionConfig.maxReconnectDelay` (30 min)
  is where the doubling stops. Thirty minutes because a dead address should cost about what an
  announce costs, and the announce is the thing that would tell us a peer came back. At the default
  delay the ceiling is reached on the seventh consecutive failure.
- **The streak belongs to the address, and a handshake ends it.** Only a *dial* failure counts. A
  peer that answered and later hung up gets the flat delay as before — it is reachable, whatever it
  did next — and an address that was dark for hours before opening its port starts again from
  thirty seconds rather than inheriting the backoff its silence earned.
- **Rejected: dropping an address after N failures.** It reads as the stronger fix and is the
  weaker one. The tracker hands the same addresses out every announce, so a dropped address returns
  within the interval with its history erased — the treadmill at 1 800 s instead of 30 s, plus the
  loss of the one piece of state that would have throttled it. Backoff keeps the memory and
  removes the traffic.
- **Rejected: lowering `maxPeers`.** The cap is not the problem and it is load-bearing elsewhere:
  [B-98](B-98-how-many-peers-does-this-client-meet.md) measured it to 250 and derives the download
  window from it, so lowering it costs throughput on swarms that *do* answer.
- **Not covered: `known` grows without bound.** Every address any announce ever returned is kept
  for the life of the session, and `failed` grows with it. At a few thousand entries of two small
  objects this is not what hurts, and evicting from `known` needs a policy about what a peer source
  is worth — its own item if a long-running session is ever measured holding a large one.
- **Not covered: a complete torrent dials as hard as an incomplete one.** A seed has a reason to
  dial — leechers that cannot accept — but not the same reason, and `maxPeers` does not distinguish.
  That is a question about the dial budget rather than about this schedule.

- AC: with a dialer that refuses one address, a session on a virtual clock dials it 7 times in an
  hour — at 0, 30, 90, 210, 450, 930 and 1 890 s — where the flat delay dialled it 121 times.
- AC: an address refused three times and then answered is redialled 30 s after that connection
  ends, not the 240 s its streak had reached.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/SessionState.kt`,
  `engine/src/commonTest/kotlin/io/github/youndie/kachok/engine/session/SessionTest.kt`
