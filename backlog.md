# Backlog: kachok, phase 1

> Role of this document: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. What lives here is the index (generated)
> and everything that is not an item: the goal, the stages, and the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `python3 scripts/backlog_index.py` after editing.

## Goal

Phase 1 is a headless BitTorrent client on JDK 25 whose hot path — socket, direct buffer, disk —
allocates nothing and whose design decisions are measured rather than assumed. It ends when a
real public torrent downloads and seeds end to end, the numbers the brief estimated have been
replaced by numbers this client produced, and the distribution is a run-time image with an AOT
cache that provably maps. Phases 2 and 3 have one stage of placeholders each so that their
questions are asked before their code is written.

The reasoning behind the items is in
[docs/research/research-architecture.md](docs/research/research-architecture.md); an item that
implements a decision cites it.

## Stages

A stage is a field on the item, not a directory. Items are cited by id from documents in every
layer, so re-prioritising an item must never move its file. The order below is dependency order,
which is also the order of work.

| Stage id | Stage | What it is |
|---|---|---|
| `m0-skeleton` | M0 — The build and its gates | Two modules that compile, lint and test on JDK 25; CI that actually runs. |
| `m1-metainfo` | M1 — Metainfo | Bencode, `.torrent` parsing, the info hash; pure common code. |
| `m2-wire` | M2 — The wire | The codec, the buffer pool, one virtual thread per peer. |
| `m3-storage` | M3 — Storage | One writer, gathering writes, sparse files, hashing, deferred `force()`. |
| `m4-download` | M4 — A download, end to end | Tracker, picker, session, the CLI command, a real swarm. |
| `m5-seeding` | M5 — Seeding | The upload read path, the choker, rate limits. |
| `m6-resume` | M6 — Resume | Atomic resume files, start-up verification, graceful shutdown. |
| `m7-measure` | M7 — Measure and ship | JFR baseline, heap and collector, the run-time image, the AOT cache — every hypothesis in the research gets its number here. |
| `m8-extensions` | M8 — Extensions | UDP trackers, fast extension, PEX, DHT, magnets, v2, IPv6. |
| `phase-2-ui` | Phase 2 — UI | Compose desktop in-process; the browser build as a client of the headless engine. Placeholders. |
| `phase-3-mobile` | Phase 3 — Mobile | Android and iOS targets. Placeholder. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (18)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-32](docs/backlog/B-32-udp-tracker.md) `[ ]` | UDP tracker protocol (BEP 15) | P1 | M | B-15 |
| [B-10](docs/backlog/B-10-extension-protocol-handshake.md) `[ ]` | Extension protocol (BEP 10): reserved bit and the handshake dictionary | P2 | S | B-06 |
| [B-22](docs/backlog/B-22-rate-limits.md) `[ ]` | Upload and download rate limits | P2 | S/M | B-21 |
| [B-28](docs/backlog/B-28-aot-cache-in-the-distribution.md) `[ ]` | Build the AOT cache with the launcher's flags and prove it maps | P2 | M | B-29 |
| [B-29](docs/backlog/B-29-jlink-runtime-image.md) `[ ]` | A jlinked run-time image, the jars, and a launcher script | P2 | S/M | B-18 |
| [B-30](docs/backlog/B-30-measure-transferto-vs-mmap.md) `[ ]` | Does seeding need mmap? Measure transferTo against a mapped file | P2 | M | B-20 |
| [B-33](docs/backlog/B-33-fast-extension.md) `[ ]` | Fast extension (BEP 6): reject, have all/none, allowed fast | P2 | M | B-06 |
| [B-34](docs/backlog/B-34-peer-exchange.md) `[ ]` | Peer exchange (BEP 11, ut_pex) | P2 | M | B-10 |
| [B-35](docs/backlog/B-35-dht.md) `[ ]` | Mainline DHT (BEP 5) | P2 | L | - |
| [B-36](docs/backlog/B-36-ut-metadata-and-magnets.md) `[ ]` | Metadata exchange (BEP 9): make magnet links downloadable | P2 | M | B-05, B-10 |
| [B-43](docs/backlog/B-43-picker-allocates-per-decision.md) `[ ]` | The picker allocates a candidate list on every request | P2 | S | - |
| [B-31](docs/backlog/B-31-verify-codec-dispatch-is-a-tableswitch.md) `[ ]` | Check with javap that the message dispatch compiles to a tableswitch | P3 | XS | B-06 |
| [B-37](docs/backlog/B-37-v2-and-hybrid-torrents.md) `[?]` | v2 and hybrid torrents (BEP 52): SHA-256 piece layers | P3 | L | B-04 |
| [B-38](docs/backlog/B-38-ipv6.md) `[ ]` | IPv6 peers and trackers (BEP 7) | P3 | S | B-15 |
| [B-39](docs/backlog/B-39-compose-ui-desktop.md) `[ ]` | Phase 2: a Compose Multiplatform desktop UI on the engine's StateFlow | P3 | XL | B-19 |
| [B-40](docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md) `[ ]` | Phase 2: the wasmJs UI is a client of the JVM headless engine | P3 | L | B-39 |
| [B-41](docs/backlog/B-41-android-and-ios-targets.md) `[ ]` | Phase 3: Android and iOS targets on the engine | P3 | XL | B-39 |
| [B-02](docs/backlog/B-02-ci-runs-build-and-docs-gates.md) `[ ]` | CI runs the build and the documentation gates on every push | infra | S | B-01 |

## Closed (25)

**M0 — The build and its gates**

- [B-01](docs/backlog/B-01-gradle-skeleton-builds-on-jdk-25.md) `[x]` - The Gradle skeleton builds, lints and tests on JDK 25

**M1 — Metainfo**

- [B-03](docs/backlog/B-03-bencode-codec.md) `[x]` - Bencode encoder and decoder in common code
- [B-04](docs/backlog/B-04-metainfo-parser-and-info-hash.md) `[x]` - Metainfo parser and the v1 info hash
- [B-05](docs/backlog/B-05-magnet-link-parsing.md) `[x]` - Parse magnet links into an info hash and tracker list

**M2 — The wire**

- [B-06](docs/backlog/B-06-peer-wire-codec.md) `[x]` - Peer wire codec: handshake, message ids, in-place piece and request
- [B-07](docs/backlog/B-07-virtual-thread-peer-transport.md) `[x]` - One virtual thread per peer on a blocking SocketChannel
- [B-08](docs/backlog/B-08-direct-buffer-pool.md) `[x]` - A capped pool of 16 KiB direct ByteBuffers
- [B-09](docs/backlog/B-09-incoming-connections.md) `[x]` - Accept incoming peers on the BEP 3 port range
- [B-42](docs/backlog/B-42-scopedvalue-in-the-reader-loop.md) `[-]` - Is ScopedValue used anywhere, or dropped?

**M3 — Storage**

- [B-11](docs/backlog/B-11-single-writer-with-gathering-writes.md) `[x]` - One writer coroutine, one gathering positional write per piece
- [B-12](docs/backlog/B-12-file-layout-and-sparse-files.md) `[x]` - Piece-to-file mapping and sparse file creation
- [B-13](docs/backlog/B-13-hashing-dispatcher.md) `[x]` - Whole-piece SHA-1 on a bounded dispatcher with one MessageDigest per thread
- [B-14](docs/backlog/B-14-deferred-force-timer.md) `[x]` - force() on a timer and at close, not per piece

**M4 — A download, end to end**

- [B-15](docs/backlog/B-15-http-tracker-announce.md) `[x]` - HTTP tracker announce with compact peers
- [B-16](docs/backlog/B-16-piece-picker.md) `[x]` - Rarest-first piece picker with strict priority and endgame
- [B-17](docs/backlog/B-17-session-orchestrator.md) `[x]` - Session: the StateFlow, the command channel and the one timer
- [B-18](docs/backlog/B-18-cli-download-command.md) `[x]` - kachok download <file.torrent> [--dir …]: progress on stderr, exit 0 on completion
- [B-19](docs/backlog/B-19-end-to-end-download-acceptance.md) `[x]` - Download a real public torrent end to end, and record the numbers

**M5 — Seeding**

- [B-20](docs/backlog/B-20-upload-read-path.md) `[x]` - Serve requests with FileChannel.transferTo
- [B-21](docs/backlog/B-21-choking-algorithm.md) `[x]` - The ten-second choker with optimistic unchoke

**M6 — Resume**

- [B-23](docs/backlog/B-23-atomic-resume-file.md) `[x]` - A resume record written atomically and rarely
- [B-24](docs/backlog/B-24-startup-verification-of-existing-data.md) `[x]` - Re-hash what the resume file does not vouch for
- [B-25](docs/backlog/B-25-graceful-shutdown.md) `[x]` - SIGINT: stop announces, close peers, flush, write resume, exit

**M7 — Measure and ship**

- [B-26](docs/backlog/B-26-jfr-baseline-of-the-hot-path.md) `[x]` - A JFR baseline: allocations on the hot path, pinned threads, carrier count
- [B-27](docs/backlog/B-27-measure-heap-and-collector.md) `[x]` - Measure the heap the engine needs, with G1 and with ZGC

<!-- END INDEX -->

## Decisions worth not re-litigating

**An item that measures is an item, not a chore.** M7 is half measurements
([B-26](docs/backlog/B-26-jfr-baseline-of-the-hot-path.md),
[B-27](docs/backlog/B-27-measure-heap-and-collector.md),
[B-30](docs/backlog/B-30-measure-transferto-vs-mmap.md)) because the research is explicit about
what it assumed: the heap size, the collector, the seeding read path. A hypothesis that never gets
its measurement is a guess with a paper trail, and the paper trail is worse than nothing because
it looks like evidence.

**A question is a status, not a stalled item.**
[B-37](docs/backlog/B-37-v2-and-hybrid-torrents.md) and
[B-42](docs/backlog/B-42-scopedvalue-in-the-reader-loop.md) are `question` because the work
depends on an answer that only data can give. Writing the code first would choose the expensive
answer by accident. [B-40](docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md) was a
question for one day: the owner answered it, and the item now records the answer and what phase 1
owes it.

**Placeholders for later phases carry no acceptance criteria on purpose.**
[B-39](docs/backlog/B-39-compose-ui-desktop.md) and
[B-41](docs/backlog/B-41-android-and-ios-targets.md) exist so that phase 1 keeps the engine's API
in the shape those phases need — a state flow and a command channel — and for no other reason. An
acceptance criterion written now would be re-written then.

**"Done" means the build said so.** [B-01](docs/backlog/B-01-gradle-skeleton-builds-on-jdk-25.md)
is the only closed item and it closed on a green build, not on the files existing;
[B-02](docs/backlog/B-02-ci-runs-build-and-docs-gates.md) stays open although both workflows are
written, because neither has run.
