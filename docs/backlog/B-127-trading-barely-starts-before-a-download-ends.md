---
id: B-127
title: "Four clients on one seed trade 2% of the data, because almost nothing is ever unchoked"
status: done
priority: P3
size: M
stage: m9-swarm
blocked_by: []
---

# B-127 — Trading barely starts before the download ends

[B-126](B-126-a-stand-with-more-than-one-leecher.md)'s measurement answered what it was built for
and left a larger number lying beside it. On a stand of four clients and one seed whose uplink is
the bottleneck, the **best** of the two pickers moved **1.9 %** of the data between the clients; the
other moved none at all. The seed served 2 512 blocks of a possible 2 560 — it was doing 98 % of the
work in a swarm expressly built so that it could not.

The reason is not the picker and is visible in the timings: connections start choked,
`chokeInterval` is ten seconds and `optimisticInterval` thirty, so a forty-second swarm gets about
four choke passes and one optimistic rotation. Tit-for-tat has nothing to reciprocate at the start —
nobody has given anybody anything — so the only thing that can begin a trade is the optimistic slot,
once every thirty seconds, per client.

**This is not obviously a defect**, and that is exactly why it is worth an item rather than a fix: a
real swarm runs for minutes or hours and those constants are the ones every client uses. What is not
known is whether a client that joins a swarm, takes its copy in forty seconds and leaves has
contributed anything at all — and on this evidence it has not.

- **The question.** How long does a kachok client have to be in a swarm before it is giving as much
  as it takes, and is the answer minutes or hours? A ratio of uploaded to downloaded against time in
  the swarm, taken on the B-126 stand at several durations.
- The obvious change and why it is not the item: shortening the choke interval. A constant chosen
  because BEP 3 suggests it and every other client uses it is not a number to change because one
  stand made it visible, and a client that unchokes faster than its peers is a client that gives
  more than it gets by construction. The measurement comes first.
- Not covered: super-seeding, and any change to the choker itself. Both are decisions that need this
  number before they need anything else.

## The answer, and the hypothesis this item was written on is wrong

Same stand, three durations — which on this stand means three torrent sizes, because the only way to
keep four clients in a swarm for longer is to give them more to download. Two runs each:

| torrent | in the swarm | gave / took | from the seed |
|---|---|---|---|
| 10 MiB | 39.3 s | 1.9 % | 39.2 of 40.0 MiB |
| 30 MiB | 119.2 s | 0.6 % | 119.2 of 120.0 MiB |
| 60 MiB | 239.2 s | 0.3 % | 239.2 of 240.0 MiB |

**The share falls as the swarm lasts longer and the absolute never moves: 0.8 MiB traded, every
time.** Trading is not slow to start here. It happens once, at the beginning, and then stops — so
the choker, which this item suspected, is innocent, and duration was the wrong variable.

**The cause is the tie-break.** Rarest-first compares with `availableFrom < rarest`, strictly less,
so among equally rare pieces the lowest index wins. On a fresh swarm around one seed every piece
nobody holds has availability 1, so every client resolves every tie identically and asks for the
same piece next. Four clients that always want the same piece stay in lock step, and a swarm in lock
step has nothing to trade.

An experimental build differing in that one line — a reservoir sample among the equally rare, the
technique the picker already uses for the first piece — on the same stand, the same machine:

| torrent | tie-break | in the swarm | gave / took |
|---|---|---|---|
| 10 MiB | lowest index | 39.3, 39.2 s | 1.9 % |
| 10 MiB | random among the rarest | **13.1, 12.6 s** | **68.8 %, 68.6 %** |
| 30 MiB | lowest index | 119.2, 119.2 s | 0.6 % |
| 30 MiB | random among the rarest | **34.2, 33.4 s** | **71.7 %, 72.2 %** |

- **The statement this item owes, with its evidence: a short-lived client is not a free rider
  because it is short-lived.** This client is a free rider at *every* duration, because its picker
  keeps it in step with its peers — and it stops being one the moment the tie is broken at random,
  giving seventy per cent of what it takes and finishing the whole swarm three times faster.
- The change is not folded in here. A measurement and a change to the download path of every torrent
  are two things to review, and the measurement is what makes the change arguable:
  [B-128](B-128-ties-among-equally-rare-pieces.md).
- Found on the way: in the high-trading runs the four clients recorded taking 40.9 MiB of a 40.0 MiB
  torrent, which a counter that rises per verified piece can only do if a piece was verified twice —
  [B-129](B-129-a-piece-can-be-verified-twice.md).

- AC: the ratio of what a client gives to what it takes, against how long it stays, measured on the
  four-client stand at a minimum of three durations, written into the research; and a statement —
  with its evidence — of whether a short-lived client is a free rider by construction. **Both met**,
  and the answer is the opposite of what the item assumed.
  **Automated:** `swarm/src/test/.../measure/ScenariosTest.kt` — `aScaledStandIsTheSameSwarmForLonger`,
  `theContributionOfASwarmOfOneIsRefused`, `theContributionCurveMeasuresWhatTheSwarmGaveItself`.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/measure/Scenario.kt`,
  `docs/research/research-architecture.md`.
