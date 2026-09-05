---
id: engine
title: engine (Kotlin Multiplatform library module)
type: service
module: engine
tech_stack: [Kotlin 2.4 Multiplatform, kotlinx.coroutines 1.11, JDK 25 (jvm target)]
owner: unassigned
depends_on:
  - BitTorrent trackers (HTTP and UDP)
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
* **Outbound, external:** BEP 3 (peer wire, HTTP tracker), BEP 23 (compact peers), BEP 15 (UDP
  tracker), later BEP 10/9/11 (extensions), BEP 5 (DHT). The facts the code relies on are in
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
| `.../engine/resume/StartupVerifier.kt` | what is already on the disk, before a peer is dialled |
| `engine/src/jvmTest/kotlin/ru/workinprogress/kachok/engine/storage/UploadPathBench.kt` | `transferTo` against a mapped segment on real sockets — research §1.3c |
| `.../engine/choke/TokenBucket.kt` | the upload and download rate limits, spent by bytes and refilled by the timer |
| `.../engine/wire/Message.kt` | the wire's messages, BEP 3's and BEP 6's `suggest` / `have all` / `have none` / `reject` / `allowed fast` |
| `.../engine/wire/MetadataMessage.kt` | BEP 9's dictionary and the block that follows it with nothing in between |
| `.../engine/metainfo/MetadataAssembly.kt` | the info dictionary arriving in blocks, hashed before it is read |
| `.../engine/metainfo/MetadataFetcher.kt` | the session before the session: announce, dial, handshake, ask, verify |
| `.../engine/dht/NodeId.kt` | 160-bit ids, XOR distance, and what a bucket is |
| `.../engine/dht/Krpc.kt` | BEP 5's three message shapes and four queries, on this project's bencode |
| `.../engine/dht/RoutingTable.kt` | buckets by common prefix, eviction by failure |
| `.../engine/dht/Dht.kt` | bootstrap, the iterative `get_peers` lookup, `announce_peer` |
| `.../engine/io/DatagramKrpcTransport.kt` (jvmMain) | one socket for the whole DHT, multiplexed by transaction id |
| `.../engine/wire/PexMessage.kt` | BEP 11's `added` / `dropped` delta |
| `.../engine/peer/CompactPeers.kt` | BEP 23's six bytes, shared by both trackers and by peer exchange |
| `.../engine/wire/ExtensionHandshake.kt` | BEP 10's `m` dictionary: what a peer can do and the id it wants each extension sent under |
| `.../engine/tracker/Tracker.kt`, `TrackerProtocol.kt` | the announce model, the query string and the response parsing — both peer encodings |
| `.../engine/tracker/UdpTrackerProtocol.kt` | BEP 15's two requests, three replies and retransmit schedule, without a socket |
| `.../engine/tracker/TrackerClientByScheme.kt` | which transport a tracker URL goes to |
| `.../engine/tracker/HttpTrackerClient.kt` (jvmMain) | the GET, blocking on a virtual thread |
| `.../engine/tracker/UdpTrackerClient.kt` (jvmMain) | the connect/announce exchange on a `DatagramSocket`, with BEP 15's retransmits |
| `.../engine/session/SessionState.kt` | the state a UI reads, the commands it sends, and every knob with what it trades |
| `.../engine/session/Session.kt` | the orchestrator: peers, tracker loop, writer, one timer, all under one `SupervisorJob` |
| `.../engine/hash/MessageDigestPieceHasher.kt` (jvmMain) | SHA-1 on a bounded dispatcher, with a pool of digests and the `JvmBlock` seam |
| `.../engine/storage/FileSet.kt` (jvmMain) | the torrent's files, created sparse with `setLength` and kept open for positional writes |
| `engine/src/commonTest/kotlin/ru/workinprogress/kachok/engine/` | 169 tests across every package; the session's nine run entirely on fakes; the fixtures are embedded strings, because a KMP test source set has no resources |

The layout the backlog builds toward, under `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/`
(a directory appears when its first backlog item lands; none of these exist yet):

| Directory | What goes there | Backlog |
|---|---|---|
| `peer/` | one peer's state machine on top of the connection: choke/interest flags, pipeline, rates | [B-17](../backlog/B-17-session-orchestrator.md) |

and under `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/`:

| Directory | What goes there | Backlog |
|---|---|---|
| `storage/` | the `transferTo` read path for uploads, beside the writer already there | [B-20](../backlog/B-20-upload-read-path.md) |
| `tracker/` | trackers reached some other way: BEP 5's DHT is a third transport behind the same interface | [B-35](../backlog/B-35-dht.md) |

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
| External | HTTP and UDP trackers | announces (BEP 3, BEP 15) |
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
* **The listener sets `SO_REUSEADDR`.** Without it a port this client used a minute ago cannot be
  taken again while its old connections sit in `TIME_WAIT`, and a restarted client announces a
  different port than the one peers remember.
* **A blocked `transferTo` is ended by `shutdownOutput`, and by nothing else that is safe.**
  Closing the socket leaves the writer inside it; closing the file channel or interrupting the
  writer hangs the *caller*. Measured the same on macOS and Linux (research §1.3d), which is why
  `SocketPeerConnection.close` shuts the output down first and why the order is not cosmetic.
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
* **A rate limit is applied by not asking, never by reading slowly.** Reading slowly does not stop
  a peer sending — the bytes reach the kernel either way — and writing slowly blocks a virtual
  thread inside a socket write. A block never requested is never sent, and a `request` left
  unanswered costs the peer a timeout and nothing else.
* **The limit is one budget for the session, not one per peer.** A per-peer limit multiplied by
  however many peers happen to be unchoked is not a limit, and an uplink is shared.
* **A throttled download would stall without the timer.** Requests are normally issued when a block
  arrives, and no block arrives while nothing is asked for; the tick that refills the budget is
  also what asks every peer for more.
* **`index in started` on a `Map<Int, _>` boxes the index.** The picker asks it once per piece per
  request, which is where half of the profile's `Integer` allocations came from; a `BooleanArray`
  beside the map answers the same question for nothing. Both mutations of `started` go through one
  pair of methods, because a second copy of a key set is worth nothing if it can drift from it.
* **The info dictionary is served as the bytes it arrived as, never re-encoded.** `Metainfo` keeps
  the slice the parser hashed. A torrent whose keys are not canonically sorted — which BEP 3
  permits and this parser accepts — would re-encode into a dictionary with a *different* hash: well
  formed, and the wrong answer to a question asked by hash.
* **Metadata is served from the moment the torrent is open**, not from the moment it completes. The
  dictionary is whole either way; it is the pieces that are missing.
* **BEP 9 puts bencode and raw bytes in one message with nothing between them.** No length, no
  separator: the block starts at the byte after the dictionary's closing `e`, and the only thing
  that knows where that is is the decoder — which is why `Bencode.decodePrefix` exists and why it
  is the one place trailing bytes are not an error.
* **Metadata is hashed before it is parsed.** All of it came from strangers who were asked by
  identifier, so a mismatch throws the whole assembly away — a single SHA-1 over the whole cannot
  say which peer sent the bad block.
* **`metadata_size` is a number a stranger sends and this client allocates.** It is refused above
  four megabytes, which is far past any torrent in circulation.
* **A magnet announce sends a non-zero `left`.** The torrent's length is in the metadata being
  fetched; zero would announce this client as a seed and bring back leechers only.
* **`NodeId` is not a value class**, unlike the torrent's other twenty-byte identifiers. A value
  class around a `ByteArray` inherits the array's equality, which is identity; the routing table
  keys maps by node id, so every lookup would miss and the table would fill with duplicates of the
  same node.
* **BEP 7's `peers6` is a separate field, not a longer `peers`.** A tracker with both sends both;
  a client reading only the first finds no IPv6 peer, and one reading eighteen bytes as six finds
  three peers made of one peer's halves. Same for PEX's `added6`, which is read and never written.
* **The listener binds the wildcard, not `0.0.0.0`.** On a dual-stack JVM that is `::` and accepts
  both families; binding the IPv4 wildcard announces a port no IPv6 peer can reach, and the failure
  is invisible from an IPv4 test.
* **The DHT's `values` is a list of strings and the tracker's `peers` is one string.** They carry
  the same six bytes per peer and are not the same field. `nodes` is a third shape again —
  twenty-six bytes, id first (research §1.6).
* **One socket for the whole DHT, not one per query.** `announce_peer`'s `implied_port` tells a
  node to remember the port a query arrived from, so the port has to be the same one every time or
  the announce points at nothing. That makes the transport a multiplexer, and `t` is what
  multiplexes.
* **Eviction is by failure, never to make room.** A full bucket of good nodes refuses a new one:
  a known-good node is worth more than an unknown one, and one lost datagram is normal on UDP —
  two failures make a node replaceable.
* **The DHT is off unless asked for.** Joining means contacting three public routers and
  announcing this machine to strangers; nothing in phase 1 needs it, because every torrent this
  client can open names a tracker. Magnets change that.
* **`ut_pex` is a delta, and that is the easy thing to get wrong.** A message repeating the whole
  swarm every minute is still well formed and still parses. Each peer's link remembers what it was
  last told, and an unchanged swarm produces no message at all.
* **A private torrent is not offered `ut_pex`, not merely never sent one.** BEP 27's point is that
  the swarm is the tracker's business, and a peer that sees the name in the handshake will ask.
* **The address advertised for an accepted peer is not the one it dialled from.** That is an
  ephemeral port nothing listens on; the dialable one is BEP 10's `p`, and a peer that gave no
  handshake is not advertised at all. Sending everyone to a dead port is worse than telling them
  about one peer fewer.
* **A peer learned from `ut_pex` is dialled at once.** The alternative — waiting for the next time
  a connection ends — is never, for a client whose peers are all healthy.
* **BEP 6 is worth having for `reject` alone.** Without it a choke leaves both pickers guessing
  which of their outstanding requests died, and the answer arrives as a thirty-second timeout. With
  it the block is free in one round trip, and `PiecePicker.requestRejected` frees exactly the one
  block rather than everything that peer was asked for.
* **`have all` and `have none` are not decoration.** A bitfield for two million pieces is 250 KiB,
  and a client with nothing sends the same 250 KiB of zeros. On a fast connection the first message
  is one of bitfield / have all / have none and is never omitted — which is what a BEP 3 client
  with no pieces does.
* **`allowed fast` is the one message that changes what may be *sent*.** Everything else in the
  protocol changes what is known; this one is why "choked means ask for nothing" has an exception
  in it, and why the picker has `nextFrom`, which asks for blocks of a named piece rather than of
  the piece the ordering rules would have chosen.
* **A suggestion is honoured as a `have` and not as a preference.** BEP 6 says a peer only suggests
  what it has, so that much is free. Putting one peer's hint above rarest-first and above the
  pieces already started needs a rule for two peers suggesting different pieces, and there is no
  measurement here to write one from.
* **An extension id belongs to the peer that published it.** BEP 10's `m` maps a name to the id
  *that peer* wants messages sent under, and the two sides need not agree: `ut_pex` may be 1 here
  and 3 there. A client that hard-codes an id talks only to peers that happen to match it.
* **`m` with an id of `0` means the extension is off**, which is how a peer disables one in a later
  handshake without renumbering the rest. Read as an id it would send every message of that
  extension as another handshake.
* **This client's own `m` is empty, and that is not the same as not speaking BEP 10.** The peer
  learns the handshake happened, this client's version and its listening port, and that nothing
  extended is on offer — which is right until PEX and metadata exchange put names in it.
* **An extended message this client did not ask for is dropped in silence.** BEP 10 works because
  both sides ignore what they do not recognise; a handshake that will not even parse costs the
  peer its extensions and not its connection.
* **A UDP announce uses `DatagramSocket`, not `DatagramChannel`.** BEP 15 is a protocol of
  timeouts, and a channel in blocking mode has no receive timeout — `withTimeout` would cancel the
  coroutine and leave the read blocked underneath it. Measured before choosing: 200 virtual threads
  parked in `DatagramSocket.receive` cost 12 platform threads, so it parks like a socket read.
* **The UDP client connects its socket and still checks the transaction id.** Connecting makes the
  kernel drop datagrams from anyone but the tracker; the transaction id is what survives somebody
  who knows the tracker's address. A datagram that fails either test is a non-event, not a failure,
  and the announce keeps waiting out its window.
* **A tracker URL with an unknown scheme is a `TrackerException`, not an `IllegalArgumentException`.**
  The session catches the first and walks to the next tracker; the second used to end the announce
  loop, which is what a `udp://` entry did before `TrackerClientByScheme` existed.
* **`explicitApi()` and warnings-as-errors come from `sborka.kmp`**, not from this file. A new
  public declaration without a visibility modifier fails the build; that is intended.
