---
id: B-94
title: "A degraded session cannot recover, and the DHT table was the thing degrading it"
status: done
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-94 — A degraded session cannot recover, and the DHT table was the thing degrading it

Reported from a running client on 2026-09-07: after
`One session is degraded. dht lookup: ConcurrentModificationException` there was nothing left to do
with the torrent but remove it.

**Two defects, and the second is the worse one.** The exception is a real race and it is fixed. But
the loop that threw it had already recovered — `tick` catches, records and carries on, a second
later it works again — and the *mark* was what never came off. A torrent that was downloading
normally showed `Error`, kept its banner, and offered no way back: pause, resume and re-check all
leave `sessionError` alone, and nothing else ever set it to null. The client was fine and said it
was broken, permanently, over one bad pass.

## The race

There is one `Dht` for a whole `TorrentSet` — one routing table, one socket — and **every torrent
runs its own lookup loop on its own `limitedParallelism(1)` dispatcher**. Confinement is per
session, so two torrents with the DHT on walk the same table at the same time. `RoutingTable` held
each bucket as a `mutableListOf`, and `closest` iterated one while another lookup's `seen` appended
to it.

**A bucket is replaced now, never edited.** A lock would have worked and would have been the third
concurrency mechanism in an engine that has two; whole-list replacement means a reader always walks
a list nobody can touch. What a race can still cost is one *lost* update — two writers to a bucket
both build from what they read and the second wins — and a lost `seen` is a node the table forgets
it met and meets again within the minute. `Entry.failures` became a `val` for the same reason: it
was mutated in place through a reference a reader might be holding.

Reproduced before it was fixed: three writing threads and three reading ones over one table throw
`ConcurrentModificationException` on the old code and do not on the new. A JVM test, because
coroutines on one thread interleave only at suspension points and these methods do not suspend —
the engine's single-threaded test dispatcher could never have found this.

## The mark that would not come off

`tick` is for *periodic* work: the loop that runs it survives a failure and runs it again. So it
clears the error it set, when the same job succeeds — **its own only**, matched by prefix, because a
`dht lookup` that starts answering must not rub out a `flush` that is still losing data.

`launchGuarded` is left exactly as it was, and the asymmetry is the point: it wraps a whole loop, so
an exception out of one means that loop has stopped for good. A dead writer does not get to look
healthy again.

- AC: two torrents with the DHT on do not throw out of a lookup; a periodic failure that stops
  happening stops being reported, and one that is still happening keeps being.
- Anchors: [`engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/dht/RoutingTable.kt`](../../engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/dht/RoutingTable.kt),
  [`engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`](../../engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt).

**Automated:** `engine/src/jvmTest/.../dht/RoutingTableRaceTest.kt` — six threads over one table, and
the invariant a lost update may not break: no node held twice · `engine/src/commonTest/.../session/SessionTest.kt`
— a pass that fails and then works clears its own mark, and another job's success does not clear it.
Both directions were checked by breaking the fix: with no clearing both fail, and with indiscriminate
clearing both fail.
