---
id: B-127
title: "Four clients on one seed trade 2% of the data, because almost nothing is ever unchoked"
status: open
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

- AC: the ratio of what a client gives to what it takes, against how long it stays, measured on the
  four-client stand at a minimum of three durations, written into the research; and a statement —
  with its evidence — of whether a short-lived client is a free rider by construction.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/measure/Scenario.kt`,
  `docs/research/research-architecture.md`.
