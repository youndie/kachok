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
| `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt` | `main`, the window, and the loop that samples the session and stops it cleanly on close |
| `.../ui/theme/` | the eight roles, the `warning` M3 does not ship, the three bundled families, the 4 dp calibration |
| `.../ui/icons/Icons.kt` | the twenty-one Material Symbols codepoints and the subset font they index into |
| `.../ui/list/TorrentRow.kt` | the nine columns at the design's widths, and the row's own hairline |
| `.../ui/list/RowColors.kt` | which colour every cell of every state is, as a function of the scheme rather than of a composition |
| `.../ui/main/` | the toolbar, the banner, the column header, the status bar, and the window that stacks them |
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
| Fonts | Source Serif 4, Archivo, JetBrains Mono, Material Symbols Rounded | bundled, not asked of the machine |

## 5. Infrastructure and deploy

* `./gradlew :ui:run` opens the window. No installer in phase 2 yet; `jpackage` through
  `compose.desktop` is what [B-39](../backlog/B-39-compose-ui-desktop.md)'s successors will use.
* The JVM flags are the CLI's three, pinned in `ui/build.gradle.kts` for the same reason: a UI does
  not get to run a different VM from the one every measurement was taken on.

## 6. Local setup

```bash
./gradlew :ui:run --args="example.torrent ~/Downloads"
```

Recording and checking the goldens, which must happen on the machine that has the rasteriser they
were recorded with:

```bash
LOCAL=1 ./gradlew :ui:viddikRecord
```

## 7. Configuration

None. Two command-line arguments and nothing read from the environment; the settings screen is
[B-51](../backlog/B-51-empty-and-settings.md).

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
* **One torrent per window.** The engine is one `Session` per torrent and nothing above it holds
  several; the list, the status bar and the counts are all built for many and are given one.
