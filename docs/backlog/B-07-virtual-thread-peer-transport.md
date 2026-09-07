---
id: B-07
title: "One virtual thread per peer on a blocking SocketChannel"
status: done
priority: P0
size: M
stage: m2-wire
epic: feature-download
blocked_by: [B-06, B-08]
---

# B-07 — One virtual thread per peer on a blocking SocketChannel

The JVM `PeerTransport`: connect (or accept), handshake, then a reader loop that blocks on
`SocketChannel.read` into pooled buffers and a writer that drains the peer's outgoing channel.
Research §1.1 verified that a blocking socket read parks the virtual thread, not its carrier; this
item is where that fact becomes throughput.

- **The decision and its reason.** The reader is a plain blocking loop, never a coroutine that
  suspends — [../research/research-architecture.md](../research/research-architecture.md) D1. The `piece` payload is read directly into a pooled 16 KiB buffer and
  handed off without copying. Writes go through one outgoing `Channel` per peer so that a choke
  can drop queued requests, as BEP 3 asks.
- Rejected: NIO selectors. The reason virtual threads exist is to make one-thread-per-connection
  cheap; a selector loop is the code this design avoids writing.
- Not covered: incoming connections ([B-09](B-09-incoming-connections.md)), rate limiting
  ([B-22](B-22-rate-limits.md)), encryption (not planned).

- AC **met 2026-09-05** (`SocketPeerConnectionTest`, 7 tests): against a local fake peer, a handshake with a wrong info hash closes the connection; a
  `piece` arrives in a pool buffer and the pool's outstanding count rises by one; a thousand idle
  connections to a local acceptor hold zero platform threads beyond the carriers
  (`jcmd Thread.dump_to_file` counted in the test).
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/`, `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/peer/`.

**Closed 2026-09-05.** The measurement, and the design note this item disproved:

* **A thousand parked connections added 8 platform threads**, in 266 ms — 0.008 threads per peer
  instead of one. The number is in the research at §1.2a; the test asserts a looser bound
  (`availableProcessors() + 32`) because what must never happen is a thread per peer, and a
  scheduler that adds a carrier for its own reasons should not fail a build.
* **"The reader is a plain blocking loop, never a coroutine that suspends" is wrong, and this item
  is where it was found.** Reading a block means taking a buffer from the pool, and that suspends
  when every buffer is out — which is not a flaw but the back-pressure of D3. The reader has one
  suspension point, at the one place where waiting is correct. Corrections written into the
  research at D1 and D2.
* **The knock-on: `ScopedValue` has nowhere left to live.** D2 allowed it "inside a blocking loop
  that never suspends"; that loop does not exist, so the exemption is void and
  [B-42](B-42-scopedvalue-in-the-reader-loop.md) is dropped rather than done.
* **A block reaches the session as a `Block`, not a `ByteBuffer`.** The common interface carries
  the index, the offset and the length; the bytes stay in the pooled buffer on the JVM side, which
  is what keeps the phase-3 port honest and the copy absent.
