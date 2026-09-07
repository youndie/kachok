---
id: B-19
title: "Download a real public torrent end to end, and record the numbers"
status: done
priority: P0
size: S
stage: m4-download
epic: feature-download
blocked_by: [B-18]
---

# B-19 — Download a real public torrent end to end, and record the numbers

Everything before this item is tested against fakes and local peers. A real swarm has clients that
send `bitfield` late, `have` before the handshake finishes, keep-alives on odd schedules, and
requests larger than 16 KiB; this item is where the engine meets them.

- **The decision and its reason.** One publicly distributed, freely licensed torrent (a Linux
  distribution image is the usual choice), downloaded from a clean directory with `-Xlog:gc` and a
  JFR recording on; the resulting throughput, peak heap, carrier count and pool occupancy are
  written into the research as the first measured numbers, replacing the brief's estimates.
- Rejected: declaring M4 done on the fake-peer tests. A protocol implementation that has only met
  its own fakes has met nothing.
- Not covered: seeding back — the client announces `stopped` at the end of this test.

- AC **met 2026-09-05**: the download completes with a matching hash; the numbers are in the research with the
  torrent named, the machine described, and the date; anything that misbehaved is a quirk in the
  feature document or a new backlog item.
- Anchors: `cli/src/main/kotlin/io/github/youndie/kachok/cli/`, `docs/research/research-architecture.md`.

**Closed 2026-09-05.** Debian 13.6.0 netinst, 791 674 880 bytes in 3 020 pieces, from the public
swarm through its HTTP tracker: **368 seconds, and the SHA-256 equals the one Debian publishes**.
The numbers are in the research at §1.2b — zero pinned carriers, 8 MB of live heap, four young
collections, no allocation on the block path.

**This item found four defects, and every one of them was invisible to the local swarm.** Three
runs stalled dead before one completed:

* **The tracker client asked for HTTP/2.** `HttpClient` defaults to it, which over cleartext means
  offering an `h2c` upgrade; `bttracker.debian.org` answers that with something the JDK reports as
  `chunked transfer encoding, state: READING_LENGTH`, while a plain HTTP/1.1 request gets a correct
  `Content-Length`. Pinned to HTTP/1.1. No local test server reproduces it, because a server that
  understands the upgrade handles it correctly.
* **`SocketChannel.connect` has no timeout.** Half the addresses a tracker hands out are dead, and
  each one held a coroutine and a connection slot until the operating system gave up — minutes. A
  thread dump during the first stall showed 22 of 50 dials stuck in `connect` while five
  connections did all the work.
* **A request nobody answered was held for ever.** A peer that takes a request and goes quiet keeps
  that block, and once every block of every started piece was held that way the picker had nothing
  to give anyone and no new piece could begin: stalled at 960 of 3 020 with 25 connections all
  waiting. The session timer now expires requests and offers the blocks to somebody else.
* **The session's state was shared across threads.** This is the serious one. The peer table, the
  picker and every `PeerLink` are plain mutable structures, and the engine's dispatcher is a
  virtual-thread-per-task executor — so they were being read and written from as many carriers as
  the machine has. It surfaced as a `NullPointerException` from a `LinkedHashMap` mid-write. The
  single-threaded test dispatcher had hidden it completely: 116 green tests said nothing about it.
  The session now confines its own coroutines to `limitedParallelism(1)` and does its one blocking
  call — the dial — elsewhere.

**What made the fourth one findable was making failure visible.** The session had a `sessionError`
field and nothing printed it, so a dead timer looked exactly like a slow swarm. The progress line
now shows a degraded session, and the count of unchoked peers and outstanding requests beside the
piece count — with those three numbers the diagnosis took one run instead of three.
