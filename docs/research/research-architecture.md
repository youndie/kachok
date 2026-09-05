---
id: research-architecture
title: kachok — architecture research
type: research
status: active
date: 2026-09-05
---

# Research: the architecture of kachok

kachok is a BitTorrent client written in Kotlin. The engine — bencode, the peer wire protocol,
piece selection, choking, storage — is common Kotlin from the first commit; the I/O behind it is
whatever the platform does best, and on the desktop that platform is JDK 25: virtual threads, the
foreign memory API, compact object headers, the ahead-of-time cache. Phase 1 is a **headless JVM
client**; phase 2 puts a Compose Multiplatform UI on the same engine (desktop and wasmJs); phase 3
adds Android and iOS through `expect`/`actual`. The niche is deliberate: not a port of libtorrent,
and not another Java client that allocates a heap object per block — a client whose hot path is
socket → direct buffer → disk with nothing on the heap in between.

This document records **verified facts** (read in the JDK that is installed, in dependency
listings, in the protocol specifications), **decisions** — including where the research changed
the brief — and **risks**. Anything not verified is marked as a hypothesis and says where it will
be settled. The layer documents say what the system does; this one says why it is built this way.

The brief this research was written against is reproduced in [§5](#5-the-brief-and-where-the-research-departs-from-it),
next to every point where the research departs from it.

---

## 1. Verified facts

### 1.1 What JDK 25 actually ships as final, and what it does not

Verified on the JDK the project builds with — `openjdk 25.0.2 2026-01-20` — by reading the class
files with `javap`, the flags with `-XX:+PrintFlagsFinal`, and the sources in the JDK's own
`lib/src.zip`; and against the JEP texts at `openjdk.org/jeps/<n>`.

| Fact | Where verified |
|---|---|
| `java.lang.ScopedValue` is a final API in 25 (no `PreviewFeature` annotation in the class file) | `javap -v java.lang.ScopedValue`; JEP 506, *Release 25* |
| `java.util.concurrent.StructuredTaskScope` is **still preview** in 25 | `javap -v` shows `jdk/internal/javac/PreviewFeature` on the class; JEP 505 |
| Virtual threads are final since 21; `synchronized` no longer pins the carrier since 24 | JEP 444 (*Release 21*), JEP 491 (*Release 24*) |
| A blocking `SocketChannel` read on a virtual thread parks the virtual thread rather than the carrier | `sun/nio/ch/SocketChannelImpl.java` in `src.zip`: `park(Net.POLLIN)` in `read`, `park(Net.POLLOUT)` in `write`, gated on `Thread.currentThread().isVirtual()` |
| Blocking file I/O on a virtual thread is *compensated* (the carrier pool grows), not unmounted | `sun/nio/ch/FileChannelImpl.java`: `Blocker.begin(...)` / `Blocker.end(...)` around `read`, `write`, positional variants, `transferTo` |
| The carrier pool's ceiling is `jdk.virtualThreadScheduler.maxPoolSize` | `java/lang/VirtualThread.java`, the scheduler's `createDefaultScheduler` |
| `-XX:+UseCompactObjectHeaders` is a **product** flag in 25, default `false` | `PrintFlagsFinal`: `bool UseCompactObjectHeaders = false {product lp64_product}`; JEP 519, *Release 25* |
| JEP 519 reports 22 % less heap and 8 % less CPU on SPECjbb2015, 10 % less time on a JSON parser benchmark | JEP 519, *Motivation* — those are the JEP's numbers for the JEP's workloads, not this project's |
| The AOT cache flags `AOTCache`, `AOTCacheOutput`, `AOTConfiguration`, `AOTMode` are product flags | `PrintFlagsFinal`; JEP 514 (*Release 25*), JEP 515 (*Release 25*) |
| The AOT cache requires jar-only, identical class paths and identical module options across training and production runs | JEP 483, *Description → Consistency* |
| ZGC is the generational collector only; the non-generational mode was removed in 24 | JEP 490 (*Release 24*); `PrintFlagsFinal` on 25 has no `ZGenerational` flag at all |
| G1 is the default collector on this machine; `UseZGC = false` by default | `PrintFlagsFinal` |
| `FileChannel.map(MapMode, long, long, Arena)` returning a `MemorySegment` exists; `Arena.ofConfined()` / `ofAuto()` / `global()` exist | `javap java.nio.channels.FileChannel`, `javap java.lang.foreign.Arena`; JEP 454 (*Release 22*) |
| `FileChannel` has positional `write(ByteBuffer, long)` and gathering `write(ByteBuffer[], int, int)`; `transferTo(long, long, WritableByteChannel)` | `javap java.nio.channels.FileChannel` |
| `StandardOpenOption.SPARSE` exists **and is ignored on Unix** | `javap java.nio.file.StandardOpenOption`; `sun/nio/fs/UnixChannelFactory.java`: `case SPARSE: /* ignore */ break;` |
| A heap `ByteBuffer` handed to channel I/O is copied into a temporary direct buffer first | `sun/nio/ch/IOUtil.java`: `Util.getTemporaryDirectBuffer(rem)` in both the read and the write paths |
| SHA-1 / SHA-256 / SHA-3 / SHA-512 intrinsics are on for this CPU | `-XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal`: `UseSHA1Intrinsics = true`, `UseSHA256Intrinsics = true` (diagnostic flags, so invisible without the unlock) |
| `jlink`, `jpackage`, `jfr` and the `jmods/` directory ship with this JDK build | `ls <JDK>/bin`, `ls <JDK>/jmods` |
| JFR's default configuration records `jdk.VirtualThreadPinned` | `<JDK>/lib/jfr/default.jfc` |
| Linking a run-time image without jmods is a build-time option of the JDK, not the default | JEP 493 (*Release 24*), *Restrictions* |

**Consequence 1 — the whole "one blocking channel per peer" design stands on two different
mechanisms.** Socket reads and writes *unmount* the virtual thread, so ten thousand parked peers
cost ten thousand stacks and no carriers. File reads and writes do **not** unmount: they block the
carrier and the scheduler compensates by adding one, up to `maxPoolSize` (256 by default). A design
that performs disk I/O from every peer's virtual thread therefore converts "thousands of peers" into
"up to 256 blocked platform threads" under load. That is why disk I/O is concentrated in one writer
([D4](#d4-one-writer-a-queue-of-blocks-and-one-gathering-write-per-piece)) and the read side is bounded
([D5](#d5-seeding-reads-go-through-transferto-first-mmap-is-a-measured-hypothesis)).

**Consequence 2 — direct buffers are not an optimisation, they are the absence of a copy.** The
JDK copies every heap buffer into a temporary direct buffer on the way to the kernel. A pooled
direct buffer that travels socket → pool → disk is the only path with zero copies on the Java side.

**Consequence 3 — `SPARSE` costs nothing and buys nothing on macOS and Linux**, where files are
sparse by default; it matters on Windows only. It stays in the open options because it is free and
phase 1 does not test Windows ([Risk 6](#3-risks-and-open-questions)).

**Consequence 4 — `StructuredTaskScope` is out.** The brief did not ask for it, and the point is
worth recording anyway: structured concurrency for this project is kotlinx.coroutines, not the JDK
preview API, so no `--enable-preview` anywhere.

### 1.2 The AOT cache, measured on this JDK

The JEP text for JDK 24 (JEP 483) says "ZGC is not yet supported". The JDK 25 JEPs (514, 515) do
not say whether that changed. So it was run. A one-class program on `-cp app.jar`, one-step
workflow (`-XX:AOTCacheOutput=<file>`), three configurations:

| Configuration | Result |
|---|---|
| G1 (default) | cache created, 11.9 MB; mapped on the next run (`-Xlog:aot=info`: *Opened AOT cache*) |
| `-XX:+UseZGC` | cache created, 10.0 MB; **mapped on the next run under ZGC** — the JDK 24 limitation is gone on 25.0.2 |
| `-XX:+UseCompactObjectHeaders` | cache created, 12.5 MB; mapped; the log records `UseCompactObjectHeaders = 1` in the cache |
| a G1-made cache run under `-XX:+UseZGC` | **refused**: *The saved state of UseCompressedOops and UseCompressedClassPointers is different from runtime, CDS will be disabled* — and the program then ran normally without it |

The AOT cache built from a `jlink`ed run-time image (the one in §1.3) is created the same way.

**Consequence 1.** The cache is bound to the exact VM configuration — collector, compressed oops,
header layout — and to the exact jar list. The distribution has to fix all of those in a launcher,
build the cache with that launcher's flags, and ship the cache next to the jars. A user who edits
the flags gets a silently uncached start, not an error.

**Consequence 2.** "Silently" is the risk: the fallback is a warning line and a normal start. A
distribution smoke test has to assert that the cache was *mapped*, not that the program ran —
[Risk 3](#3-risks-and-open-questions).

### 1.2a The transport, measured on this machine

Verified by `SocketPeerConnectionTest` (B-07) on macOS/aarch64, JDK 25.0.2, 2026-09-05.

| Measurement | Result |
|---|---|
| 1 000 connections to a local peer, each parked on a blocking `SocketChannel.read` | **+8 platform threads**, and 266 ms to open all of them |
| the same, per connection | 0.008 platform threads |

**Consequence.** The design's central claim is not a quotation from a JEP any more. One thread per
connection would have been a thousand platform threads; the eight are the carrier pool growing to
the machine's parallelism. The test asserts a bound of `availableProcessors() + 32` rather than
the measured 8, because the bound rules out the failure that matters — one platform thread per
peer — without failing on a scheduler that adds a carrier for its own reasons.

### 1.3 A trimmed run-time image, measured

`jlink --add-modules java.base,java.net.http,jdk.jfr,java.management --strip-debug --no-man-pages
--no-header-files --compress zip-6` from this JDK produces a **32 MB** image on macOS/aarch64 that
runs `java -version` and creates an AOT cache. The brief's "40–60 MB instead of the full JDK" is
consistent with that for a headless client; the Compose phase adds `java.desktop` and grows it.

**Consequence.** The phase-1 distribution does not need `jpackage` or the Compose Gradle plugin:
a run-time image, the application jars on the class path, a launcher script and an AOT cache is
the whole artefact — [D10](#d10-phase-1-ships-a-jlinked-runtime-plus-jars-jpackage-arrives-with-compose).

### 1.3a Sparse files: the mechanism is not interchangeable

Measured on this machine (macOS 27, APFS, JDK 25.0.2, 2026-09-05) by creating a 4 MB file four
ways and reading `du -k` and `stat -f %b`:

| How the file was given its length | Reported size | Allocated |
|---|---|---|
| one byte written at `position = length - 1` | 4 000 000 | **3 908 KB** |
| two 4 KiB blocks written with a hole between them | 4 000 000 | **3 908 KB** |
| `RandomAccessFile.setLength(length)` | 4 000 000 | **0 KB** |
| written out in full with zeros | 4 000 000 | 3 908 KB |

**Consequence 1 — the obvious trick is preallocation.** Extending a file by writing past its end
costs exactly as much as writing the whole file, which is the thing D4's sparse-file decision
exists to avoid. Only `setLength` leaves a hole. `FileSet` therefore sizes every file with
`RandomAccessFile.setLength` before opening its channel, and the class comment says why, because
the two calls look interchangeable and are not.

**Consequence 2 — the JDK cannot tell you this.** There is no portable allocated-block count:
the `unix` attribute view on macOS exposes `size` and not `blocks`, so the test that guards this
shells out to `du`. A check that quietly skipped would be indistinguishable from one that passed.

**Correction to §1.1, Consequence 3.** That paragraph said `SPARSE` "costs nothing and buys
nothing on macOS and Linux, where files are sparse by default". The first half stands — the option
is dropped by `UnixChannelFactory`. The second half was too glib: a file is sparse only if it is
*extended* rather than *written*, and nothing about the platform makes that choice for you.

### 1.4 Kotlin, coroutines and the build

Verified against Maven Central (`repo1.maven.org/maven2/<group>/<artifact>/maven-metadata.xml`),
the Kotlin release notes at `kotlinlang.org/docs/whatsnew*.html`, the coroutines `CHANGES.md` in
`Kotlin/kotlinx.coroutines`, and the shared build conventions this portfolio publishes
(`ru.workinprogress.sborka`, source at `github.com/youndie/sborka`).

| Fact | Where verified |
|---|---|
| Kotlin **2.4.10** is the current stable release; 2.4.20 is at RC3 (planned September 2026) | Maven Central `kotlin-stdlib` metadata; `kotlinlang.org/docs/releases.html` |
| Java 25 bytecode since Kotlin 2.3.0; Java 26 since 2.4.0 | *What's new in Kotlin 2.3.0 → Kotlin/JVM*; *2.4.0 → Support for Java 26* |
| `-Xjvm-default` is **deprecated**; the stable option is `-jvm-default` with `enable` / `no-compatibility` / `disable` (Kotlin 2.2.0) | *What's new in Kotlin 2.2.0 → Changes to default method generation*; `compiler-reference.html`, `-jvm-default mode` |
| Guard conditions in `when` are Stable since 2.2.0 | *What's new in Kotlin 2.2.0 → Stable features* |
| `-Xwhen-expressions=indy` compiles a `when` over a sealed hierarchy to one `invokedynamic` type switch, JVM target 21+ (Kotlin 2.2.20) | *What's new in Kotlin 2.2.20* |
| `-Xno-param-assertions` / `-Xno-call-assertions` are **not** in the documented compiler reference; the compiler accepts them | `compiler-reference.html` has no entry; accepted by `kotlinc` 2.4.10 in this repository's release build (`./gradlew build -Pkachok.release`) |
| kotlinx.coroutines **1.11.0** is current; its changelog has **no** virtual-thread dispatcher — `Executor.asCoroutineDispatcher()` is the mechanism | Maven Central metadata; `CHANGES.md` (the only "virtual" entries concern virtual *time* in tests) |
| Compose Multiplatform 1.12.0 is current (phase 2) | Maven Central `compose-gradle-plugin` metadata; the shared catalog pins the same |
| Gradle 9.7.1 is current and runs on JDK 25 | `services.gradle.org/versions/current`; `docs.gradle.org/current/userguide/compatibility.html` lists 25 |
| The shared conventions (`ru.workinprogress.sborka`) are at 0.2.0.29 on a **publicly readable** snapshot repository; the `wip` catalog they carry pins coroutines 1.11.0, serialization 1.11.0, ktor 3.5.2, compose 1.12.0, koin 4.2.2, junit 6.1.3 | `reposilite.kotlin.website/snapshots/ru/workinprogress/sborka/settings/maven-metadata.xml`; `catalog/sborka.versions.toml` in the sborka repository |
| This repository's skeleton — `:engine` (multiplatform, `jvm()` only) and `:cli` — **builds, lints and tests green** on JDK 25 with Kotlin 2.4.10, Gradle 9.7.1, sborka 0.2.0.29, `-jvm-default=no-compatibility`, and with the release flags | `./gradlew build` and `./gradlew build -Pkachok.release` in this repository, 2026-09-05 |

**Consequence 1.** The brief's `-Xjvm-default=all` becomes `jvmDefault = NO_COMPATIBILITY` in
`engine/build.gradle.kts` — same effect, the supported spelling.

**Consequence 2.** "Compiles to `tableswitch`" is true of a `when` over integer constants, which is
what a peer-wire message id is; it is not true of guard conditions, which compile to branches. The
codec dispatches on the id byte with a constant `when`; guards are for readability elsewhere and
carry no performance claim. Whether the compiled codec really is a `tableswitch` is checked with
`javap` when the codec exists — **hypothesis, check in M2**.

**Consequence 3.** The engine's coroutine dispatcher is
`Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()`, and every other dispatcher
in the engine is a `limitedParallelism` view of it — [D1](#d1-one-virtual-thread-dispatcher-blocking-io-inside-it-coroutines-above-it).

### 1.5 The protocol, from the specifications

Verified against the BEP texts at `www.bittorrent.org/beps/bep_<nnnn>.html`. Every number a
feature document quotes comes from here.

| Fact | Where verified |
|---|---|
| Requests are for `2^14` (16 KiB) blocks; "all current implementations … close connections which request an amount greater than that" | BEP 3, *peer messages*, `request` (amended 11-Oct-2013) |
| Handshake: one byte `19`, the string `BitTorrent protocol`, eight reserved bytes, the 20-byte info hash, the 20-byte peer id | BEP 3, *peer protocol* |
| Message ids: `0` choke, `1` unchoke, `2` interested, `3` not interested, `4` have, `5` bitfield, `6` request, `7` piece, `8` cancel; a zero-length message is a keep-alive, sent "once every two minutes" | BEP 3, *peer messages* |
| `bitfield` is only ever the first message; high bit first; spare bits zero | BEP 3 |
| Connections start choked and not interested; requests are pipelined; on choke, queued requests are dropped | BEP 3 |
| The reference choking algorithm: re-evaluate every ten seconds, unchoke the four best interested downloaders, one optimistic unchoke rotating every thirty seconds; a seed ranks by upload rate | BEP 3, *choking and optimistic unchoking* |
| Tracker request keys `info_hash`, `peer_id`, `ip`, `port`, `uploaded`, `downloaded`, `left`, `event` (`started` / `completed` / `stopped`); response `interval` + `peers`, or `failure reason` | BEP 3, *trackers* |
| Listening port convention: 6881, then 6882 … give up after 6889 | BEP 3, *trackers*, `port` |
| Trackers may return `peers` as a packed string of 6 bytes per peer (4 ip + 2 port), requested with `compact=1` | BEP 23 |
| The extension protocol is advertised by `reserved[5] & 0x10`; extended messages use id `20` | BEP 10 |
| The fast extension is advertised by `reserved[7] |= 0x04` and adds Have All / Have None, Reject Request, Suggest, Allowed Fast | BEP 6 |
| Metadata over the wire (`ut_metadata`) moves in 16 KiB (16384-byte) blocks | BEP 9 |
| UDP trackers use a `connect` / `announce` handshake with a `connection_id` | BEP 15 |
| DHT is a UDP protocol (KRPC) | BEP 5 |
| v2 torrents use SHA-256, a `piece layers` dictionary and 16 KiB leaf blocks; the request limit is the same `2^14` | BEP 52 |
| Peer id convention `-XX0000-` (Azureus style) | BEP 20 |

**Consequence 1.** The block size is not a tunable. 16 KiB is what the swarm will accept; a buffer
pool sized to one block is a buffer pool sized to the protocol.

**Consequence 2.** The protocol has three timers with three periods — 10 s choke, 30 s optimistic,
120 s keep-alive — and all three are per *session*, not per peer, which is why one timer coroutine
suffices ([D1](#d1-one-virtual-thread-dispatcher-blocking-io-inside-it-coroutines-above-it)).

**Consequence 3.** v1 and v2 share the 16 KiB block and the request shape but not the hash function
or the piece verification; phase 1 is v1, and the seam is the hasher —
[Open question 3](#3-risks-and-open-questions).

---

## 2. Decisions

### D1. One virtual-thread dispatcher; blocking I/O inside it, coroutines above it

Brief: virtual threads for I/O, coroutines on top for orchestration, a `limitedParallelism(cores)`
dispatcher for hashing, one timer coroutine per session.
Decision: exactly that, with one clarification the brief leaves open — **there is one root
dispatcher**, built from `Executors.newVirtualThreadPerTaskExecutor()`, and every other dispatcher
the engine uses is a `limitedParallelism` view of it. No `Dispatchers.IO`, no `Dispatchers.Default`
in engine code.

Why:

- a coroutine that suspends on a `Channel` and resumes is resumed on *some* virtual thread of the
  executor; a blocking socket read inside it parks that virtual thread (§1.1). Both halves of the
  brief live on the same threads, and there is no hand-off between a "coroutine pool" and an "I/O
  pool" to get wrong;
- `limitedParallelism(cores)` on top of the same executor is enough to keep hashing from taking
  every carrier, and it costs one line;
- the price: CPU-bound hashing shares carriers with socket-parking peers. Parked peers hold no
  carrier, so the practical contention is hashing versus the writer and the timer — measured in M7
  with JFR before anyone tunes it.

Per-peer structure: one coroutine per peer owns the connection; its *reader* is a blocking loop
(read length prefix, read message, dispatch); its *writer* drains a `Channel<Outgoing>`. The
session owns the timer, the picker, the choker and the tracker announcer as coroutines under one
`SupervisorJob`; cancelling the session cancels every peer.

**Correction found while implementing M2** (B-07): this paragraph used to say the reader "never
suspends". It cannot. Reading an incoming block means taking a buffer from the pool, and that call
suspends when every buffer is out — which is not an oversight but the back-pressure of D3, the only
flow control the download path has. The reader therefore has exactly one suspension point, at the
one place where waiting is the correct behaviour; everything else in the loop blocks. See the
knock-on correction in D2.

### D2. `ScopedValue` is allowed in the blocking loops only *(deviation from the brief)*

Brief: `ScopedValue` instead of `ThreadLocal` for the session context.
Decision: session context travels as a `CoroutineContext.Element` in coroutine code. `ScopedValue`
may be used **inside a blocking loop that never suspends** — the per-peer reader — and nowhere
else. Whether it is used at all is decided in M2; the default is "not".

Why:

- a scoped value is bound to a *thread* for the dynamic extent of `run` (JEP 506, §1.1). A
  coroutine suspends on one virtual thread and resumes on another one the executor creates; the
  binding does not follow it. Reading a `ScopedValue` after a suspension point is either unbound or
  another coroutine's value — the exact class of bug thread-locals have under coroutines, with
  better-looking syntax;
- `CoroutineContext` is the mechanism the coroutine runtime already propagates through `launch`,
  `withContext` and `Channel` hand-offs; it is the same idea with the right scope;
- the price: two mechanisms if `ScopedValue` is used in the reader loop. The reader loop is one
  function, and the rule "no `ScopedValue.get()` after a suspension" is checkable by reading it.

**Correction found while implementing M2** (B-07): there is no such loop, so there is no price and
no second mechanism. The reader suspends when it takes a pool buffer (see D1's correction), which
means a `ScopedValue` binding does not survive it there either. `ScopedValue` is therefore not used
anywhere in this engine, and Open question 5 is answered: no. The exemption this decision carved
out was carved for a loop that turned out not to exist.

### D3. A pool of 16 KiB direct buffers is the unit of everything

Decision: `BufferPool` hands out direct `ByteBuffer`s of exactly one block (16 KiB, §1.5). A peer
reads a `piece` payload straight into a pooled buffer; the buffer travels to the writer queue; the
writer returns it to the pool after the gathering write. The pool has a hard cap; a peer that
cannot get a buffer does not read, which turns the cap into TCP back-pressure.

Why:

- §1.1: a heap buffer is copied into a temporary direct buffer by the JDK anyway; pooling the
  direct buffer removes the copy and the temporary;
- one size means no fragmentation and no size classes; the last block of a torrent is shorter and
  uses a slice, not a smaller buffer;
- the price: memory is committed off-heap up front and counted separately from `-Xmx`; the cap is
  a config knob with a measured default (M7), not a guess.

`piece` and `request` messages are parsed **in place** — index, begin, length read from the buffer,
no message object. The sealed `Message` hierarchy exists for the rare messages (`have`, `bitfield`,
choke/unchoke, the extension handshake) where an object per message is noise, not cost.

### D4. One writer, a queue of blocks, and one gathering write per piece

Decision: a single writer coroutine consumes `Channel<Block>`; blocks are grouped per piece; when
a piece is complete it is **hashed from the buffers** on the hashing dispatcher and, if the hash
matches, written with one positional gathering `write(ByteBuffer[])` per contiguous file span, then
the buffers go back to the pool. Files are opened with `CREATE, WRITE, READ, SPARSE`. `force()` runs
on a timer and at close; the resume file is written to a temporary and `ATOMIC_MOVE`d.

Why:

- §1.1 Consequence 1: file I/O blocks carriers. One writer means one blocked carrier at a time
  instead of one per peer;
- hashing from the buffers before the write means a corrupt piece never touches the disk and is
  never re-read to be checked; the cost is holding a piece's blocks in the pool until it completes,
  which the picker bounds by finishing started pieces first (BEP 3 "strict priority");
- gathering: a 4 MiB piece is 256 blocks; 256 `write` calls become as many calls as the piece has
  file spans — one, for the common case;
- positional writes need no seek and no lock; the writer is the only writer anyway, and the
  seeding read path never writes;

**Correction found while implementing M3** (B-11): there is no such thing as a positional gathering
write. `FileChannel` offers `write(ByteBuffer, long)` — positional, one buffer — and
`write(ByteBuffer[], int, int)`, which writes at the *channel's* position; `write(ByteBuffer[],
long)` does not exist (`javap java.nio.channels.FileChannel`). Aiming a gathering write therefore
means calling `position(…)` first, which is channel state and not a parameter. That is safe here
only because exactly one coroutine ever writes — so "one writer" is load-bearing for a second
reason on top of the carrier-thread one, and a future optimisation that adds a second writer would
break the file layout rather than merely the thread budget.
- the price: a crash between the write and the next `force()` loses what the page cache had;
  the resume file records verified pieces, so the recovery is a re-hash of the pieces the resume
  file did not mention. [Risk 5](#3-risks-and-open-questions).

### D5. Seeding reads go through `transferTo` first; mmap is a measured hypothesis *(deviation from the brief)*

Brief: FFM `MemorySegment` + `Arena` for mmap, "for seeding read straight from the page cache
without copies into the heap".
Decision: the phase-1 upload path is `FileChannel.transferTo(position, 16 KiB, socketChannel)`. The
mmap path (`FileChannel.map(READ_ONLY, …, Arena)`) is implemented **only if** M7's measurement says
`transferTo` is the bottleneck, and then first for start-up hashing of large files, where a
sequential scan over a mapping is the obvious win.

Why:

- `transferTo` from a file to a socket is the kernel's zero-copy path (`sendfile` on Linux and
  macOS); it copies nothing into the Java heap and nothing into a direct buffer either. That is the
  brief's goal with less machinery;
- an mmap read still ends in `SocketChannel.write(segment.asByteBuffer())`, which is a copy from
  the mapping into the socket by the kernel — the same copy count as `transferTo`, plus unmapping
  to manage;
- what mmap uniquely buys — hashing a multi-gigabyte file by scanning a mapping — is a start-up
  concern, not a seeding one;
- the price: `transferTo` is compensated file I/O (§1.1), so a seeding storm blocks carriers up to
  `maxPoolSize`. Uploads are bounded by the choker (four unchoked peers plus one optimistic, §1.5)
  which bounds the concurrent `transferTo` calls to five per torrent. Measured in M7 with
  `jdk.VirtualThreadPinned` and thread counts before the cap is raised.

### D6. G1 with compact object headers for phase 1; ZGC is a measured alternative *(deviation from the brief)*

Brief: Generational ZGC, or G1 with `-Xmx` 128–256 MB.
Decision: G1, `-XX:+UseCompactObjectHeaders`, `-Xmx256m` as the *starting* value, all three in
`cli/build.gradle.kts` (`applicationDefaultJvmArgs`) so that `./gradlew :cli:run` runs the same VM a
distribution would. ZGC is tried in M7 on the same workload; whichever wins on the measured heap
and pause profile is what the launcher ships.

Why:

- §1.2: the AOT cache is bound to the collector. Picking one now means one cache to build and one
  launcher to test;
- the heap this engine needs is small by construction (D3: the data is off-heap). At 256 MB, G1's
  pauses are the young-collection pauses of a heap that mostly holds peer state; ZGC's advantage
  is pause time on large heaps, and its cost is a higher memory overhead per byte of heap;
- the price: "128–256 MB is enough" is the brief's estimate and this document's hypothesis. It is
  measured in M7 (`-Xlog:gc`, JFR allocation profile) on a real swarm before it becomes a number in
  the README.

### D7. Two modules now, and the phases are seams, not stubs

Decision: `:engine` is a Kotlin Multiplatform library module with the `jvm()` target only;
`:cli` is a JVM application. Phase 2 adds `:ui`; phase 3 adds Android and iOS targets to `:engine`.
A target is declared **when there is an implementation behind it**, never earlier.

Inside `:engine`, common code depends on I/O through interfaces — `PeerTransport`, `Storage`,
`TrackerClient`, `Hasher` — and phase 1 has one implementation of each, in `jvmMain`. The
platform-primitive layer underneath those (a monotonic clock, secure random bytes, SHA-1) is
`expect`/`actual`.

Why:

- an interface can have two implementations on one platform — a real transport and a test fake
  that feeds recorded byte streams — and a test that exercises the picker against a fake peer is
  the cheapest test in the project. `expect`/`actual` forces one implementation per platform and
  makes that fake impossible without a second module;
- `expect`/`actual` is right where there is genuinely one answer per platform, which is what a
  clock and a hash function are;
- a target with no `actual` behind it fails to compile; a target with a stub `actual` compiles and
  lies. Neither belongs on `main`;
- the price: the phase-3 port is "write four `jvmMain` classes again for Android and iOS", which is
  the plan the brief describes, plus keeping the interfaces platform-neutral — no `ByteBuffer` in a
  common signature. The seam for that is the buffer abstraction in `commonMain`, and it is the one
  place the phase-1 code has to be careful on behalf of phases it is not implementing.

### D8. HTTP tracker announces use `java.net.http` in phase 1

Decision: `TrackerClient` is an interface in common; the JVM implementation uses
`java.net.http.HttpClient` and the bencode decoder. Ktor is not a dependency of phase 1.

Why: an announce is one GET every `interval` seconds per tracker. A client library is weight in
the run-time image and a second HTTP stack next to the JDK's for no request the JDK's cannot make.
When a second platform needs the same code (phase 3), the shared catalog already pins Ktor 3.5.2
and the interface does not change. UDP trackers (BEP 15) are their own implementation on
`DatagramChannel` and a backlog item.

### D9. Build conventions come from the portfolio's shared plugin

Decision: `ru.workinprogress.sborka.settings` in `settings.gradle.kts`; `sborka.kmp` + `sborka.lint`
on the engine; `sborka.base` + `sborka.test` + `sborka.lint` on the CLI; `sborka.jvmToolchain=25`,
`sborka.jvmFloor=25`; Kotlin from `gradle/libs.versions.toml`, everything that must agree with it
from the `wip` catalog the settings plugin provides. Verified green in §1.4.

Why: the conventions are one line per property instead of the usual sixty lines of Kotlin per
repository, and — the part that matters here — `sborka.test` refuses a test task that ran nothing
and checks that every declared `@Test` executed, which is exactly the class of silent failure a
protocol codec's test suite is prone to. The price is a dependency on a snapshot repository; it is
publicly readable (§1.4) and the same one nine other repositories already build from.

### D10. Phase 1 ships a `jlink`ed runtime plus jars; `jpackage` arrives with Compose

Decision: the phase-1 distribution is a directory: the run-time image of §1.3, `engine` and `cli`
jars with their dependencies on the class path, a launcher script that fixes the JVM flags of D6,
and an AOT cache built by a training run through that launcher. `jpackage` and installers are
phase 2, where the Compose Gradle plugin produces them.

Why: the application is not modular (Kotlin's stdlib and coroutines are automatic modules, which
`jlink` cannot link), so a "jlinked application image" would be the run-time image plus a class
path either way. Naming that honestly saves a plugin. The price: no installer and no code signing
in phase 1, which a headless CLI does not need.

### D11. Compiler settings

Decision, all verified by the green build in §1.4: `explicitApi()` and warnings-as-errors on the
engine (from `sborka.kmp`); `jvmDefault = NO_COMPATIBILITY`; `-Xno-param-assertions` and
`-Xno-call-assertions` **only** behind `-Pkachok.release`. K2 is the only compiler in 2.4.

Why the release gate: the assertions catch a `null` coming out of a JDK platform type at the call
site, which is where one wants to catch it while the JDK bindings are being written. They cost on
the hot path — `ByteBuffer` calls in a codec — and the hot path is what a release build is for.

### D12. Documentation follows the docs-bootstrap format, and `main` stays honest

Decision: `docs/` in this repository, the layered format of `docs/README.md`, a file-per-item
backlog. Feature documents describing behaviour that does not exist yet are `status: draft` and
live in an open branch, not on `main`; `main` documents the skeleton it has.

Why: a document that says "the client downloads a torrent" on a branch where `Main.kt` prints
usage and exits is a document nobody can trust about anything else either.

---

## 3. Risks and open questions

**Risk 1. Carrier pinning and compensation hide a thread explosion.** Mechanism: file I/O and
`transferTo` block carriers (§1.1); enough concurrent disk operations grow the carrier pool to
`maxPoolSize` and then queue. Mitigation: D4 (one writer) and D5 (uploads bounded by the choker);
`jdk.VirtualThreadPinned` and the carrier count recorded by JFR in M7's baseline, with a threshold
in the smoke test rather than a feeling.

**Risk 2. The buffer pool cap is also the throughput cap.** Mechanism: every in-flight request
holds one 16 KiB buffer; `peers × pipeline depth × 16 KiB` is the pool's working set, and a pool
that is too small starves the pipeline. Mitigation: the picker reads the pool's free count before
issuing requests, the default cap is derived from the measured pipeline depth in M4, and the
config exposes both numbers so a measurement can move them.

**Risk 3. An AOT cache that fails to map fails silently.** Mechanism: §1.2 — a flag mismatch
produces a warning and a normal, slow start. Mitigation: the distribution's smoke test runs the
launcher with `-Xlog:aot=info` and asserts the *Opened AOT cache* line; a cache that does not map
fails the build that would ship it.

**Risk 4. Phase 2's wasmJs target cannot be a BitTorrent peer.** Mechanism: a browser has no TCP
or UDP sockets; the wire protocol is TCP. A wasmJs *engine* is therefore impossible; a wasmJs *UI*
is possible only against an engine running elsewhere. Mitigation now: the engine's API is a
`StateFlow` of session state plus a command channel — a shape that can be put behind a WebSocket
without redesign. The decision — the JVM headless client is the backend, the browser build is its
client — was taken by the owner on 2026-09-05 and is recorded under
[Open question 4](#3-risks-and-open-questions), so that nobody designs the UI against an engine
that cannot exist.

**Risk 5. Data the page cache had at a crash is lost, and the resume file is the only map.**
Mechanism: D4 defers `force()`. Mitigation: the resume file records hashed pieces only, is written
atomically, and start-up re-hashes every piece the resume file does not vouch for. The cost is
re-hashing after an unclean exit, which is bounded by how often the resume file is written — a
knob, measured in M6.

**Risk 6. Windows is not tested in phase 1.** Positional writes, `SPARSE` and `ATOMIC_MOVE` behave
differently there and nothing in phase 1 runs on it. Mitigation: it is said here and in the CLI
service document rather than implied by "JVM"; the CI matrix grows when there is a Windows user.

**Risk 7. Peers close the connection on a request larger than 16 KiB** (§1.5). Mitigation: the
request size is a constant, the last block of a torrent is computed, and the codec test suite has
a scenario for the last block of the last piece.

**Open question 1. Does the seeding path need mmap at all?** Hypothesis: no — D5's `transferTo`
saturates a home uplink with five unchoked peers. Settled in M7 by measuring upload throughput and
carrier occupancy; the mmap implementation is written only if the hypothesis is refuted.

**Open question 2. What heap does the engine need?** Hypothesis: under 256 MB with compact
headers for a hundred peers across ten torrents, because the data lives off-heap (D3). Settled in
M7 with a JFR allocation profile; the number then goes into the launcher and the README together,
and nowhere else.

**Open question 3. v2 and hybrid torrents (BEP 52).** Hypothesis: phase 1 handles v1 and reads
hybrid torrents through their v1 info dictionary; v2's SHA-256 piece layers are a second `Hasher`
and a second verification path behind the same `Storage`. Settled when the first hybrid torrent
fails to load — the backlog item is open and low priority on purpose.

**Open question 4 — settled 2026-09-05.** What does a browser UI talk to? The owner's answer:
**the JVM headless client is the backend, the wasmJs build is a client of it** — the same Compose
UI compiled for desktop (in-process engine) and for the browser (engine behind a socket). Not
WebRTC peers, not a browser-side engine. Two consequences for phase 1: the CLI grows into a
headless service in phase 2 rather than being replaced, and the engine's `StateFlow` + command
channel is a wire contract in waiting, so it must stay serialisable — no platform types, no
callbacks — from the first version ([B-40](../backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md)).

**Open question 5 — settled 2026-09-05, in M2.** Is `ScopedValue` worth having at all? No, and for
a firmer reason than the hypothesis had. The exemption in D2 assumed a reader loop that never
suspends; the reader suspends on pool acquisition, so a binding would not survive it. Nothing in
the engine uses `ScopedValue`, and [B-42](../backlog/B-42-scopedvalue-in-the-reader-loop.md) is
dropped rather than done.

---

## 4. What happens next

The order of work and the acceptance criteria live in [backlog.md](../../backlog.md). Phase 1 is
eight milestones, in dependency order: the build and its gates (M0), bencode and metainfo (M1),
the wire codec and the virtual-thread transport (M2), storage (M3), an end-to-end download (M4),
seeding (M5), resume (M6), and the measurements that turn this document's hypotheses into numbers
(M7). Phases 2 and 3 are one stage of placeholders each, so that the questions above are asked
before their code is written.

The first thing to nail down is M2's codec, because every scenario in every feature document is
phrased in its terms — message ids, block sizes, handshake bytes — and the codec is the one part of
the engine whose correctness is fully decidable from the BEP text alone.

---

## 5. The brief, and where the research departs from it

The brief is a list of JDK 25 and Kotlin techniques with the expected gains. Most of it survives
verification unchanged; the departures are listed so that nobody restores the original wording by
accident.

| Brief said | Research found | Where |
|---|---|---|
| `ScopedValue` instead of `ThreadLocal` for the session context | bindings are per thread and do not survive a coroutine's suspension; the reader loop that was to be the exemption suspends too, so `ScopedValue` is not used at all and `CoroutineContext` carries the session | D2, Open question 5 |
| FFM mmap for seeding, "read straight from the page cache" | `transferTo` is the kernel's zero-copy path with the same copy count and no unmapping; mmap is a hypothesis for start-up hashing | D5 |
| Generational ZGC *or* G1 | G1 + compact headers for phase 1, because the AOT cache is bound to the collector and the heap is small; ZGC measured in M7 | D6, §1.2 |
| `-Xjvm-default=all` | deprecated since Kotlin 2.2.0; `-jvm-default=no-compatibility` | §1.4 |
| Kotlin 2.2+ | 2.4.10 is current; Java 25 bytecode needs 2.3.0 or later anyway | §1.4 |
| `when` with guards "compiles to `tableswitch`" | constant `when` does; guards do not; the codec uses the former and the claim is checked with `javap` in M2 | §1.4 |
| `jlink` + `jpackage` via the Compose plugin | phase 1 has no Compose; a `jlink`ed runtime (32 MB measured) plus jars, `jpackage` in phase 2 | D10, §1.3 |
| `-XX:+UseCompactObjectHeaders`: "minus 10–20 % heap" | JEP 519's numbers are 22 % on SPECjbb2015; this project's number is Open question 2 | §1.1, D6 |
| Sparse files via `SPARSE` instead of preallocation | the option is ignored on Unix, where files are sparse anyway; kept for Windows | §1.1 |
| AOT cache "JEP 514/515" | works on 25.0.2 with G1, ZGC and compact headers, each needing its own cache; the silent fallback is a risk with a mitigation | §1.2, Risk 3 |

What survives as written: virtual thread per peer on a blocking `SocketChannel`; coroutines with
`Channel`/`select` for orchestration; the 16 KiB direct buffer pool and the socket → disk path;
`value class` ids and primitive arrays; in-place parsing of `piece`/`request`; `MessageDigest` with
one instance per hashing thread; whole-piece hashing on a `limitedParallelism(cores)` dispatcher;
one timer per session; one writer with gathering positional writes; deferred `force()`; atomic
resume files; `StateFlow` for the UI; `kotlin.time.Duration`; K2; JFR. Vector API and Valhalla are
not taken, as the brief says.
