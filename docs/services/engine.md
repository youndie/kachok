---
id: engine
title: engine (Kotlin Multiplatform library module)
type: service
module: engine
tech_stack: [Kotlin 2.4 Multiplatform, kotlinx.coroutines 1.11, JDK 25 (jvm target)]
owner: unassigned
depends_on:
  - BitTorrent trackers (HTTP; UDP later)
  - BitTorrent peers (TCP)
publishes:
  - "engine-jvm.jar (consumed by :cli only; not published to a Maven repository)"
---

# engine

## 1. Responsibility

Everything a BitTorrent client does that is not a user interface: decoding metainfo, talking to
trackers, the peer wire protocol, choosing pieces, choking peers, verifying and storing data,
resuming. It owns the **session state** — one `StateFlow` per session, conflated, which is the only
thing a UI or a CLI reads — and the **command channel**, which is the only way anything outside
the module changes that state.

It deliberately does **not** own: argument parsing, printing, configuration files, the choice of
JVM flags, or any I/O primitive on its own — the common code sees interfaces
(research [D7](../research/research-architecture.md#d7-two-modules-now-and-the-phases-are-seams-not-stubs)),
and the JVM implementations behind them live in this module's `jvmMain` source set but are handed
in by the caller.

It also does not own a network listener's *policy*: which port to bind, whether to accept incoming
connections at all, is the caller's configuration. The engine implements BEP 3's 6881–6889 probe
when asked to.

## 2. API contracts

The engine has no HTTP surface. Its contracts are:

* **Inbound:** the public Kotlin API of `commonMain` — `explicitApi()` is on, so every public
  declaration is deliberate. The entry point will be a `Session` factory taking the I/O
  implementations and a configuration; today the public API is the three id value classes in
  `Ids.kt`.
* **Outbound, external:** BEP 3 (peer wire, HTTP tracker), BEP 23 (compact peers), later BEP 15
  (UDP tracker), BEP 10/9/11 (extensions), BEP 5 (DHT). The facts the code relies on are in
  research [§1.5](../research/research-architecture.md#15-the-protocol-from-the-specifications).

## 2a. Code anchors

What exists on `main` today:

| File | What is there |
|---|---|
| `engine/build.gradle.kts` | the one target (`jvm()`), `jvmDefault = NO_COMPATIBILITY`, the release-only assertion flags |
| `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/Ids.kt` | `InfoHash`, `PeerId`, `PieceIndex` — the value classes every other type is phrased in |
| `engine/src/commonTest/kotlin/ru/workinprogress/kachok/engine/IdsTest.kt` | the size checks |
| `.../engine/bencode/` | the codec: `BValue`, the strict decoder that records source byte ranges, the canonical encoder |
| `.../engine/metainfo/` | `Metainfo`, `TorrentFile`, and the parser that hashes `info` from its source bytes |
| `.../engine/platform/Sha1.kt` + `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/platform/Sha1.jvm.kt` | the one-shot SHA-1 primitive, `expect`/`actual` |
| `.../engine/wire/` | `Handshake`, the sealed `Message`, `PeerWire` — framing, the identifier table, in-place `piece` decoding |
| `.../engine/peer/Peer.kt` | `PeerAddress`, `Block`, `PeerEvent`, `PeerConnection` — what the session is allowed to know about a connection |
| `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/BufferPool.kt` | the capped pool of direct 16 KiB buffers and its `PooledBuffer` handle |
| `.../engine/io/EngineDispatchers.kt` | the virtual-thread dispatcher every coroutine in the engine runs on |
| `.../engine/io/SocketPeerConnection.kt` | one peer, one blocking `SocketChannel`, one virtual thread; `connect` and `accept`, blocks read straight into pool buffers |
| `.../engine/io/PeerListener.kt` | the 6881–6889 probe and the accept loop |
| `.../engine/io/SocketPeerDialer.kt` | the `PeerDialer` the session dials through |
| `.../engine/storage/PieceLayout.kt` | piece and block to file spans, by cumulative offsets |
| `.../engine/storage/PieceHasher.kt` | the interface a piece is verified through, before it is written |
| `.../engine/storage/BlockWriter.kt` | the single writer: blocks in, verified pieces out, buffers back to the pool |
| `.../engine/storage/Storage.kt` | where a verified piece goes |
| `.../engine/storage/FileStorage.kt` (jvmMain) | one gathering write per file span, and the `SpanSink` seam that makes the call count assertable |
| `.../engine/picker/Bitfield.kt` | which pieces something has, as a `LongArray` in BEP 3's bit order |
| `.../engine/picker/PiecePicker.kt` | rarest-first with strict priority, the started-piece bound, endgame |
| `.../engine/choke/Choker.kt`, `RateMeter.kt` | BEP 3's ten-second pass and optimistic unchoke, over a rolling rate window |
| `.../engine/resume/ResumeRecord.kt` | what survives a restart, bencoded, and the store interface |
| `.../engine/resume/FileResumeStore.kt` (jvmMain) | a temporary sibling and an `ATOMIC_MOVE` |
| `.../engine/tracker/Tracker.kt`, `TrackerProtocol.kt` | the announce model, the query string and the response parsing — both peer encodings |
| `.../engine/tracker/HttpTrackerClient.kt` (jvmMain) | the GET, blocking on a virtual thread |
| `.../engine/session/SessionState.kt` | the state a UI reads, the commands it sends, and every knob with what it trades |
| `.../engine/session/Session.kt` | the orchestrator: peers, tracker loop, writer, one timer, all under one `SupervisorJob` |
| `.../engine/hash/MessageDigestPieceHasher.kt` (jvmMain) | SHA-1 on a bounded dispatcher, with a pool of digests and the `JvmBlock` seam |
| `.../engine/storage/FileSet.kt` (jvmMain) | the torrent's files, created sparse with `setLength` and kept open for positional writes |
| `engine/src/commonTest/kotlin/ru/workinprogress/kachok/engine/` | 161 tests across every package; the session's nine run entirely on fakes; the fixtures are embedded strings, because a KMP test source set has no resources |

The layout the backlog builds toward, under `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/`
(a directory appears when its first backlog item lands; none of these exist yet):

| Directory | What goes there | Backlog |
|---|---|---|
| `peer/` | one peer's state machine on top of the connection: choke/interest flags, pipeline, rates | [B-17](../backlog/B-17-session-orchestrator.md) |
| `resume/` | reading a record back: seeding the picker and re-hashing the rest | [B-24](../backlog/B-24-startup-verification-of-existing-data.md) |

and under `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/`:

| Directory | What goes there | Backlog |
|---|---|---|
| `storage/` | the `transferTo` read path for uploads, beside the writer already there | [B-20](../backlog/B-20-upload-read-path.md) |
| `tracker/` | the UDP announce, beside the HTTP one already there | [B-32](../backlog/B-32-udp-tracker.md) |

## 3. How it is built

Three shapes are decisions rather than accidents; each is argued in the research and only
summarised here.

* **One dispatcher, virtual threads underneath.** The root `CoroutineDispatcher` wraps
  `Executors.newVirtualThreadPerTaskExecutor()`; hashing runs on `limitedParallelism(cores)` of the
  same dispatcher; the writer is one coroutine. Each peer is one coroutine whose reader is a
  blocking loop on a blocking `SocketChannel` — a virtual thread parks on socket I/O, so this scales
  to thousands of peers with no selector code — measured at **8 platform threads for a thousand
  parked connections** (research §1.2a). The reader suspends in exactly one place, taking a pool
  buffer, and that suspension is the back-pressure
  ([D1](../research/research-architecture.md#d1-one-virtual-thread-dispatcher-blocking-io-inside-it-coroutines-above-it)).
* **A block is a pooled direct buffer, from socket to disk.** The `piece` payload is read into a
  16 KiB direct buffer from the pool and that buffer — not a copy — is what the writer hands to
  `FileChannel.write(ByteBuffer[], …)`. A peer that cannot get a buffer stops reading; that is the
  back-pressure ([D3](../research/research-architecture.md#d3-a-pool-of-16-kib-direct-buffers-is-the-unit-of-everything)).
* **Verify, then write, from one writer.** A piece is hashed from its buffers on the hashing
  dispatcher and written only if the hash matches; file I/O blocks a carrier thread (the JDK
  compensates rather than unmounts), so it is confined to one coroutine
  ([D4](../research/research-architecture.md#d4-one-writer-a-queue-of-blocks-and-one-gathering-write-per-piece)).

Session context is a `CoroutineContext.Element`. `ScopedValue` is not used in coroutine code;
research [D2](../research/research-architecture.md#d2-scopedvalue-is-allowed-in-the-blocking-loops-only-deviation-from-the-brief)
says why.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Library | `kotlinx-coroutines-core` 1.11.0 (from the shared `wip` catalog) | dispatchers, `Channel`, `select`, `StateFlow` |
| Library | `kotlin-test`, `kotlinx-coroutines-test` | tests |
| External | HTTP trackers | announces (BEP 3) |
| External | peers over TCP | the wire protocol |
| JDK | `java.base` (`java.nio.channels`, `java.security.MessageDigest`, `java.lang.foreign` later) and `java.net.http` | the `jvmMain` implementations |

No Ktor, no serialization library, no DI framework in phase 1 — research
[D8](../research/research-architecture.md#d8-http-tracker-announces-use-javanethttp-in-phase-1).

## 5. Infrastructure and deploy

None of its own. The module is consumed by [cli](cli.md) and is not published to a Maven
repository; `sborka.publish` is deliberately not applied.

## 6. Local setup

```bash
./gradlew :engine:jvmTest
```

Nothing else has to be running: `commonTest` is pure Kotlin, and the JVM implementations will be
tested against fakes and local sockets. `./gradlew build` runs the same plus ktlint; the check that
every declared `@Test` actually executed comes from `sborka.test`.

## 7. Configuration

There is no configuration surface yet. The knobs the research names — buffer pool cap, pipeline
depth, listening port range, `force()` interval, resume interval — arrive with their backlog items
as fields of a `SessionConfig` in `session/`, with the measured defaults from M7 written next to
them. Nothing is read from the environment by this module; that is [cli](cli.md)'s job.

## 8. Quirks

* **The module has one target and says so.** `engine/build.gradle.kts` declares `jvm()` only. The
  Android, iOS and wasmJs targets of later phases are absent, not commented out — a target without
  an implementation is either a compile failure or a stub that lies.
* **`-Xno-param-assertions` and `-Xno-call-assertions` are release-only.** They are added when the
  build runs with `-Pkachok.release`; a plain `./gradlew build` keeps the null checks. Both builds
  are green on 2026-09-05.
* **An uploaded byte never enters this process.** `FileChannel.transferTo` moves it from the page
  cache to the socket inside the kernel; the storage interface therefore has no `read` returning
  bytes, because one would make the copy compulsory.
* **The session confines its own state to one thread, and must.** The peer table, the picker and
  every `PeerLink` are plain mutable structures; the engine's dispatcher runs coroutines on as many
  carriers as the machine has. `Session.start` takes `limitedParallelism(1)` of the caller's
  dispatcher for its own coroutines and does its one blocking call — the dial — elsewhere. A single
  test dispatcher hides the absence of this completely.
* **A collection iterated across a suspension point is racy, single-threaded or not.** Coroutines
  interleave at suspension points exactly as threads interleave anywhere, so every loop that sends
  to each peer iterates a snapshot. The symptom otherwise is an intermittent
  `ConcurrentModificationException` from code that looks sequential.
* **A connection ending is an event, never a thrown exception.** `SocketPeerConnection` reports
  `PeerEvent.Closed` and does not rethrow: under a `SupervisorJob` a throw goes to the platform's
  uncaught-exception path, and the commonest cause is our own `close()`.
* **`catch (Exception)` around a suspending call swallows cancellation.** Every such catch in this
  module rethrows `CancellationException` first; a peer that cannot be cancelled outlives its
  session.
* **A gathering write is aimed by moving the channel's position**, because the JDK has no
  `write(ByteBuffer[], long)`. Correct only while exactly one coroutine writes; a second writer
  would corrupt the file layout, not merely the thread budget.
* **A `ThreadLocal` is not a reuse mechanism under virtual threads.** One per thread means one per
  task when threads are per task; the hasher pools its digests to the dispatcher's parallelism
  instead. Anything else in this engine tempted to cache per thread has the same problem.
* **A file is sized with `setLength`, never by writing past its end.** The two look
  interchangeable; measured on APFS the second allocates the whole file, which is the
  preallocation the design refuses (research §1.3a).
* **An unknown message identifier closes the connection.** `PeerWire.decode` throws on one rather
  than ignoring the frame: a peer should not send what the handshake did not negotiate, and
  ignoring unknown frames would hide a framing bug of ours as "some messages are dropped". The
  interoperability cost is real and is paid deliberately.
* **A `.torrent` is input from a stranger, and `MetainfoParser` treats it as one.** Path
  components that are empty, `.`, `..`, or that contain a separator are refused at parse time, so
  no code below has to remember that a torrent can ask to be written outside its own directory.
* **`explicitApi()` and warnings-as-errors come from `sborka.kmp`**, not from this file. A new
  public declaration without a visibility modifier fails the build; that is intended.
