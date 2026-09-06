---
id: B-87
title: "Phase 3: a headless server with a web face, installable on a box that is always on"
status: open
priority: P3
size: XL
stage: phase-3-server
epic: feature-ui
blocked_by: [B-80]
---

# B-87 — Phase 3: a headless server with a web face, installable on a box that is always on

A torrent client is worth running on the machine that is always on rather than on the laptop that
is closed. [B-40](B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md) built the half that makes
that possible — `kachok serve` is the engine with a socket instead of a window, and a browser has
already driven a real download through it. What it is not is something a person can install on the
box in the cupboard and open from their phone.

Three things stand between those, and each is a decision rather than a task.

## 1. Which box, and the number that decides it

**A consumer router is out on memory, and this is measured rather than guessed.** Research §1.2d,
`:cli:collectorBench` over a gigabyte: at the `-Xmx128m` the launcher pins, peak resident is
**180–186 MB**. A router running OpenWrt on mips or armv7 has 64–256 MB *in total* and no
maintained OpenJDK for musl — the JVM engine cannot run there at all, whatever the heap says.

| Box | Runs the JVM engine today | What it costs |
|---|---|---|
| OpenWrt on mips/armv7, 64–128 MB | **no** — no JVM for musl/mips, and the RSS alone exceeds the box | a native engine, §2 |
| OpenWrt/x86 or aarch64, 512 MB+ | yes | 63 MB unpacked (§1.3: 35 MB image + jars, 27.9 MB cache) |
| a NAS, a Raspberry Pi 4+, a small VPS | yes | the same 63 MB, and Docker is already there |

- **The decision this needs.** Which of those two rows this item is for. The second is a
  distribution and a service file; the first is §2 and a different project.
- Rejected in advance: promising "runs on a router" without saying which. The word covers a £30 box
  with 64 MB and a £300 box with 4 GB, and only one of them is a JVM host.

## 2. Whether the engine gets a native target

Kotlin/Native would answer the first row, and phase 1 left the door open on purpose — common code
sees I/O through interfaces and the platform primitives are `expect`/`actual`. The door is open; the
room is not empty.

What is JVM-specific and measured *as* JVM behaviour: virtual threads (§1.1), `MessageDigest`'s
SHA-1 intrinsic, `FileChannel`'s gathering writes and positional writes (§1.3a), the FFM mapping of
the upload path (B-30), the AOT cache (§1.2), and every collector number in §1.2d. A native engine
does not inherit one of those measurements — it inherits the *questions*, and has to answer them
again on its own runtime.

- **The decision this needs.** Whether that is phase 3's work or a phase of its own. It is not a
  port; it is a second engine with the same protocol.
- Not covered either way: the desktop and mobile builds keep the JVM engine. This is about the box
  in the cupboard.

## 3. A socket on a network, which is not the socket that exists

**The current security model was chosen for a page on the same machine and does not survive this
item.** [B-40](B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md) binds `127.0.0.1` with no
authentication because the client is the person; a box on the LAN inverts every clause of that. This
client can delete files — `removeWithData` is in the protocol — and can be told to save anywhere the
process can write. On a shared network, unauthenticated, that is a file-deletion primitive with a
web interface.

So this item **reopens the security question rather than inheriting an answer**:

- authentication, and where the secret lives on a box with no keychain;
- `--bind`, which does not exist yet: `serve` binds loopback and nothing else;
- TLS or not, on a LAN, and what a self-signed certificate does to the page's own origin;
- what a reverse proxy in front of it is allowed to be trusted about.

- Rejected in advance: shipping the loopback decision with a `--bind` flag on it. A flag that turns
  a safe default into an unsafe one, with the sentence explaining why in a `--help` nobody reads, is
  how this goes wrong — the authentication has to arrive *before* the bind address does.

## 4. The page has to come from somewhere

`serve` speaks WebSocket and serves no files; the page that drove it came from a `python3 -m
http.server` beside it. There is no second server on the box in the cupboard, so the backend serves
its own page — which also collapses the `Origin` question, because the page and the socket are then
the same origin.

**And the page itself is [B-80](B-80-the-ui-moves-to-commonmain.md).** Every screen in `:ui` is in
`desktopMain` and reaches for `java.awt`, `java.nio.file.Path` and a desktop-only window frame. This
item and B-40 wait on the same thing, and it is the only thing they wait on.

- Not covered: a mobile-shaped layout. The desktop window's own narrow mode
  ([B-75](B-75-the-window-below-800dp.md)) is what a phone would get first, and whether that is
  enough is a question for whoever opens it on a phone.

## 5. Installing it

Not `jpackage`: it makes desktop installers, and a headless service on a NAS is not one.

- **The decision this needs.** A tarball with a systemd unit, an OCI image, or both. Docker is what
  a NAS already has and what a person will reach for; a tarball is what works on a box that has no
  Docker. The run-time image is already the artifact either would carry.
- Not covered: publishing that image anywhere. Where a build is published is a decision with an
  account attached.

- AC: one documented command installs and starts the server on the chosen box; a browser on another
  machine on the same network opens its page, sees the torrents and adds one; an unauthenticated
  request is refused; the client survives a reboot of the box.
- Anchors: `cli/src/main/kotlin/ru/workinprogress/kachok/cli/serve/`,
  `wire/src/commonMain/kotlin/ru/workinprogress/kachok/wire/Protocol.kt`,
  `docs/services/cli.md` §2.
