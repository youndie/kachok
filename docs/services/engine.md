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
| `engine/src/commonTest/kotlin/ru/workinprogress/kachok/engine/bencode/BencodeTest.kt`, `.../metainfo/MetainfoParserTest.kt` | 18 tests; the fixtures are embedded strings, because a KMP test source set has no resources |

The layout the backlog builds toward, under `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/`
(a directory appears when its first backlog item lands; none of these exist yet):

| Directory | What goes there | Backlog |
|---|---|---|
| `wire/` | handshake, message ids, in-place `piece`/`request` parsing, the sealed `Message` for the rest | [B-06](../backlog/B-06-peer-wire-codec.md) |
| `peer/` | one peer's state machine: choke/interest flags, pipeline, rates | [B-07](../backlog/B-07-virtual-thread-peer-transport.md) |
| `picker/` | rarest-first, strict priority for started pieces, endgame | [B-16](../backlog/B-16-piece-picker.md) |
| `choke/` | the ten-second choker and the optimistic unchoke | [B-21](../backlog/B-21-choking-algorithm.md) |
| `storage/` | `Storage` interface, piece → file-span mapping, the writer queue | [B-11](../backlog/B-11-single-writer-with-gathering-writes.md) |
| `tracker/` | `TrackerClient` interface, announce request/response model | [B-15](../backlog/B-15-http-tracker-announce.md) |
| `session/` | `Session`, the `StateFlow`, the command channel, the one timer | [B-17](../backlog/B-17-session-orchestrator.md) |
| `resume/` | the resume record and its atomic persistence | [B-23](../backlog/B-23-atomic-resume-file.md) |

and under `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/`:

| Directory | What goes there | Backlog |
|---|---|---|
| `io/` | `BufferPool` (direct, 16 KiB), the virtual-thread `PeerTransport`, the listener | [B-07](../backlog/B-07-virtual-thread-peer-transport.md), [B-08](../backlog/B-08-direct-buffer-pool.md), [B-09](../backlog/B-09-incoming-connections.md) |
| `storage/` | `FileChannel` storage: positional gathering writes, `transferTo` reads, `force()` timer | [B-11](../backlog/B-11-single-writer-with-gathering-writes.md), [B-20](../backlog/B-20-upload-read-path.md) |
| `hash/` | `MessageDigest` per hashing thread, the `limitedParallelism` dispatcher — bulk piece hashing, not the one-shot primitive above | [B-13](../backlog/B-13-hashing-dispatcher.md) |
| `tracker/` | `java.net.http` announce | [B-15](../backlog/B-15-http-tracker-announce.md) |

## 3. How it is built

Three shapes are decisions rather than accidents; each is argued in the research and only
summarised here.

* **One dispatcher, virtual threads underneath.** The root `CoroutineDispatcher` wraps
  `Executors.newVirtualThreadPerTaskExecutor()`; hashing runs on `limitedParallelism(cores)` of the
  same dispatcher; the writer is one coroutine. Each peer is one coroutine whose reader is a
  blocking loop on a blocking `SocketChannel` — a virtual thread parks on socket I/O, so this scales
  to thousands of peers with no selector code
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
* **A `.torrent` is input from a stranger, and `MetainfoParser` treats it as one.** Path
  components that are empty, `.`, `..`, or that contain a separator are refused at parse time, so
  no code below has to remember that a torrent can ask to be written outside its own directory.
* **`explicitApi()` and warnings-as-errors come from `sborka.kmp`**, not from this file. A new
  public declaration without a visibility modifier fails the build; that is intended.
