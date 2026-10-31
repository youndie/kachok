# Backlog: kachok, phase 1

> Role of this document: the product backlog. **One file per item in
> [`docs/backlog/`](docs/backlog/)** — `B-NN-<slug>.md`. What lives here is the index (generated)
> and everything that is not an item: the goal, the stages, and the decisions.
>
> New item: copy [`docs/templates/backlog-item.md`](docs/templates/backlog-item.md), take the next
> free `B-NN`, and run `make fix` after editing.

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
| `m9-swarm` | M9 — Meeting the swarm | Why this client meets fewer peers than a mature one on the same torrent: the dial loop, the announce, and the peer sources and transports it does not have. |
| `phase-2-ui` | Phase 2 — UI | Compose desktop in-process: the window, its screens, the installers and the OS integration. |
| `phase-3-mobile` | Phase 3 — Mobile | Android and iOS targets. Placeholder. |
| `phase-3-server` | Phase 3 — Server | The headless client as a service on a box that is always on, and the browser build that is its face. Placeholder. |

## Marks

`[ ]` open · `[~]` in progress · `[x]` done · `[?]` open question · `[-]` dropped

<!-- BEGIN INDEX -->

## Open (10)

| Task | | Priority | Size | Blocked by |
|---|---|---|---|---|
| [B-175](docs/backlog/B-175-a-piece-waits-on-its-slowest-peer-outside-the-endgame.md) `[~]` | An idle peer takes over a block a slow peer has sat on, before the endgame | P1 | S | B-174 |
| [B-116](docs/backlog/B-116-a-torrent-whose-name-is-not-ascii-cannot-be-opened-on-windows.md) `[~]` | A `.torrent` whose name is not ASCII cannot be opened on Windows: the launcher hands the path over as question marks | P2 | S | - |
| [B-180](docs/backlog/B-180-the-material-toolbar-is-invisible-to-accessibility.md) `[ ]` | The Material kit's toolbar buttons are named but are not buttons to the platform's accessibility | P2 | S | - |
| [B-80](docs/backlog/B-80-the-ui-moves-to-commonmain.md) `[~]` | The UI moves to commonMain | P2 | L | B-79 |
| [B-181](docs/backlog/B-181-a-style-can-turn-the-chosen-colour-into-another.md) `[ ]` | A style can turn the chosen colour into another: the swatches show the seed, not the accent | P3 | S | - |
| [B-37](docs/backlog/B-37-v2-and-hybrid-torrents.md) `[?]` | v2 and hybrid torrents (BEP 52): SHA-256 piece layers | P3 | L | B-04 |
| [B-40](docs/backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md) `[ ]` | The browser build of the UI is a client of the headless engine | P3 | L | B-80 |
| [B-41](docs/backlog/B-41-android-and-ios-targets.md) `[ ]` | Phase 3: Android and iOS targets on the engine | P3 | XL | B-39 |
| [B-87](docs/backlog/B-87-a-server-with-a-web-face.md) `[ ]` | Phase 3: a headless server with a web face, installable on a box that is always on | P3 | XL | B-80 |
| [B-02](docs/backlog/B-02-ci-runs-build-and-docs-gates.md) `[ ]` | CI runs the build and the documentation gates on every push | infra | S | B-01 |

## Closed (171)

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
- [B-10](docs/backlog/B-10-extension-protocol-handshake.md) `[x]` - Extension protocol (BEP 10): reserved bit and the handshake dictionary
- [B-42](docs/backlog/B-42-scopedvalue-in-the-reader-loop.md) `[-]` - Is ScopedValue used anywhere, or dropped?

**M3 — Storage**

- [B-11](docs/backlog/B-11-single-writer-with-gathering-writes.md) `[x]` - One writer coroutine, one gathering positional write per piece
- [B-119](docs/backlog/B-119-the-outcome-outruns-the-buffers.md) `[x]` - A piece's outcome is published before its buffers are back, and CI fails on it about once in a hundred runs
- [B-12](docs/backlog/B-12-file-layout-and-sparse-files.md) `[x]` - Piece-to-file mapping and sparse file creation
- [B-13](docs/backlog/B-13-hashing-dispatcher.md) `[x]` - Whole-piece SHA-1 on a bounded dispatcher with one MessageDigest per thread
- [B-14](docs/backlog/B-14-deferred-force-timer.md) `[x]` - force() on a timer and at close, not per piece
- [B-146](docs/backlog/B-146-files-are-sparse-on-windows.md) `[x]` - On Windows a torrent's files are created sparse, so a write deep into a fresh file costs that write
- [B-147](docs/backlog/B-147-a-closed-connection-keeps-its-buffers.md) `[x]` - A connection closed while its block waited for the session kept the block's pool buffer
- [B-148](docs/backlog/B-148-a-second-copy-of-a-block-holds-a-buffer.md) `[x]` - A second copy of a block that arrived before its piece's verdict held a pool buffer for good
- [B-149](docs/backlog/B-149-a-torrent-skipping-a-file-reads-downloading-after-a-restart.md) `[x]` - A torrent with every wanted file on the disk read 'downloading' after a restart when it skipped a file
- [B-150](docs/backlog/B-150-resume-records-live-with-the-client.md) `[x]` - Resume records live in the client's own directory, not beside every download
- [B-151](docs/backlog/B-151-one-limit-for-the-whole-client.md) `[x]` - One download and upload limit for the whole client, and a cap on downloads at once with the rest queued
- [B-152](docs/backlog/B-152-one-cap-on-connections-for-the-whole-client.md) `[x]` - One cap on connections and one on connection attempts in flight, for the whole client
- [B-153](docs/backlog/B-153-the-queue-order-survives-a-restart.md) `[x]` - The remembered torrents come back in the order they were added, so the download queue keeps its order across a restart
- [B-154](docs/backlog/B-154-each-torrent-gets-its-share-of-the-dials.md) `[x]` - Each transferring torrent gets an equal share of the client's dials in flight
- [B-174](docs/backlog/B-174-the-block-memory-is-one-budget-for-the-client.md) `[x]` - The pieces open across the client fit the direct memory they live in

**M4 — A download, end to end**

- [B-124](docs/backlog/B-124-the-download-has-no-feature-document.md) `[x]` - The thing this client is for has no feature document, so it has no scenarios
- [B-15](docs/backlog/B-15-http-tracker-announce.md) `[x]` - HTTP tracker announce with compact peers
- [B-16](docs/backlog/B-16-piece-picker.md) `[x]` - Rarest-first piece picker with strict priority and endgame
- [B-17](docs/backlog/B-17-session-orchestrator.md) `[x]` - Session: the StateFlow, the command channel and the one timer
- [B-18](docs/backlog/B-18-cli-download-command.md) `[x]` - kachok download <file.torrent> [--dir …]: progress on stderr, exit 0 on completion
- [B-19](docs/backlog/B-19-end-to-end-download-acceptance.md) `[x]` - Download a real public torrent end to end, and record the numbers

**M5 — Seeding**

- [B-109](docs/backlog/B-109-download-seed-closes-the-set-before-it-seeds.md) `[x]` - `kachok download --seed` closes the set before it seeds, so nobody can reach it
- [B-110](docs/backlog/B-110-this-client-never-uploads-a-block.md) `[x]` - This client never uploads a block: no live connection is ever given the storage to serve from
- [B-20](docs/backlog/B-20-upload-read-path.md) `[x]` - Serve requests with FileChannel.transferTo
- [B-21](docs/backlog/B-21-choking-algorithm.md) `[x]` - The ten-second choker with optimistic unchoke
- [B-22](docs/backlog/B-22-rate-limits.md) `[x]` - Upload and download rate limits

**M6 — Resume**

- [B-122](docs/backlog/B-122-a-file-the-files-tab-calls-complete-is-not.md) `[x]` - The Files tab called a file 100% and the bytes were not there
- [B-23](docs/backlog/B-23-atomic-resume-file.md) `[x]` - A resume record written atomically and rarely
- [B-24](docs/backlog/B-24-startup-verification-of-existing-data.md) `[x]` - Re-hash what the resume file does not vouch for
- [B-25](docs/backlog/B-25-graceful-shutdown.md) `[x]` - SIGINT: stop announces, close peers, flush, write resume, exit

**M7 — Measure and ship**

- [B-123](docs/backlog/B-123-a-seed-that-holds-part-of-the-torrent.md) `[x]` - Every seed on the stand holds everything, so nothing on it can be rare
- [B-125](docs/backlog/B-125-a-measurement-that-is-a-pair.md) `[x]` - A speed comparison needs a harness that cannot publish a lonely number
- [B-126](docs/backlog/B-126-a-stand-with-more-than-one-leecher.md) `[x]` - The swarm cost of a picker cannot appear on a stand with one leecher
- [B-163](docs/backlog/B-163-an-aot-cache-is-opened-before-it-is-refused.md) `[x]` - The AOT checks look for a line the VM prints before it refuses the cache
- [B-26](docs/backlog/B-26-jfr-baseline-of-the-hot-path.md) `[x]` - A JFR baseline: allocations on the hot path, pinned threads, carrier count
- [B-27](docs/backlog/B-27-measure-heap-and-collector.md) `[x]` - Measure the heap the engine needs, with G1 and with ZGC
- [B-28](docs/backlog/B-28-aot-cache-in-the-distribution.md) `[x]` - Build the AOT cache with the launcher's flags and prove it maps
- [B-29](docs/backlog/B-29-jlink-runtime-image.md) `[x]` - A jlinked run-time image, the jars, and a launcher script
- [B-30](docs/backlog/B-30-measure-transferto-vs-mmap.md) `[x]` - Does seeding need mmap? Measure transferTo against a mapped file
- [B-31](docs/backlog/B-31-verify-codec-dispatch-is-a-tableswitch.md) `[x]` - Check with javap that the message dispatch compiles to a tableswitch
- [B-43](docs/backlog/B-43-picker-allocates-per-decision.md) `[x]` - The picker allocates a candidate list on every request
- [B-44](docs/backlog/B-44-does-closing-a-peer-end-a-write-in-flight.md) `[x]` - Does closing a peer socket end a write already in flight?

**M8 — Extensions**

- [B-135](docs/backlog/B-135-the-magnet-fetch-gives-up-too-early.md) `[x]` - The magnet fetch gives up too early: first tracker only, twenty dials, no DHT
- [B-32](docs/backlog/B-32-udp-tracker.md) `[x]` - UDP tracker protocol (BEP 15)
- [B-33](docs/backlog/B-33-fast-extension.md) `[x]` - Fast extension (BEP 6): reject, have all/none, allowed fast
- [B-34](docs/backlog/B-34-peer-exchange.md) `[x]` - Peer exchange (BEP 11, ut_pex)
- [B-35](docs/backlog/B-35-dht.md) `[x]` - Mainline DHT (BEP 5)
- [B-36](docs/backlog/B-36-ut-metadata-and-magnets.md) `[x]` - Metadata exchange (BEP 9): make magnet links downloadable
- [B-38](docs/backlog/B-38-ipv6.md) `[x]` - IPv6 peers and trackers (BEP 7)
- [B-45](docs/backlog/B-45-serve-metadata-to-peers.md) `[x]` - Serve the info dictionary to peers that ask (BEP 9)

**M9 — Meeting the swarm**

- [B-100](docs/backlog/B-100-protocol-encryption.md) `[x]` - Protocol encryption (MSE/PE): the peers that will not talk in the clear
- [B-101](docs/backlog/B-101-utp-transport.md) `[x]` - µTP (BEP 29): the transport this client cannot be reached on
- [B-102](docs/backlog/B-102-local-service-discovery.md) `[x]` - Local service discovery (BEP 14): the peers on the same network are never found
- [B-103](docs/backlog/B-103-upnp-and-nat-pmp-port-mapping.md) `[x]` - Port mapping (UPnP IGD, NAT-PMP/PCP): reopening B-09's rejection, because the reason given was a dependency
- [B-105](docs/backlog/B-105-connections-are-made-and-not-kept.md) `[x]` - Three hundred handshakes, twenty-two peers held — and the client asks nineteen of them for nothing
- [B-111](docs/backlog/B-111-two-connections-to-the-same-peer.md) `[x]` - Two connections to the same peer: nothing drops the second, and endgame asks it for everything again
- [B-112](docs/backlog/B-112-a-peer-interested-for-seconds-is-never-unchoked.md) `[-]` - A peer interested for a few seconds is never unchoked: the choke pass is the only place an unchoke happens
- [B-113](docs/backlog/B-113-shutdowntest-interrupts-a-download-that-has-already-finished.md) `[x]` - `ShutdownTest` can interrupt a download that has already finished, and then finds no record
- [B-114](docs/backlog/B-114-a-peer-that-stops-reading-stops-the-whole-session.md) `[x]` - Against the reference client this one downloads at half the rate or not at all: a peer that stops reading stops the whole session, and a lookup that finds nothing is kept for fifteen minutes
- [B-118](docs/backlog/B-118-a-peer-that-never-answers-is-dialled-for-ever.md) `[x]` - A peer that never answers is redialled every thirty seconds for the life of the torrent
- [B-127](docs/backlog/B-127-trading-barely-starts-before-a-download-ends.md) `[x]` - Four clients on one seed trade 2% of the data, because almost nothing is ever unchoked
- [B-128](docs/backlog/B-128-ties-among-equally-rare-pieces.md) `[x]` - Rarest-first breaks ties by index, which keeps every client of a swarm in lock step
- [B-129](docs/backlog/B-129-a-piece-can-be-verified-twice.md) `[x]` - Four clients recorded taking 40.9 MiB of a 40.0 MiB torrent
- [B-139](docs/backlog/B-139-upnp-searches-every-interface.md) `[x]` - UPnP finds the router on a machine with several adapters, and a refusal is checked against the router's table
- [B-95](docs/backlog/B-95-the-dial-loop-only-runs-when-something-else-happens.md) `[x]` - The client stops dialling: there is no periodic top-up, and a dial in flight is dialled again
- [B-96](docs/backlog/B-96-the-handshake-read-has-no-deadline.md) `[x]` - A peer that accepts the connection and then says nothing is never given up on
- [B-97](docs/backlog/B-97-the-announce-never-says-how-many-peers-it-wants.md) `[x]` - The announce never says how many peers it wants, and only one tracker is ever asked
- [B-98](docs/backlog/B-98-how-many-peers-does-this-client-meet.md) `[x]` - How many peers does this client meet? Measure it against a reference client, then set the cap
- [B-99](docs/backlog/B-99-the-dht-is-off-and-its-reason-for-being-off-expired.md) `[x]` - The DHT is off by default, and the reason written beside the default has since come true

**Phase 2 — UI**

- [B-104](docs/backlog/B-104-the-settings-screen-cannot-hold-another-row.md) `[x]` - The settings screen is exactly full: an eleventh row pushes the tenth somewhere nobody can reach it
- [B-106](docs/backlog/B-106-per-file-priority.md) `[x]` - Per-file priority: which file of a torrent the picker fetches first
- [B-107](docs/backlog/B-107-dropping-a-torrent-does-nothing-on-macos.md) `[x]` - Dropping a .torrent on the window does nothing on macOS
- [B-115](docs/backlog/B-115-the-startup-check-runs-on-the-window-s-thread.md) `[x]` - The window draws its title bar and nothing else: the start-up check reads the disk on the AWT event thread
- [B-121](docs/backlog/B-121-sequential-does-not-serve-a-player.md) `[x]` - Sequential downloads a file front to back, and a player needs its end too
- [B-130](docs/backlog/B-130-the-interface-is-too-small.md) `[x]` - The whole interface wants to be 10–20 % larger
- [B-131](docs/backlog/B-131-the-window-has-no-minimum-size.md) `[x]` - The window has no minimum size, and the interface scale moved where it breaks
- [B-132](docs/backlog/B-132-left-goes-negative-when-files-are-skipped.md) `[x]` - left goes negative when files are skipped
- [B-134](docs/backlog/B-134-move-a-torrent-s-data.md) `[x]` - A torrent's data can be moved to another directory, keeping its progress
- [B-144](docs/backlog/B-144-the-toolbar-says-what-its-buttons-do.md) `[x]` - The toolbar's icon buttons say what they do on hover, and Force re-announce is one of them
- [B-145](docs/backlog/B-145-a-speed-says-its-unit.md) `[x]` - A speed says its unit: scaled B/s, KiB/s, MiB/s instead of a bare number of KiB/s
- [B-155](docs/backlog/B-155-the-desktop-distribution-ships-shrunk-classes.md) `[x]` - The desktop distribution ships its classes through ProGuard, and the preflight checks that image
- [B-156](docs/backlog/B-156-the-desktop-runtime-is-compressed.md) `[-]` - The desktop runtime is linked compressed, as the CLI's already is
- [B-157](docs/backlog/B-157-the-desktop-distribution-ships-an-aot-cache.md) `[x]` - The desktop distribution ships an AOT cache trained through its own launcher, and proves it maps
- [B-158](docs/backlog/B-158-conveyor-as-the-packager.md) `[-]` - Decide whether Conveyor replaces jpackage as the desktop packager
- [B-159](docs/backlog/B-159-the-screens-draw-through-a-kit-not-through-material.md) `[x]` - The screens draw through a kit of their own, and Material becomes one implementation of it
- [B-160](docs/backlog/B-160-oldge-ui-is-a-kit-the-window-can-wear.md) `[x]` - oldge-ui is a second kit, and the settings choose between the two
- [B-161](docs/backlog/B-161-the-material-kit-takes-its-colours-from-a-seed.md) `[x]` - The Material kit takes its colours from a seed the person picks, through MaterialKolor
- [B-162](docs/backlog/B-162-the-oldge-ui-kit-offers-its-skins.md) `[x]` - The oldge-ui kit offers its skins as its colour choice
- [B-164](docs/backlog/B-164-accent-text-reads-on-a-light-skin.md) `[x]` - Text drawn in the accent stays readable on a light skin: an accent-as-text role in the kit
- [B-165](docs/backlog/B-165-a-russian-name-is-drawn-in-the-rows-face.md) `[x]` - A Russian torrent name is drawn in the row's own face under the Material kit
- [B-166](docs/backlog/B-166-the-preflight-reruns-when-the-image-changes.md) `[x]` - The packaged preflight runs again whenever the image it checks changes
- [B-167](docs/backlog/B-167-a-seeded-scheme-keeps-its-states-apart.md) `[x]` - Every seeded scheme keeps the error row's tint and the accent apart from the surface and the warning
- [B-168](docs/backlog/B-168-oldge-skins-own-their-footnote-and-dialog-border.md) `[x]` - The oldge skins draw the footnote band and the dialog border from their own tokens
- [B-169](docs/backlog/B-169-a-number-field-shows-its-whole-value.md) `[x]` - A settings number field shows its whole value: "no limit" is not cut to "no lim"
- [B-170](docs/backlog/B-170-a-seeded-accent-is-not-the-error-colour.md) `[x]` - No offered seed and style draws the accent in the error's colour
- [B-171](docs/backlog/B-171-the-dmg-carries-an-aot-cache-trained-on-its-own-files.md) `[x]` - The .dmg carries an AOT cache trained on its own files
- [B-172](docs/backlog/B-172-the-msi-carries-an-aot-cache-trained-on-its-own-files.md) `[-]` - The .msi carries an AOT cache trained on its own files
- [B-173](docs/backlog/B-173-the-window-ships-unshrunk-and-without-an-aot-cache.md) `[x]` - The window ships its classes as compiled and no installer carries an AOT cache
- [B-176](docs/backlog/B-176-the-oldge-kit-draws-the-toolbar.md) `[x]` - The oldge kit draws the toolbar with its own buttons, icons and search bar
- [B-177](docs/backlog/B-177-the-oldge-kit-draws-the-banner-button-and-the-details-tabs.md) `[x]` - The oldge kit draws the banner's button and the details panel's tabs with its own components
- [B-178](docs/backlog/B-178-the-oldge-kit-draws-the-settings-dialogs-and-empty-state.md) `[x]` - The oldge kit draws the settings fields, the dialogs and the empty state's button with its own components
- [B-179](docs/backlog/B-179-a-long-default-squeezes-the-settings-label.md) `[x]` - A long default value squeezes the settings label into a column of letters
- [B-39](docs/backlog/B-39-compose-ui-desktop.md) `[x]` - Phase 2: a Compose Multiplatform desktop UI on the engine's StateFlow
- [B-46](docs/backlog/B-46-ui-theme-and-calibration.md) `[x]` - The theme: colour roles, type, and the desktop calibration
- [B-47](docs/backlog/B-47-torrent-row-and-states.md) `[x]` - The torrent row and its seven states
- [B-48](docs/backlog/B-48-main-window-shell.md) `[x]` - The main window: toolbar, column header, status bar, degraded banner
- [B-49](docs/backlog/B-49-details-panel.md) `[x]` - The details panel and its four tabs
- [B-50](docs/backlog/B-50-add-torrent.md) `[x]` - Add torrent: the dialog, the drop target, the clipboard magnet
- [B-51](docs/backlog/B-51-empty-and-settings.md) `[x]` - The empty state and the settings screen
- [B-52](docs/backlog/B-52-ui-on-the-real-engine.md) `[x]` - The UI on the real engine, not on a fixture
- [B-53](docs/backlog/B-53-feature-ui-document.md) `[x]` - The feature document the phase 2 epic names
- [B-54](docs/backlog/B-54-many-torrents.md) `[x]` - More than one torrent in one process
- [B-55](docs/backlog/B-55-magnets-in-the-window.md) `[x]` - Magnets in the window, not only on the command line
- [B-56](docs/backlog/B-56-dead-toolbar-controls.md) `[x]` - Controls that reported themselves and nobody listened
- [B-57](docs/backlog/B-57-a-paused-torrent.md) `[x]` - A paused torrent, which the engine does not have
- [B-58](docs/backlog/B-58-remove-a-torrent.md) `[x]` - Removing a torrent, and the dialog the ellipsis promises
- [B-59](docs/backlog/B-59-force-re-check.md) `[x]` - Force re-check: verifying a torrent that is already running
- [B-60](docs/backlog/B-60-two-torrents-one-path.md) `[x]` - Two torrents saving to the same file, and nothing that notices
- [B-61](docs/backlog/B-61-appframe-title-bar.md) `[x]` - The title bar the design draws, which is not the operating system's
- [B-62](docs/backlog/B-62-dead-controls-on-two-more-screens.md) `[x]` - Controls on two more screens that reported nothing, and the guard that missed them
- [B-63](docs/backlog/B-63-joining-the-dht-at-runtime.md) `[x]` - Joining the DHT from the settings screen, not from a restart
- [B-64](docs/backlog/B-64-a-click-waited-for-the-tick.md) `[x]` - A click waited for the tick
- [B-65](docs/backlog/B-65-sequential-download.md) `[x]` - Sequential download, which the add dialog offers and the picker does not do
- [B-66](docs/backlog/B-66-the-filter-field.md) `[x]` - The filter field, which is a box with the word Filter in it
- [B-67](docs/backlog/B-67-per-file-selection.md) `[x]` - Per-file progress and choosing which files to fetch
- [B-68](docs/backlog/B-68-the-peers-list.md) `[x]` - The peers list, which the session counts and does not name
- [B-69](docs/backlog/B-69-the-trackers-list.md) `[x]` - A status per tracker, and re-announcing by hand
- [B-70](docs/backlog/B-70-settings-reach-a-running-session.md) `[x]` - Settings that reach a running session
- [B-71](docs/backlog/B-71-settings-that-survive-a-restart.md) `[x]` - Settings that survive a restart
- [B-72](docs/backlog/B-72-the-keyboard-map.md) `[x]` - The keyboard map the empty state advertises
- [B-73](docs/backlog/B-73-drop-and-paste.md) `[x]` - Dropping a file on the window, and a magnet on the clipboard
- [B-74](docs/backlog/B-74-resizing-the-details-panel.md) `[x]` - Dragging the details panel's edge
- [B-75](docs/backlog/B-75-the-window-below-800dp.md) `[x]` - The window below 800 dp
- [B-76](docs/backlog/B-76-the-last-dead-controls.md) `[x]` - The copy button, Show it, and the add dialog's ticks
- [B-77](docs/backlog/B-77-the-rate-column-reads-zero.md) `[x]` - The rate column reads zero while the torrent is downloading
- [B-78](docs/backlog/B-78-nothing-runs-the-packaged-application.md) `[x]` - Nothing runs the packaged application
- [B-81](docs/backlog/B-81-the-torrent-list-survives-a-restart.md) `[x]` - The list of torrents survives a restart
- [B-82](docs/backlog/B-82-an-installer-per-platform.md) `[x]` - An installer per platform, and the version that stops one
- [B-83](docs/backlog/B-83-autostart-and-its-setting.md) `[x]` - Starting with the operating system, and the setting that says so
- [B-84](docs/backlog/B-84-torrent-files-open-with-the-client.md) `[x]` - A .torrent opens with the client, on all three platforms
- [B-85](docs/backlog/B-85-open-a-file-from-the-files-tab.md) `[x]` - Double-clicking a file in the Files tab opens it
- [B-86](docs/backlog/B-86-the-application-icon.md) `[x]` - The application icon, drawn from its own geometry
- [B-88](docs/backlog/B-88-closing-to-a-tray.md) `[x]` - Closing the window leaves the client running, in a tray
- [B-89](docs/backlog/B-89-sequential-on-a-running-torrent.md) `[x]` - Sequential download can be turned on for a torrent that is already running
- [B-90](docs/backlog/B-90-the-msi-did-not-upgrade.md) `[x]` - An .msi did not upgrade the installed client
- [B-91](docs/backlog/B-91-the-tray-menu-is-not-hdpi.md) `[x]` - The tray's right-click menu is not scaled on a HiDPI display
- [B-92](docs/backlog/B-92-the-window-samples-three-times-a-second.md) `[x]` - The window samples three times a second, off the thread that draws it
- [B-93](docs/backlog/B-93-opening-a-downloaded-executable.md) `[x]` - Double-clicking a downloaded executable, and the warning Windows never gets to show
- [B-94](docs/backlog/B-94-a-degraded-session-cannot-recover.md) `[x]` - A degraded session cannot recover, and the DHT table was the thing degrading it

**Phase 3 — Mobile**

- [B-79](docs/backlog/B-79-the-windows-state-outlives-its-composition.md) `[x]` - The window's state outlives its composition

**Phase 3 — Server**

- [B-108](docs/backlog/B-108-an-mcp-server-for-agents.md) `[x]` - An MCP server, so an agent can drive the client
- [B-117](docs/backlog/B-117-one-client-for-the-window-and-the-agent.md) `[x]` - One client for the window and the agent: `kachok mcp` attaches to the running window
- [B-120](docs/backlog/B-120-two-launches-have-no-order-between-them.md) `[x]` - A test asserts the order of paths handed over by two separate launches, which nothing guarantees
- [B-133](docs/backlog/B-133-sequential-over-mcp-and-the-wire.md) `[x]` - Sequential over MCP and the wire, and an agent's changes kept by the window
- [B-136](docs/backlog/B-136-one-engine-that-remembers.md) `[x]` - A headless kachok mcp is the machine's one engine, and it remembers its torrents
- [B-137](docs/backlog/B-137-mcp-frames-are-utf8.md) `[x]` - MCP frames are UTF-8 on every platform
- [B-138](docs/backlog/B-138-one-missing-drive-does-not-cost-the-list.md) `[x]` - One torrent whose drive is not there does not cost the rest of the list
- [B-140](docs/backlog/B-140-the-snapshot-reports-the-network.md) `[x]` - The snapshot reports the DHT from its own table and says whether the port is mapped
- [B-141](docs/backlog/B-141-the-dht-table-fills-and-a-resume-looks-at-once.md) `[x]` - The DHT table fills near its own id, a resumed torrent looks at once, and a lookup ends at the closest eight
- [B-142](docs/backlog/B-142-the-dht-answers.md) `[x]` - The DHT answers the queries other nodes send it
- [B-143](docs/backlog/B-143-the-dht-port-is-the-listeners-and-forwarded.md) `[x]` - The DHT listens on the listener's port, and the router is asked to forward it for UDP

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

**M9 exists because an owner compared this client against another one, and the comparison has not been repeated.** Every item in the stage names a mechanism read out of the code — a dial loop that
only runs on events, a handshake with no deadline, an announce that asks for nothing, three peer
sources and one transport that are missing. None of them names a number, so the order between them
is a hypothesis and [B-98](docs/backlog/B-98-how-many-peers-does-this-client-meet.md) is what turns
it into one. Fixing the cheap items first is right regardless; *claiming* they were the gap is not,
until the measurement says so.

**"Done" means the build said so.** [B-01](docs/backlog/B-01-gradle-skeleton-builds-on-jdk-25.md)
is the only closed item and it closed on a green build, not on the files existing;
[B-02](docs/backlog/B-02-ci-runs-build-and-docs-gates.md) stays open although both workflows are
written, because neither has run.
