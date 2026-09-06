---
id: B-40
title: "Phase 2: the wasmJs UI is a client of the JVM headless engine"
status: open
priority: P3
size: L
stage: phase-2-ui
blocked_by: [B-80]
---

# B-40 — Phase 2: the wasmJs UI is a client of the JVM headless engine

A browser has no TCP or UDP sockets, so a wasmJs build of the *engine* cannot exist (research
Risk 4). This item was opened as a question — remote engine or WebRTC peers — and answered by the
owner on 2026-09-05: **the JVM headless client is the backend; the browser build of the Compose UI
is its client.** The desktop build runs the engine in-process; the browser build talks to the same
engine over a socket.

- **The decision and its reason.** One engine, two front ends, one UI codebase: the UI reads a
  session state and sends commands, and whether those cross a process boundary is a transport
  detail. WebRTC peers would have been a different protocol with a different swarm, a second
  engine to maintain, and no access to the user's disk.
- Rejected: a browser-side engine over WebRTC (WebTorrent-compatible). It downloads from a
  different, smaller swarm, cannot write files, and duplicates everything phase 1 builds.
- What phase 1 owes this item: the engine's `StateFlow<SessionState>` and `Channel<Command>`
  ([B-17](B-17-session-orchestrator.md)) are a wire contract in waiting — plain data, no platform
  types, no callbacks — so that putting them behind a socket is serialisation, not redesign. The
  CLI ([B-18](B-18-cli-download-command.md)) is the process that grows into the backend, not a
  throwaway.
- Not covered until phase 2: the transport (a local WebSocket is the obvious candidate), the
  serialisation (kotlinx.serialization is in the shared catalog), authentication for a socket
  that is not only local, and how the browser build is served.

## What the desktop stage settled, and what is still a question (2026-09-05)

The desktop half closed — [B-46](B-46-ui-theme-and-calibration.md) through
[B-55](B-55-magnets-in-the-window.md) — and three of this item's assumptions are now facts rather
than intentions:

* **The wire contract held.** `SessionState` is still plain data with no platform types, no
  callbacks and no connections in it, and the desktop window reads nothing else. Putting it behind
  a socket is a serialisation task, which is what phase 1 was asked to leave possible.
* **The UI's own layer is transport-agnostic already.** `rowOf`, `detailsOf`, `statusOf`,
  `settingsOf` and `addFrom` are pure functions of a `SessionState`, a `Metainfo` or a
  `MagnetLink`, with no engine types beyond those and no suspension. A browser build that received
  those three over a socket would call the same functions unchanged; what is desktop-only is
  `App.kt`, `TorrentSet` and the file chooser.
* **The backend process exists.** `TorrentSet` is what would hold the sessions behind the socket,
  and it already routes an incoming peer by info hash — which a multi-torrent backend needs and a
  single-torrent CLI did not.

**Still the owner's to decide, and the reason this item stays open:** the transport, the
serialisation format, and what happens when the socket is not only local. Nothing in the desktop
work forces any of the three, and guessing one would put a security model in the repository that
nobody chose. Recorded here rather than started.

## The three answers, taken by the owner on 2026-09-06

| Question | Answer |
|---|---|
| transport | a WebSocket on loopback |
| serialisation | `kotlinx.serialization`, JSON |
| security | localhost only, no authentication |

**A correction to the third, made before building it and not after.** "Localhost only" does not mean
"only this machine's own programs": a WebSocket is *not* subject to the same-origin rule that keeps
an ordinary request from crossing into `127.0.0.1`, so a page on any site the person visits can open
`ws://127.0.0.1:<port>` and drive this client — add torrents, remove them with their data. The
cheapest thing that makes "localhost" mean what it is meant to mean is checking the `Origin` header,
which a browser always sends and which our own page's value is known: it refuses *other people's
pages*, not the person. That is not authentication and it is one line to remove. Recorded here so
that a later reader can see it was a decision rather than an oversight.

## What is being built, and what it waits on

**The wire is hand-rolled, and the reason is the artifact it goes in.** Ktor's server would be the
obvious answer, and the headless client's run-time image is a *measured* artifact whose module list
is guarded by `scripts/verify_runtime_image.sh` — pulling a server stack into it for one loopback
socket changes the thing phase 1 spent its measurements on. RFC 6455's server side, for text frames
between two programs, is a handshake and a frame codec.

The peer, however, is a **browser**, which this project does not control: it fragments, it pings, it
sends a close frame and it masks. So the codec is checked against implementations that are not this
one — the JDK's own `java.net.http.WebSocket` client, which is in the module list already, and a
real browser.

**The browser *page* waits on the UI moving to `commonMain`**, which is
[B-80](B-80-the-ui-moves-to-commonmain.md) and is filed for phase 3. Every screen in `:ui` is in
`desktopMain` today and half of them reach for `java.awt`, `java.nio.file.Path` and a desktop-only
window frame. That dependency is not recorded anywhere else and it is the one thing between this
item and a page in a browser.

- AC, the wire — **met on 2026-09-06**: the headless client serves a WebSocket on loopback that a
  browser can connect to; a snapshot of every torrent arrives as JSON and a command sent back
  reaches the engine; a page from another origin is refused; the codec is verified against an
  implementation that is not this one.
- AC, the page — **not met, and blocked**: a Compose build of this UI, in a browser, showing the
  same list the desktop window shows. It needs the screens to be in `commonMain`, which is
  [B-80](B-80-the-ui-moves-to-commonmain.md).
- Anchors: [`wire/src/commonMain/kotlin/ru/workinprogress/kachok/wire/Protocol.kt`](../../wire/src/commonMain/kotlin/ru/workinprogress/kachok/wire/Protocol.kt),
  [`cli/src/main/kotlin/ru/workinprogress/kachok/cli/serve/`](../../cli/src/main/kotlin/ru/workinprogress/kachok/cli/serve),
  [`docs/services/cli.md`](../services/cli.md).

## The wire is built. The page is not, and it is blocked.

`kachok serve` runs the engine with no window and a WebSocket on loopback. `:wire` is the contract —
plain `@Serializable` data, one dependency, no engine — because the client that will read it cannot
depend on a module full of sockets.

### Driven from a real browser on 2026-09-06

Not a claim about a browser: a page served from `http://127.0.0.1:8123`, Chrome, this backend.

| What was done from the page | What happened |
|---|---|
| `new WebSocket('ws://127.0.0.1:8765')` | connected; a snapshot arrived before the page asked for anything |
| `send({type:'addTorrent', base64:…})` | the torrent was added, 4 MB came off the local swarm, `sha256 -c` on the file says `OK` |
| watched the snapshots | `completedPieces` climbed 0→16, `downBytesPerSecond` 209 715 |
| `send({type:'pause', …})` | `paused: true`, `connectedPeers: 0` on the next snapshot |
| the same page from `http://localhost:8123` | **refused** — zero snapshots, close code 1006 |

That last row is the security decision working: `localhost` and `127.0.0.1` are different origins, so
a page that is not the one this backend was told about cannot drive it.

### Two defects the live check found that no test of mine would have

**`kotlinx.serialization` omits fields equal to their defaults**, so a torrent with no peers has no
`peers` key at all — and JavaScript reads `undefined` where a list was promised. The very first
browser to connect threw on `snapshot.torrents.length`. Every client would have had to know every
default in the protocol to defend against that; `encodeDefaults = true` is a few dozen bytes a
second on loopback and the cheaper half of the trade. A JVM test would never have noticed: the
decoder fills the defaults back in.

**The snapshot carried a wall clock.** `at = System.currentTimeMillis()` was there so a client could
tell a stale snapshot from a still one — and this repository's own lint rule refused it, correctly: a
client comparing that to its own clock compares two machines' clocks, and a protocol whose whole
point is that the client may be somewhere else cannot assume they agree. It is a **sequence number**
now, which needs no clock and no agreement.

### The codec, and why it is written here

Ktor's server is the obvious answer and the wrong one for this artifact: the headless client's
run-time image is measured, and its module list is guarded by `scripts/verify_runtime_image.sh`.
RFC 6455's server half — for text frames — is a handshake and a frame codec. But the peer is a
*browser*, which this project does not control, so it is checked against implementations that are
not this one: the JDK's `java.net.http.WebSocket`, which masks, fragments, pings and expects a close
to be echoed, and then a real browser.

### What is left, and it is not this item's fault

**The browser *page* waits on [B-80](B-80-the-ui-moves-to-commonmain.md)**, the UI moving to
`commonMain` — which is filed for phase 3 and blocked on [B-79](B-79-the-windows-state-outlives-its-composition.md).
Every screen in `:ui` is in `desktopMain` today, and the ones that matter reach for `java.awt`,
`java.nio.file.Path` and a desktop-only window frame. That dependency was not recorded anywhere
before this item was built, and it is the single thing between a working wire and a window in a
browser. `:wire` declares only a `jvm()` target for the same reason: a `wasmJs` target with nothing
built on it is a target that lies.

**Automated:** `cli/src/test/.../serve/WebSocketServerTest.kt` — eleven cases against the JDK's own
client: masking in both directions, fragmentation, an eight-byte length, ping, close, the origin
rules, a plain HTTP request, and that the listener is on loopback and nowhere else ·
`cli/src/test/.../serve/BackendTest.kt` — nine, including a real download from `:swarm` driven
entirely over the socket, and every refusal answered in words rather than in silence.
