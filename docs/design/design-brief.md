# kachok — design brief for phase 2 (desktop UI)

> Who this is for: the designer of the Compose Multiplatform UI ([B-39](../backlog/B-39-compose-ui-desktop.md)).
> What it rests on: phase 1 is closed — the headless engine downloads and seeds real torrents; the
> numbers in this brief are the engine's, not estimates. Where the UI needs something the engine
> does not expose yet, the brief says **planned** and names the item that will add it.

## 1. The product in one paragraph

kachok is a BitTorrent client written in Kotlin on JDK 25. Its selling point is not features; it
is that it is small, honest and fast: a 128 MB heap, a hot path that allocates nothing, a
distribution that starts in ~50 ms. The UI should feel the same way — a tool, not a dashboard.
Reference points: Transmission for restraint, qBittorrent for what a power user expects to find,
neither for looks. Not a media centre, not a search engine, not a "downloads manager".

## 2. Users

- **Primary:** a technical person on macOS or Linux who downloads a few torrents a week and keeps
  a handful seeding. Wants to add a torrent in one gesture, glance at progress, and forget it.
- **Secondary:** the same person diagnosing a stalled download. Needs to see *why* — this is where
  most clients fail, and where kachok's engine exposes more than others do (§4).
- **Later (phase 2b / 3):** the same person on a phone or in a browser, controlling the desktop
  client remotely. Not designed now, but the layout must not preclude it (§6).

## 3. Platforms and toolkit

- **Desktop first:** macOS and Linux; Windows untested but not excluded.
- **Compose Multiplatform 1.12 + Material 3.** Design on the Material 3 design kit; every
  component should map to an M3 component. A component that needs custom drawing is fine but must
  be flagged as such — it is implementation cost, and there is one developer.
- **One UI codebase for three shells:** in-process desktop app (phase 2), browser build talking
  to the engine over a local socket ([B-40](../backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md)),
  Android/iOS remote ([B-41](../backlog/B-41-android-and-ios-targets.md)). So: adaptive, not
  three designs — one layout that reflows at ~1200, ~800 and ~400 px.
- **Light and dark**, following the system. No other themes.
- Native window chrome and menu bar on macOS; Material components inside the window.

## 4. What the engine exposes — the data the UI can show

The UI reads one `SessionState` per torrent, refreshed once a second (the engine's tick), and
sends commands. Source of truth: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/SessionState.kt`.

**Per torrent, today:**

| Field | What the user should read from it |
|---|---|
| `name`, `totalLength` | title and size |
| `completedPieces` / `pieceCount`, `downloaded`, `left` | progress; note `left` is not `total − downloaded` after a resume |
| `uploaded` | seeding contribution; ratio = `uploaded / totalLength` |
| `connectedPeers` | peers we are talking to |
| `unchokedPeers` | peers that are actually *allowed* to send us data — the number that explains a stall |
| `outstandingRequests` | requests in flight; zero with peers connected = stall |
| `knownPeers`, `extendedPeers`, `dhtNodes` | swarm size, peers with extensions, DHT health (0 = DHT off) |
| `hashFailures` | corrupt pieces received and thrown away; non-zero is unusual and worth showing |
| `verifiedPieces` / `verifyingOf` | the start-up check of data already on disk ("Checking 120 / 449") |
| `trackerError`, `lastPeerError`, `sessionError` | the last complaint from each source, verbatim |
| `isComplete` | download finished; the torrent is now seeding |

**Not in the state today, planned for phase 2 (the designer should design them; the engine will
grow them — tracked as new items when B-39 reopens):**

- download and upload **speed** (the CLI derives it from `downloaded` deltas; the state will carry
  it directly), ETA
- a **per-peer list** (address, client name from BEP 10's `v`, choked/interested flags, rates)
- a **per-file list** with progress and selection (which files to download)
- a **per-tracker list** with last announce and status
- torrent lifecycle: **paused / stopped** as a state distinct from running

**Commands, today:** add peers, accept an incoming peer, stop. **Planned:** add torrent (file or
magnet), pause / resume, remove (keep data / delete data), change rate limits and settings at
run time, set file priorities, force re-check, re-announce.

**Configuration** (`SessionConfig`, same file): max peers, pipeline depth, listening port, upload
and download limits, DHT on/off. Defaults are measured, so the settings screen should show the
default beside the field rather than a blank.

## 5. Screens for phase 2, v1

1. **Main window — torrent list.** A dense table, not cards: name, size, progress, down/up
   speed, peers as `connected / unchoked`, ratio, ETA, state. Sortable. Multi-select. Context
   menu and toolbar with the same actions. Status bar with session totals (down/up speed, DHT
   nodes, port state).
2. **Torrent states in the list** — each needs a distinct visual: *fetching metadata* (a magnet:
   no name, no size yet), *checking* (start-up verification with its own progress), *downloading*,
   *seeding*, *paused*, *stopping* (a clean stop can take up to ten seconds: tracker, peers,
   flush, resume record), *error* (`sessionError` non-null — degraded, needs a person).
3. **Add torrent.** Drag-and-drop of a `.torrent` onto the window, file picker, magnet paste
   (also from the clipboard on focus). Then: destination folder, file selection (planned), start
   immediately or paused.
4. **Details panel** (bottom or side, resizable; collapses on narrow widths): *Overview* (every
   field in §4, the error lines verbatim, the info hash copyable), *Files*, *Peers*, *Trackers*
   (the last three planned).
5. **Settings.** Download folder, listening port (default 6881, probes 6881–6889), max peers,
   rate limits, DHT toggle — **off by default**, with an explanation: joining the DHT announces
   this machine to strangers, and the client only needs it for magnets without trackers.
6. **Empty state.** First launch: one call to action (add a torrent), nothing else.
7. **Remote mode — placeholder only.** A "connect to a kachok engine" screen for the browser and
   mobile builds. Sketch, do not finish.

## 6. Principles the design should hold

- **Every state is visible.** The engine was built on the rule that "no peers, no reason" is a
  state nobody can act on. The UI must not hide `trackerError`, `lastPeerError`, `hashFailures`
  or the unchoked count behind a hover. Show the diagnosis where the symptom is.
- **Numbers are first-class.** Tabular figures, consistent units (KiB/MiB/GiB, KiB/s), right
  alignment, no number that reflows the row when it changes.
- **1 Hz is the refresh rate.** Progress and speeds update once a second; animations should be
  designed for that, not for 60 fps.
- **Density over decoration.** Compact M3 density on desktop. A user with forty torrents should
  see all forty.
- **Keyboard-complete.** Every action in the list has a shortcut; the list is navigable without a
  mouse.
- **Adaptive, not responsive-by-accident.** Define the three widths (§3) and what collapses at
  each: details panel → sheet, table → list rows, toolbar → overflow menu.
- **No emoji, no illustrations in the working screens.** The empty state may have one.

## 7. Non-goals for v1

Torrent search, RSS, a media player, sequential-download streaming, scheduler, themes beyond
light/dark, plugins, a web UI served by the app (the browser build is a separate deliverable).

## 8. Deliverables

- Figma file on the Material 3 design kit, light and dark, at the three widths.
- The main window in every torrent state listed in §5.2, plus empty and error.
- Add-torrent flow, details panel (all four tabs), settings.
- Component inventory: which M3 component each element is; custom ones flagged.
- App icon (macOS, Linux; 1024 px master) and the tray/menu-bar glyph.
- Token file (colours, type scale, spacing) exportable to Compose.

## 9. Glossary

- **Torrent / metainfo** — the `.torrent` file; describes files and pieces. **Magnet** — a link
  that names a torrent by hash and carries none of it; the client fetches the metainfo from peers.
- **Info hash** — the torrent's 40-hex-character identity.
- **Piece / block** — a torrent is verified in pieces (256 KiB–4 MiB); pieces move in 16 KiB
  blocks.
- **Peer** — another client. **Seed** — a peer with everything. **Leech** — a peer still
  downloading.
- **Choked** — a peer refuses to send us data right now. **Unchoked** — it will. The engine
  unchokes at most four peers itself.
- **Tracker** — a server peers register with. **DHT** — a trackerless peer directory.
- **Announce** — the client telling a tracker it exists. **Check / verify** — re-hashing what is on
  disk.
- **Ratio** — uploaded ÷ downloaded.

## 10. Open questions for the designer

1. Details panel: bottom (Transmission-style) or right (IDE-style)? Both reflow differently at
   800 px.
2. Is the stall diagnosis (`connected / unchoked / outstanding`) a column, a status-bar cell, or a
   badge on the progress bar?
3. How much of the Material 3 look survives on macOS without feeling like an Android app — density,
   corner radius, elevation? Propose a calibration, not a full custom theme.
4. Speeds as numbers, sparklines, or both?
