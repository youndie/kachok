---
id: B-44
title: "Does closing a peer socket end a write already in flight?"
status: open
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
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/SocketPeerConnection.kt`,
  `engine/src/jvmTest/kotlin/ru/workinprogress/kachok/engine/storage/UploadPathBench.kt`.
