---
id: B-07
title: "One virtual thread per peer on a blocking SocketChannel"
status: open
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

- AC: against a local fake peer, a handshake with a wrong info hash closes the connection; a
  `piece` arrives in a pool buffer and the pool's outstanding count rises by one; a thousand idle
  connections to a local acceptor hold zero platform threads beyond the carriers
  (`jcmd Thread.dump_to_file` counted in the test).
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/peer/`.
