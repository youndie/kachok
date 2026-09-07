---
id: B-44
title: "Does closing a peer socket end a write already in flight?"
status: done
priority: P2
size: S/M
stage: m7-measure
epic: feature-seeding
blocked_by: [B-30]
---

# B-44 — Does closing a peer socket end a write already in flight?

Found while measuring the upload read path (research §1.3c). With twenty peers pulling flat out,
clearing the run flag and closing every socket left **all** the harness's threads alive; the
`FileChannel.close()` that followed blocked indefinitely in `NativeThreadSet.signalAndWait`,
waiting for the ones still inside `transferTo0`. It happens on the mapped path too, where the
thread is in a plain `SocketChannel.write`, so it is not about `transferTo`.

The engine does not obviously hit this: the choker bounds concurrent serves to five, and
`Session.shutDown` closes peers before it touches storage. Neither is a guarantee — the first is a
number that could change, the second is an ordering that holds for a different reason (durability).
A stuck writer would show up as a shutdown that takes the CLI's full ten-second bound and a
`FileSet.close` that never returns.

- **The question.** Does `SocketChannel.close()` from another thread end a blocking `write` or
  `transferTo` in progress on that channel, on macOS and on Linux? If it does not, what does —
  `Thread.interrupt` on the virtual thread, `shutdownOutput`, a write timeout?
- **The decision this feeds.** Whether `SocketPeerConnection.close` needs more than closing the
  channel, and whether `FileSet.close` needs to be bounded.
- Rejected in advance: raising the unchoke cap before this is answered. The measurement says the
  failure appears somewhere above ten concurrent serves.
- Not covered: Windows.

- AC: a small harness that blocks N writers on a socket with a full send buffer, closes it, and
  reports how many end; a table in the research for macOS and Linux; the mechanism named, or
  recorded as unestablished with what was ruled out.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerConnection.kt`,
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/io/BlockedWriteProbe.kt`,
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/io/BlockedTransferTest.kt`.

**Answered, and the answer is a one-line fix.** Research §1.3d has the table, measured on macOS and
in a Linux container.

**The question as asked had a false premise.** It asked whether `SocketChannel.close()` ends a
blocking write; it does, five different ways do. What does not end is a thread inside
`FileChannel.transferTo(position, count, socket)` — waiting on the *socket* while registered on the
*file* channel, so closing the socket signals nobody. One thread, two channels.

**Two of the obvious remedies are worse than doing nothing.** Closing the file channel signals the
thread and waits for it to leave, which it never does. `Thread.interrupt()` goes through
`AbstractInterruptibleChannel.postInterrupt`, which closes the channel, which waits — so the
*interrupting* thread hangs. Both were found the hard way: the probe hung itself twice before it
hung anything on purpose, and it now runs every stop attempt on a bounded thread for that reason.

**`shutdownOutput` ends it, identically on both kernels** — so this is not a platform quirk to ship
around, it is how `transferTo` behaves. `SocketPeerConnection.close` shuts the output down before
closing, and `BlockedTransferTest` keeps both halves: that `close` alone is not enough, and that
`shutdownOutput` is. The first assertion is the one that starts failing if a JDK ever makes `close`
sufficient, which is the right way to learn that.

Nothing here changes [B-30](B-30-measure-transferto-vs-mmap.md)'s choice: the price of `transferTo`
turned out to be a line in `close`, not the 15 % the mapped path costs.
