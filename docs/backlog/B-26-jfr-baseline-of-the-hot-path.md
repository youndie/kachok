---
id: B-26
title: "A JFR baseline: allocations on the hot path, pinned threads, carrier count"
status: done
priority: P1
size: M
stage: m7-measure
blocked_by: [B-19]
---

# B-26 — A JFR baseline: allocations on the hot path, pinned threads, carrier count

The brief promises "zero garbage on the hot path". That is a claim about a profile, and the
profile has to exist. This item also settles the buffer-pool cap and the `force()` interval defaults.

- **The decision and its reason.** `-XX:StartFlightRecording` on the
  [B-19](B-19-end-to-end-download-acceptance.md) download; the allocation profile by stack, the
  `jdk.VirtualThreadPinned` events, the platform thread count over time. Any allocation attributed
  to the `piece` path is a bug; any pinned event in the transport is a bug. The numbers go into
  the research with the torrent and machine named ([../research/research-architecture.md](../research/research-architecture.md) Risk 1, Risk 2).
- Rejected: an external profiler. JFR is in the run-time image and in every CI run.
- Not covered: micro-benchmarks. A JMH suite is worth having once there is a number to protect.

- AC **met 2026-09-05** (research §1.2c): the research §1 gains a "measured" subsection with the allocation rate, the pinned event
  count and the carrier peak; the pool cap and `force()` interval constants cite it.
- Anchors: `docs/research/research-architecture.md`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/`.

**Closed 2026-09-05.** Two profiled runs against the Debian swarm — one to completion, one of 449
pieces stopped deliberately — and they agree, which is the only reason to believe either. The
table is at research §1.2c.

The brief's claim was "zero garbage on the hot path". The profile says the hot path allocates
nothing at all: not one sample in the reader, the pool or the writer's block handling. What it does
allocate is `java.lang.Integer` in `PiecePicker.rarestUnstarted` — boxing, from a `List<Int>` of
candidate pieces built on every request. The profile named the mechanism, not just the method
([B-43](B-43-picker-allocates-per-decision.md)).

**Two defects the measurement found, neither of which any test would have:**

* **The pool cap formula was wrong.** `… + pipelineDepth` reads as "one peer's worth of reads in
  progress", and there is not one peer: a block occupies a buffer from the moment its read begins,
  so the term is bounded by the number of peers. Measured at 117 of 144 — 81 % of a cap a faster
  link would have hit, throttling the download silently. Now `… + maxPeers`.
* **The listener did not set `SO_REUSEADDR`.** A port this client used a minute ago cannot be taken
  again while its old connections sit in `TIME_WAIT`, so a restarted client would skip its own port
  and announce a different one. Found because a test suite re-run hit it; the same window is two
  minutes wide for a user restarting a client.

The `force()` interval needed no change and now says why: writes are already rare because a piece is
one gathering write — twenty sampled events against 449 pieces — so the flush is not what costs
anything. What it bounds is how much of the page cache a crash can take, which the resume record
turns into a re-hash.

**A measurement note worth keeping.** `SIGINT` sent to a process this harness started in the
background does nothing: a shell puts a backgrounded command's `SIGINT` disposition to *ignored*,
and the JVM does not install its handler over that. It looked exactly like a client that would not
stop. `SIGTERM` runs the same hook and stopped it in three seconds.
