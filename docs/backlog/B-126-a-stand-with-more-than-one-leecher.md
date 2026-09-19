---
id: B-126
title: "The swarm cost of a picker cannot appear on a stand with one leecher"
status: open
priority: P3
size: L
stage: m7-measure
blocked_by: []
---

# B-126 — A stand with more than one leecher

[B-125](B-125-a-measurement-that-is-a-pair.md)'s first comparison found no difference between
rarest-first and in-order — believably, because its positive control saw a 2.14× difference on the
same stand in the same session. But the null is narrower than the question:
[B-65](B-65-sequential-download.md)'s claim was never about this client's own download time. It was
about everybody else:

> every peer asks for piece 0 first, nobody has anything rare to trade, and the client that does it
> finishes last

That is an **externality**: a cost a client imposes on a swarm. On a stand with one leecher and five
seeds that hold from the first second what they will hold at the last, there is nothing for it to be
imposed on — no peer's download is affected by what this one asks for, and no trade happens at all.
Five seeds at 256 KiB/s is also a hard cap of 1.25 MiB/s, which both variants hit, so the stand was
measuring its own rate limiter for the second half of every run.

- **The decision this needs.** A stand of *N leechers and one seed that is not enough on its own* —
  the shape a real swarm has in its first minutes. What is then measurable is the thing B-65 claimed:
  how long the *whole set* takes to finish, and how much the in-order client got from its peers
  rather than from the seed. One client's own time is the wrong dependent variable and always was.
- The alternative that was rejected: more seeds, more pieces, more bandwidth. That makes the
  existing stand bigger, not different, and a bigger stand with no trade in it answers the same
  narrow question at greater cost.
- **What it will cost, honestly.** N clients in one JVM, each with its own `TorrentSet`, storage
  directory and port; the run is over when the last of them finishes, and the measurement is the
  spread between them as much as the total. The existing harness takes it from there — interleaved
  pairs, ratio with a range, no lonely absolutes — so this item is the stand and not the statistics.
- Not covered: choking strategy as a variable. A swarm of leechers is exactly the stand on which
  tit-for-tat becomes measurable, and that is worth its own item once this one exists.

- AC: a scenario of the catalogue runs N leechers against a seed that cannot serve them all, reports
  when the last one finished, and the in-order variant's swarm cost — B-65's claim — is written into
  the research as a number with its range, or recorded as still unmeasurable with the reason.
- Anchors: `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/measure/Scenario.kt`,
  `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/measure/Measure.kt`,
  `docs/research/research-architecture.md`.
