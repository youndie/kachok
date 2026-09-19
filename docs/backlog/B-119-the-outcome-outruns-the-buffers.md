---
id: B-119
title: "A piece's outcome is published before its buffers are back, and CI fails on it about once in a hundred runs"
status: done
priority: P1
size: XS
stage: m3-storage
epic: feature-download
blocked_by: []
---

# B-119 — A piece's outcome is published before its buffers are back

`BlockWriter.complete` sends the outcome inside the `try` and releases the piece's blocks in the
`finally` that follows:

```kotlin
storage.write(piece, blocks)
results.send(PieceOutcome.Verified(piece))
} finally {
    blocks.forEach { it.release() }
}
```

A reader woken by that outcome therefore races the writer's own `finally`. `BlockWriterTest`'s
`aDuplicateBlockIsReleasedRatherThanCounted` asserts `pool.outstanding == 0` the moment the outcome
arrives, and lost the race on CI in the `-Pkachok.release` build of
[#30](https://github.com/youndie/kachok/pull/30): *"the duplicate's buffer came back too ==>
expected: <0> but was: <2>"*. The two still out were the piece's own blocks, waiting on the
`finally`; the duplicate — which the message names — is the one that had already been released, on
the `continue` path in `run`. The failure landed on a commit that touches only `Session`, which is
how a race of this shape costs more than its probability: the first place anyone looks is the diff.

`BlockWriter.kt` and its test have not changed since
[#11](https://github.com/youndie/kachok/pull/11), so this predates everything it was blamed for.

- **The decision: release, then publish.** The outcome is computed in the `try`, the `finally`
  returns the buffers, and the send happens after both. The `finally` still covers the exceptional
  path, so a hasher or a storage that throws returns its buffers exactly as before.
- **The reason is not only the test.** `results.send` suspends when nothing is reading, and a send
  that suspends while holding the piece's blocks holds `pieceLength / 16 KiB` buffers out of a
  capped pool for the duration. The peers that would drain the pool are blocked acquiring from it,
  and the only thing that can unblock the send is the outcome's reader. Short today because the
  session reads outcomes promptly; the ordering makes it unobservable instead of short.
- **Rejected: making the test wait for the pool to drain.** It would turn a red build green and
  leave the production ordering — including the hold above — exactly as it is, and the next reader
  to act on an outcome would find the same race waiting.
- **Not covered: the outcomes channel has no back-pressure story of its own.** It is
  `Channel.BUFFERED` and nothing sizes it against the pool. Worth an item if a session is ever
  measured with outcomes outrunning their reader.

- AC: with the old ordering, a hundred pieces completed one at a time against a reader already
  parked in `receive` report buffers still out — observed on 3 runs of 3. With the new ordering the
  same test passes, and `./gradlew build -Pkachok.release` is green.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/storage/BlockWriter.kt`,
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/storage/BlockWriterTest.kt`
