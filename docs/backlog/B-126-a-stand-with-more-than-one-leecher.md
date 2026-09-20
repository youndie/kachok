---
id: B-126
title: "The swarm cost of a picker cannot appear on a stand with one leecher"
status: done
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

## The answer, and the two stands that had to be thrown away to get it

Four clients, one seed at 1 MiB/s, 10 MiB in 64 KiB pieces; four rounds, interleaved:

| | makespan | blocks from the seed |
|---|---|---|
| rarest-first | 39 250, 39 250, 39 256 ms | 2 512, 2 513, 2 512 |
| sequential | 39 997, 39 993, 40 003 ms | **2 560, 2 560, 2 560** |

**2 560 is exactly four whole copies.** Four clients asking in order took every block from the seed
and gave each other nothing, in every run. Rarest-first traded 48 blocks between them — and that is
the entire difference: 48 blocks is 768 KiB, which at 1 MiB/s is 0.75 s, and the makespans differ by
0.747 s. B-65's sentence is confirmed in direction and in mechanism, and the number is small for a
reason that belongs to the stand: forty seconds is four choke passes, so trading hardly begins for
either order. That floor is now its own item, [B-127](B-127-trading-barely-starts-before-a-download-ends.md).

**Neither of the first two stands asked the question, and both reported a confident 1.00.**

* The seed's rate was **per connection**, so one seed at "one client's worth of bandwidth" gave each
  of four clients that bandwidth in full. Nobody had to trade; four downloads finished in the time
  one of them would have taken. The rate is now a shared budget, like an uplink.
* The run then lasted **four seconds**, which is shorter than the choker's ten-second pass, so no
  peer was ever unchoked by any other and every client again took its whole copy from the seed. The
  stand is now sized to outlast the mechanism it depends on, and `build` runs a scaled-down copy of
  it, because what `build` needs to know is that the stand works and not that it is long enough.

Both were found the same way: **by looking at where the bytes came from rather than at the clock.**
The report now prints the seed's own block count beside every median, and refuses to publish a ratio
at all when both sides took everything from the seed — a null result and a stand that asked nothing
are indistinguishable from the timings alone.

- AC: a scenario of the catalogue runs N leechers against a seed that cannot serve them all, reports
  when the last one finished, and the in-order variant's swarm cost — B-65's claim — is written into
  the research as a number with its range. **Met**; the number is in §1.2c5 with its mechanism, its
  range and the reason its magnitude belongs to this stand's duration.
  **Automated:** `swarm/src/test/.../measure/ScenariosTest.kt` — `everyScenarioStillPosesItsQuestion`,
  `everyStandCanMakeAPieceRare`, `aRunInWhichNobodyTradedIsNotReportedAsAComparison`,
  `aSideThatTradedIsTheAnswerRatherThanAFault`.
- Anchors: `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/measure/Scenario.kt`,
  `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/measure/Measure.kt`,
  `docs/research/research-architecture.md`.
