---
id: B-125
title: "A speed comparison needs a harness that cannot publish a lonely number"
status: open
priority: P2
size: M
stage: m7-measure
blocked_by: [B-123]
---

# B-125 — A measurement that is a pair, or it is not published

The owner wants to compare variants by speed — sequential against rarest-first, encrypted against
plaintext, one pipeline depth against another. Every one of those questions has the same two traps,
and this repository has fallen into both before: a **single run** of each variant decides nothing
(the same variant gave 1.14 and 2.42 back to back), and an **absolute number** does not survive the
week it was taken in, let alone the machine.

`CollectorBench` already got the shape right for G1 against ZGC and said why it is not in `build`:
*"it takes minutes, it measures this machine as much as this code, and a number produced on a shared
CI runner would be worse than no number."* What is missing is that shape as something reusable, for
any pair of options, with the swarm of [B-123](B-123-a-seed-that-holds-part-of-the-torrent.md)
underneath it so that the thing being compared can actually differ.

- **The decision and its reason.** A scenario is a value — swarm shape, torrent shape, client
  options — and it runs in two modes from one definition: `verify` asserts behaviour and is
  deterministic and belongs in `build`; `measure` takes a **pair** of variants, runs them
  **interleaved in one session** for N repetitions, discards the first of each as warm-up, and
  reports the ratio with its spread. The interleaving is the whole design: a machine that gets slower
  during the run then slows both variants, and a ratio taken across two sessions is the only kind
  this repository has ever been wrong about.
- **A lonely absolute cannot be printed.** The report gives `variant / control` with the spread, and
  the absolutes are carried as context — machine, date, commit — rather than as the result. A ratio
  whose spread crosses 1.0 is printed as "no difference this stand can see", not as a percentage.
- **Where it runs, and this is the answer to "как их гонять".** `verify` on GitHub, because a
  loopback swarm is an ordinary network test and no scenario needs the public one. `measure` never
  on a shared runner — one machine, one commit, both variants in the same session. The public swarm
  is neither: it is taken by hand, rarely, and recorded in the research with its date and conditions.
- The alternative that was rejected: a threshold in seconds in `build`. That is a gate that measures
  the runner, goes red on a noisy Tuesday, and gets raised until it asserts nothing.
- Not covered: a regression watch over time — comparing today's ratio to last month's. That needs a
  stable stand and a stored series, and the first thing to find out is whether this stand is stable
  enough to carry one.

- AC: `./gradlew :swarm:measure -Pscenario=picker-order` (or the equivalent this item settles on)
  runs both variants interleaved and prints a ratio with its spread and the machine it was taken on;
  the same scenario file runs as an assertion in `build` and cannot print a timing there; the first
  real answer — what asking for both ends of a file first costs on a swarm where pieces are rare —
  is written into the research beside the rarest-first numbers.
- Anchors: `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/` (target),
  `cli/src/test/kotlin/io/github/youndie/kachok/cli/CollectorBench.kt`,
  `docs/research/research-architecture.md`.
