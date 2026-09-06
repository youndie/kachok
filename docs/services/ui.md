---
id: ui
title: ui (Compose Multiplatform desktop application)
type: service
module: ui
tech_stack: [Kotlin 2.4 JVM, JDK 25, Compose Multiplatform 1.12, Material 3, viddik 0.4]
owner: unassigned
depends_on:
  - engine
publishes:
  - "a desktop window (phase 2; no installer yet)"
---

# ui

## 1. Responsibility

The phase-2 surface: a desktop window on the same engine the headless client runs. It samples one
`StateFlow<SessionState>` once a second, turns each sample into rows, and draws the design's main
window around them — toolbar, degraded banner, sortable column header, torrent list, status bar.

It deliberately contains **no protocol logic and no wiring**. The engine is built by
`TorrentRuntime`, which is [engine](engine.md)'s and which the CLI uses unchanged; what is here is
what a window does with the result. A field this UI wants and the engine does not have is either
derived here and said so (the two rates), or drawn as *planned* and said so (`paused`).

## 2. API contracts

No network API. The contract is the command line it is launched with and the window it opens:

```
kachok-ui <file.torrent> [directory]
```

There is no add-torrent dialog yet ([B-50](../backlog/B-50-add-torrent.md)), so the argument is the
same one the CLI takes — which is what keeps the two surfaces comparable while they are being
compared.

## 2a. Code anchors

| File | What is there |
|---|---|
| `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt` | `main`, the window, and the loop that samples every session and stops them cleanly on close |
| `.../ui/theme/` | the eight roles, the `warning` M3 does not ship, the three bundled families, the 4 dp calibration |
| `.../ui/icons/Icons.kt` | the twenty-one Material Symbols codepoints and the subset font they index into |
| `.../ui/list/TorrentRow.kt` | the nine columns at the design's widths, and the row's own hairline |
| `.../ui/list/RowColors.kt` | which colour every cell of every state is, as a function of the scheme rather than of a composition |
| `.../ui/main/` | the toolbar, the banner, the column header, the status bar, and the window that stacks them |
| `.../ui/details/DetailsPanel.kt` | the right-hand panel, its four tabs, and what the three empty ones say instead of rows |
| `.../ui/session/DetailsFrom.kt` | `SessionState` as those fields, including the one the design badges `planned` |
| `.../ui/add/AddTorrent.kt` | the add dialog, the drop overlay and the clipboard prompt |
| `.../ui/session/AddFrom.kt` | a `Metainfo` or a `MagnetLink` as what the dialog is allowed to say |
| `.../ui/settings/Settings.kt` | one screen in the window, with the measured default beside every field |
| `.../ui/session/SettingsFrom.kt` | those defaults, read out of `SessionConfig` rather than repeated |
| `.../ui/main/EmptyState.kt` | what a new install looks like: three ways in, all of them named |
| `.../ui/session/Sorting.kt` | the column header's order, taken on the values and never on the cells |
| `.../ui/theme/PathText.kt` | a directory cut from the front, measured rather than guessed |
| `.../ui/session/Figures.kt` | three significant figures for a size, grouped thousands for a rate |
| `.../ui/session/SessionRow.kt` | `SessionState` as a row, plus the lifecycle the engine has no field for |
| `ui/src/desktopTest/.../session/AppDownloadTest.kt` | a real download from `:swarm`, sampled the way the window samples it |
| `ui/src/desktopTest/snapshots/` | the viddik goldens, recorded on macOS and verified by `make check` |
| `scripts/capture_design.sh` | re-renders `docs/design/screens/` out of the design document they are compared against |

## 3. How it is built

Compose Multiplatform with a single `jvm("desktop")` target — the name is load-bearing, because
viddik's Gradle plugin reads it to decide which `ksp*` configuration its processor goes on.

Everything the design calls custom is drawn rather than configured: nine columns are a `Row` of
fixed-width cells because `ListItem` cannot do nine, the progress cell is a 4 dp box because
`LinearProgressIndicator` brings a wave that fights a figure updating at 1 Hz, and three toolbar
controls are drawn because `FilledTonalButton` and `OutlinedTextField` cannot be 28 dp high with
what they reserve. Everything else is stock M3.

The colour rule lives outside the composables (`RowColors.kt`) so that "is this cell `#BEC9C6` or
`#DDE4E1`" is a question a test answers rather than a screenshot.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [engine](engine.md) | `TorrentRuntime`, `SessionState`, everything below the window |
| Module | `:swarm` (test only) | the tracker and seeding peer the end-to-end download runs against |
| Library | Compose Multiplatform 1.12 + Material 3 | the toolkit and the eight roles the design names |
| Library | viddik 0.4 | `viddikRecord` / `viddikVerify`, the goldens |
| Library | AppFrame 0.1.20 | the title bar the design draws, with the host's own window controls |
| Fonts | Source Serif 4, Archivo, JetBrains Mono, Material Symbols Rounded | bundled, not asked of the machine |

## 5. Infrastructure and deploy

* `./gradlew :ui:run` opens the window against the *full* JDK. Nothing a run like that does proves
  anything about the shipped application: the distribution carries a `jlink` runtime cut down to the
  modules `nativeDistributions` names, and a module missing from it is a `NoClassDefFoundError` on
  somebody else's machine ([B-78](../backlog/B-78-nothing-runs-the-packaged-application.md)).
* The JVM flags are the CLI's three, pinned in `ui/build.gradle.kts` for the same reason: a UI does
  not get to run a different VM from the one every measurement was taken on.

### Building an installer

`jpackage` cannot cross-compile, so each is produced on its own platform. One command each, and one
format each — the host picks from `targetFormats`:

| Platform | Command | Output | Prerequisite |
|---|---|---|---|
| macOS | `LOCAL=1 ./gradlew :ui:packageDistributionForCurrentOS` | `ui/build/compose/binaries/main/dmg/kachok-1.0.0.dmg` | — |
| Linux | `~/.claude/bin/wsl-run './gradlew :ui:packageDeb'` | `.../deb/kachok_0.1.0_amd64.deb` | `fakeroot` |
| Windows | `gradlew.bat :ui:packageMsi` | `.../msi/kachok-0.1.0.msi` | none — the Compose plugin downloads WiX 3.11.2 into `~/.gradle/compose-jb/` and passes it as `WIX_PATH`; `compose.desktop.application.downloadWix=false` turns that off |

A `.torrent` is registered to this client by all three installers, and the client is a **single
instance**: a second launch hands its path to the running one over a loopback socket named in
`<config>/instance` and exits. On Linux the `.desktop` entry `jpackage` writes has no `%f` and would
therefore never be given the file, so `:ui:patchDesktopEntry` adds one and fails the build if it
could not — see [B-84](../backlog/B-84-torrent-files-open-with-the-client.md).

The macOS bundle says **1.0.0** while the project is at 0.1.0, and that is deliberate: Apple refuses
a `CFBundleShortVersionString` whose first component is zero, so `0.1.0` cannot be packaged on macOS
at all. The override is on `macOS { }` alone; the `.deb` carries the project's own number.

## 6. Local setup

```bash
./gradlew :ui:run --args="example.torrent ~/Downloads"
```

Recording and checking the goldens, which must happen on the machine that has the rasteriser they
were recorded with:

```bash
LOCAL=1 ./gradlew :ui:viddikRecord
```

## 7. Configuration, and where the state lives

Two command-line arguments — a `.torrent` and a directory — and nothing read from the environment.
Everything else the client remembers is in the platform's own configuration directory, written by
`ui/src/desktopMain/.../session/StoredPreferences.kt` and `StoredTorrents.kt`:

| Platform | Directory |
|---|---|
| macOS | `~/Library/Application Support/kachok/` |
| Windows | `%APPDATA%\kachok\` |
| Linux | `$XDG_CONFIG_HOME/kachok/`, or `~/.config/kachok/` |

| What | File | Written when |
|---|---|---|
| the settings | `settings.properties` | half a second after the last keystroke in the settings screen |
| the list of torrents | `torrents/<info hash>.torrent` — a **copy**, not a pointer | a torrent is added |
| each torrent's own choices | `torrents/<info hash>.properties` — name, directory, paused, unwanted files, sequential | added, paused, resumed |
| which pieces are verified | `<name>.<8 hex>.resume`, **beside the data** and not here | the engine's own rule ([B-23](../backlog/B-23-atomic-resume-file.md)) |

Every one of these is written to a neighbour and moved into place: a file half-written by a process
that was killed reads as nonsense on the next start, and the move is the one operation the
filesystem will not do halfway.

**The copy is the point.** A pointer to the file somebody added turns "I tidied my Downloads
folder" into "my client forgot what it was doing", and a magnet has no file to point at in the
first place — after BEP 9 the metainfo exists only in memory. `MetainfoWriter` splices the info
dictionary in as bytes rather than re-encoding it, so the copy has the same info hash as the
original and therefore claims the same resume record; a canonical re-encoding would silently make
it a different torrent for every `.torrent` whose keys are not sorted, and those circulate.

## 8. Quirks

* **A golden is a picture of one rasteriser's output.** Recording on macOS and verifying on the
  Linux build machine compares two renderers and calls the difference a regression, so
  `verifyOnCheck` is true only on macOS — and `make check` runs `:ui:viddikVerify` there, so what
  is off is the duplicate rather than the gate.
* **A golden recorded on the Linux replica does not survive.** `mutagen` flushes the mac's tree
  over it before the next command, so the picture is written and then erased with nothing failing.
* **A 12 px figure never reaches its own colour.** Antialiasing means the brightest pixel in a
  small glyph is 10–15 % short of the colour it is drawn in, so per-cell colours are asserted
  through `rowColor` and not by counting pixels in the golden.
* **`#151C1A` is a border, `#161D1B` is a background.** All 72 of the first appear in a `border`
  and all 11 of the second in a `background`. Reading them as one value put the column header and
  the status bar a shade too dark for two items.
* **CSS `width` is measured inside the padding and the border.** The design's filter field says
  `width: 200px` and occupies 220 on screen. It is the only place in the document where the two
  systems disagree about what a number means, and the only place this compensates for it.
* **Material Symbols reaches a glyph by ligature, and that cannot be subset.** The substitution
  table maps letters to all four thousand icons, so `Icons.kt` addresses them by codepoint and
  `scripts/subset_icon_font.sh` cuts the font to 33 KB by the same list.
* **The two rates are the UI's own arithmetic.** `SessionState` carries cumulative counters and
  nothing per-second; the design marks *speed down / up* `planned` for that reason, and `RateMeter`
  divides two samples by the time between them, against an injected `TimeSource`.
* **There is no paused torrent.** The engine has `Command.Stop` and no paused state, the design
  marks the row *planned*, and `PAUSED_IS_PLANNED` is asserted so that the day it changes somebody
  has to come back.
* **A window holds several torrents; a `Session` still holds one.** `TorrentSet` owns the listener,
  the DHT and the dispatcher, and there is a `BufferPool` per torrent because the cap is the
  back-pressure and back-pressure that is global lets a fast torrent starve a slow one (research
  §1.2c2).
* **What the engine says is sampled on a timer; what the person decides is read in composition.**
  `EngineSnapshot` holds one second's worth of session state and nothing else. The window state used
  to be rebuilt inside the sampling loop, which made every click up to a second late
  ([B-64](../backlog/B-64-a-click-waited-for-the-tick.md)).
* **The DHT is built the first time it is asked for.** Joining announces this machine's address to
  three public routers, so it happens when somebody asks for it rather than because a flag was true
  at start-up; a torrent already running keeps the `Dht` it was opened with, which may be none.
* **Every callback the window exposes is proved to arrive**, through `MainWindow` and not through
  the screen underneath it. A screen's own test cannot see a parameter that the window accepts and
  drops, which is what `onSetting` was ([B-62](../backlog/B-62-dead-controls-on-two-more-screens.md)).
* **Picking a directory is two implementations.** `apple.awt.fileDialogForDirectories` turns AWT's
  `FileDialog` into a folder chooser on macOS and does nothing anywhere else; `JFileChooser` in
  `DIRECTORIES_ONLY` is the one that exists on Windows and Linux. The wrong half does not throw —
  it opens a *file* chooser and returns nothing for a folder.
* **A control either has a command or a reason, never neither.** `ToolbarAction` refuses to be
  built without one of the two and the handler switches on an enum, because four buttons that
  looked available and fell into an `else ->` shipped once ([B-56](../backlog/B-56-dead-toolbar-controls.md)).
* **The title bar is drawn, not the operating system's**, because the design draws it in its own
  colours. `AppFrame` provides it and the controls stay the host's — traffic lights on macOS,
  minimise/maximise/close on Windows, the GTK layout on Linux.
* **The theme has to wrap `AppFrame`, not its content.** The bar is composed inside the window from
  `MaterialTheme.colorScheme.surfaceVariant`; with `KachokTheme` one level lower it drew from the
  default *light* scheme while everything under it was dark. The golden could not catch it — a
  golden cannot open a window, so it renders the same bar inside the theme and drew the right thing
  while the application drew the wrong one.
* **The title is `onSurfaceVariant` where the design has `#BEC9C6`.** `AppFrame` does not forward
  `color`/`contentColor` to the `TitleBar` it draws, though `TitleBar` itself takes both. One
  parameter; everything else about the bar — height, controls, their size, spacing and padding, the
  centred title, the background — matches the reference to the pixel.
* **`TextOverflow.StartEllipsis` type-checks and does nothing.** Compose Multiplatform 1.12
  truncates at the end whatever it says, with and without `softWrap = false` — checked twice
  against a golden. A path is elided by measuring it (`PathText`), which is also why the golden
  `details_long-path.png` exists: a real machine's directory does not fit and `~/Downloads/iso`
  does, so nothing before it showed the fault.
* **Selection is a torrent, not a row number.** Sorting reorders the list under it, and an index
  would leave the highlight on whatever moved into that position.
* **Sorting is on the values, not the cells.** Every column but the name is a number wearing a
  unit; `14.6 GiB` sorts before `3.70 GiB` as text.
* **A settings default is never typed twice.** `settingsOf` constructs `SessionConfig()` for its
  defaults, because a value written down a second time goes stale the first time a measurement
  moves it. `no limit` is the one place the engine's value (`0`) and the words a person needs are
  different things.
* **A magnet is a row before it is a torrent.** It is added as the design's *Metadata* state —
  the info hash where the name will be, an indeterminate bar, no size — and becomes a real one when
  `fetchMetainfo` returns. A fetch nobody can answer removes the row rather than marking a session
  broken; there is no session to mark.
* **Three of the four details tabs have nothing to show.** *Files*, *Peers* and *Trackers* need
  engine changes that do not exist — per-file progress, peer identities, a status per tracker — so
  each says which one it is waiting for rather than drawing an empty table.
* **`IntrinsicSize.Max` on a tab, or the first tab eats the row.** The 2 dp indicator under a
  selected tab is `fillMaxWidth`, which in a wrap-content column takes the whole remaining width
  unless the column is measured by its text.
