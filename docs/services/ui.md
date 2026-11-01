---
id: ui
title: ui (Compose Multiplatform desktop application)
type: service
module: ui
tech_stack: [Kotlin 2.4 JVM, JDK 25, Compose Multiplatform 1.12, Material 3, viddik 0.4]
owner: unassigned
depends_on:
  - engine
  - control
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

**Except that the window does answer something, and it is not on the network.** Since
[B-117](../backlog/B-117-one-client-for-the-window-and-the-agent.md) the single-instance socket this
process already binds carries a second kind of caller: `kachok mcp`, spawned by an agent runtime,
attaches to it and drives *this* window's engine rather than building one of its own. The window
registers the sessions once it has a `TorrentSet` and clears them before it closes it; everything
else about that surface — the tools, the frames, the refusals — is [control](control.md) §2 and
[cli](cli.md) §2. What it means here is that a row can appear in this window because an agent was
asked for it in a conversation, through the same `TorrentSet` a click goes through.

## 2a. Code anchors

| File | What is there |
|---|---|
| `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt` | `main`, the window, and the platform it reaches for: the file chooser, the clipboard, the drop target, the tray |
| `.../ui/session/ClientModel.kt` | what the window *is*, held where a window is not: its state, its channels, and the loop that samples every session and stops them cleanly |
| `.../ui/theme/` | the kit the screens draw through — `KachokColors` (the design's roles by what they are for), `KachokType`, `Text`, and the components a kit draws itself (`Toggle`, `DialogButton`, `ProgressBar`, the toolbar's `ToolButton`, `ToolMainButton` and `FilterField`, the banner's `BannerButton`, the details `Tabs`, and since B-178 the settings' `SettingField`, `FolderField` and `Segmented`, the empty state's `InvitationButton`, and the dialogs' `DialogFrame` and `SaveToField`, and since B-183 the dialogs' `Check` and `Choices`) — `InterfaceKit`, the choice the settings store, the three bundled families and the interface scale |
| `.../ui/theme/material/` | the Material kit: the Cyrillic runs of an Archivo text drawn in its companion (`CyrillicRuns.kt`, B-165), the eight roles read off the design, the `warning` M3 does not ship, the palette beside them, the 4 dp calibration, and its drawing of the three components (`MaterialComponents.kt`, the code the screens had, moved); since B-161 `MaterialKit.of(seed, style)`, the seeds and styles offered (`MaterialColours.kt`) and the derivation of every role from MaterialKolor's scheme (`SeededDesign.kt`) — the only place `make check` lets name `com.materialkolor` |
| `.../ui/theme/oldge/` | the oldge-ui kit (B-160): `OldgeKit.of(skin)`, one instance per `OldgeSkin` (B-162), every role from that skin's tokens, `OldgeSwitch`, `OldgeButton`, `OldgeTextField`, `OldgeSegmented` and `OldgeDialog` (B-178), the row's bar as `OldgeProgressWell` (B-182), the dialogs' checkboxes and radio buttons as `OldgeCheckbox` and `OldgeRadioGroup` with their description lines (B-183), `OldgeDensity.Pointer` and English `OldgeStrings` at its root (B-182), text through `OldgeText` in the design system's faces, and the Material scheme `AppFrame` reads; with `theme/material/`, the only place `make check` lets name `material3` |
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

**The screens do not name Material; they draw through a kit** (B-159). `KachokTheme.colors` holds
the design's roles by purpose — `accent` for fills and `accentText` for the accent written as
text, a glyph, a figure or a thin outline (B-164), three text levels (`text`, `textMuted`,
`textFaint`), grounds, lines, the row and card tints — and `KachokTheme.type` the three type roles a screen names.
`Text` and the hover hint's raised surface are the kit's to draw, and since B-160 so are the three
components a second kit draws differently — the settings switch, the dialog button and the row's
progress bar; the rest is drawn on `foundation` from those tokens, as it always was. The Material
kit draws exactly what the window drew before: the twelve goldens did not move, through B-159,
B-160, B-161, B-162, B-164 or B-165. `make check` fails on any file outside `ui/theme/material/` and `ui/theme/oldge/` that imports
`androidx.compose.material3`, and on any outside the first that reads its palette.

**There are two kits, and the settings choose** ([B-160](../backlog/B-160-oldge-ui-is-a-kit-the-window-can-wear.md)).
*Interface* on the settings screen picks Material or oldge-ui — the portfolio's mid-2000s design
system, in one of its skins. The choice is stored in `settings.properties` as `interface`, read
above `AppFrame` in `App.kt`, and applies without a restart because the kit is a composition local;
a name the build does not know is Material. The oldge kit takes `OldgeSwitch` and `OldgeButton`
from the library, at the pointer density and with English names for the library's own orbs
([B-182](../backlog/B-182-oldge-ui-answered-the-asks.md)), and draws the rest — the table, the header, the toolbar, the panel — from its
tokens, because the library is a mobile kit with none of them. Its text goes through `OldgeText` in
the design system's faces, and those decide who draws Cyrillic: Archivo, which has none, becomes
DejaVu Sans Condensed; Source Serif becomes Fira Sans; JetBrains Mono stays, because Share Tech
Mono has no Cyrillic either. `AppFrame` reads `MaterialTheme` itself, so the oldge kit's root
provides a Material scheme from its tokens and the title bar is the skin's bezel.

**Under the Material kit a Russian name is drawn in Archivo's bundled companion** ([B-165](../backlog/B-165-a-russian-name-is-drawn-in-the-rows-face.md)).
Archivo has no Cyrillic, so the kit's `Text` puts a span of `ArchivoCyrillic` — Onest 2.001, cut
to its Cyrillic block and combining marks and given Archivo's vertical metrics
(`scripts/subset_cyrillic_font.py`) — over each run of Cyrillic letters in a text whose face is
Archivo (`theme/material/CyrillicRuns.kt`). The spaces, digits and punctuation stay Archivo's, so
`Мастер и Маргарита (1994).mkv` has its year and extension in the same face as the Latin rows
around it. A text with no Cyrillic, or in JetBrains Mono or Source Serif (both have Cyrillic), is
drawn as it was; the oldge kit has its own faces for this.

**The Material kit's colours come from a seed** ([B-161](../backlog/B-161-the-material-kit-takes-its-colours-from-a-seed.md)).
*Colours*, under *Interface* and shown only with the Material kit, offers six seed swatches and
four scheme styles; stored as `colourSeed=#RRGGBB` and `colourStyle`, applied without a restart,
and a malformed seed or an unknown style reads as the design's. The design's teal under *Tonal* is
the hand-written scheme itself, so the default draws what it always drew; any other pair is
MaterialKolor's dark scheme (spec 2021) for the seed, and **every** role of the design is placed
in it relative to an anchor the generated scheme has at the same tone — the role's own tone, the
anchor's hue plus the role's offset, the role's chroma scaled by the anchor's — because the
hand-written scheme uses Material's names at tones Material does not (its `surfaceVariant` is
tone 10, not 30). `warning` is the design's amber harmonised towards the seed, so *checking* and
*stopping* stay amber. Three distances between families are kept by rule
([B-167](../backlog/B-167-a-seeded-scheme-keeps-its-states-apart.md),
[B-170](../backlog/B-170-a-seeded-accent-is-not-the-error-colour.md)): the error row's tint gains
chroma, in its own hue and tone, until it is as far from the surface in Oklab's a–b plane as the
design drew it (0.024); the accent family turns away from `error` to 32° of hue where the generated
accent is nearer, on the side it is already on; and the warning is turned back from that accent to
32°, within 15° of the design's amber, where harmonising brought it nearer — `SeededColorsTest`
holds every offered seed and style to 0.02 and 30°. Dark only, by the owner's decision.

**Under oldge-ui the same *Colours* row offers its skins** ([B-162](../backlog/B-162-the-oldge-ui-kit-offers-its-skins.md)).
One row whose contents are the kit's: the skins are read from `OldgeSkin.entries` — Toxic, Media
and Crystal in 0.1.1.37, so a skin a release adds appears with the version bump — each drawn as
its `ground` with its `accent` as a dot. Stored as `oldgeSkin=<the library's id>`, default `toxic`
([B-182](../backlog/B-182-oldge-ui-answered-the-asks.md)). The name a build before B-182 stored is
still read, and a value no skin has reads as the library's first skin. Each kit keeps its own choice, so
Material, oldge-ui, Material finds the seed where it was left. Every role comes from the chosen
skin's tokens by the mapping B-160 wrote for Toxic; since [B-168](../backlog/B-168-oldge-skins-own-their-footnote-and-dialog-border.md)
that includes the settings footnote's ground (`surface2`) and a dialog's border (`edge`), which
B-162 had still drawn from the Material design's greys. Crystal is a light skin, and its lime `accent` used as text — *Seeding*, the accent
figures — was 1.6:1 on its white surface; since [B-164](../backlog/B-164-accent-text-reads-on-a-light-skin.md)
every such use reads `accentText`, which the oldge kit fills with the skin's `accentInk` in every
skin (Crystal 6.05:1 on `surface`), and the Material kits with `accent` itself, so their goldens
did not move.

oldge-ui comes from reposilite's **snapshots**, pinned to one run; `settings.gradle.kts` names that
repository exclusively for `oldge-core` and narrows the releases one to AppFrame's two modules.

The colour rule lives outside the composables (`RowColors.kt`) so that "is this cell `#BEC9C6` or
`#DDE4E1`" is a question a test answers rather than a screenshot.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [engine](engine.md) | `TorrentRuntime`, `SessionState`, everything below the window |
| Module | [control](control.md) | the single-instance lock, and the MCP sessions this window answers on it |
| Module | `:swarm` (test only) | the tracker and seeding peer the end-to-end download runs against |
| Library | Compose Multiplatform 1.12 + Material 3 | the toolkit and the eight roles the design names |
| Library | viddik 0.4 | `viddikRecord` / `viddikVerify`, the goldens |
| Library | AppFrame 0.2.1.35 (reposilite releases) | the title bar the design draws, with the host's own window controls |
| Library | oldge-core 0.1.1.37 (reposilite snapshots) | the second kit: `OldgeTheme`, its tokens and faces, `OldgeSwitch`, `OldgeButton`, `OldgeText` |
| Library | MaterialKolor 5.0.1 (`com.materialkolor:material-kolor`, Maven Central) | the Material kit's scheme from a seed, `harmonize` for `warning`; upstream, not the portfolio's `linuxX64` fork |
| Fonts | Source Serif 4, Archivo, JetBrains Mono, Material Symbols Rounded | bundled, not asked of the machine |
| Fonts | Onest 2.001 (SIL OFL 1.1, `google/fonts` `ofl/onest` at `4c1db3a`), cut to `OnestCyrillic.ttf`, 42.7 KB | Archivo's Cyrillic under the Material kit (B-165); the copyright and the licence travel in its name table |
| Fonts | DejaVu Sans Condensed, Fira Sans Bold (inside oldge-core) | the oldge kit's faces; both cover Russian |

## 5. Infrastructure and deploy

* `./gradlew :ui:run` opens the window against the *full* JDK. Nothing a run like that does proves
  anything about the shipped application: the distribution carries a `jlink` runtime cut down to the
  modules `nativeDistributions` names, and a module missing from it is a `NoClassDefFoundError` on
  somebody else's machine ([B-78](../backlog/B-78-nothing-runs-the-packaged-application.md)).
* The JVM flags are the CLI's three, pinned in `ui/build.gradle.kts` for the same reason: a UI does
  not get to run a different VM from the one every measurement was taken on.

### Building an installer

`jpackage` cannot cross-compile, so each is produced on its own platform. One command each, and one
format each — the host picks from `targetFormats`. **What ships is the `Release` variant**, built
from the classes as compiled: ProGuard is off for it
([B-173](../backlog/B-173-the-window-ships-unshrunk-and-without-an-aot-cache.md)), after its
optimiser wrote bytecode the VM refused. The `Release` task names stay, so the image the checks run
is the one the installers carry:

| Platform | Command | Output | Prerequisite |
|---|---|---|---|
| macOS | `LOCAL=1 ./gradlew :ui:packageReleaseDistributionForCurrentOS` | `kachok-1.0.<n>.dmg`, in the build directory under compose/binaries/main-release/dmg | — |
| Linux | `~/.claude/bin/wsl-run './gradlew :ui:packageReleaseDeb'` | `kachok_0.1.<n>_amd64.deb`, next to it under deb | `fakeroot` |
| Windows | `gradlew.bat :ui:packageReleaseMsi` | `kachok-0.1.<n>.msi`, under msi | none — the Compose plugin downloads WiX 3.11.2 into `~/.gradle/compose-jb/` and passes it as `WIX_PATH`; `compose.desktop.application.downloadWix=false` turns that off |

Before any of them, `distributions.yml` runs two checks on the image that ships, on its own
platform: `:ui:checkDistributable`, the launcher's `--preflight` against its trimmed runtime, and
`:ui:checkDownload`, which starts the packaged window with a home of its own, waits for its engine
on the single-instance lock and has its MCP server download a torrent from a seed on loopback. The
second is the one that sees a window; it needs a display, so it is not on `check` (`xvfb-run` on
the Linux runner, WSLg with `DISPLAY=:0` on the build machine).

No installer carries an **AOT cache** any more, and none needs a display to be built
([B-173](../backlog/B-173-the-window-ships-unshrunk-and-without-an-aot-cache.md)): the `.deb`'s and
the `.dmg`'s (B-157, B-171) were trained on the shrunk classes, and the `.msi`'s could not map on a
machine outside the runner's time zone (B-172). The CLI's cache is a separate thing and stays.

The client can be asked to **start with the computer**, from a checkbox in Settings: a launch agent
on macOS, an `HKCU\…\Run` value on Windows, a freedesktop autostart entry on Linux — each written
by the client when the box is pressed, never by the installer, so the setting cannot disagree with
what is on the disk. The entry passes `--autostart` and the window comes up minimised. Only an
installed build can do it, because the path it points at is `jpackage.app-path`.

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
| the client's limits | `limits.properties` — download and upload bytes a second, downloads at once, connections and connection attempts at once; shared with the CLI's engine | on every change, from the settings screen or an agent ([B-151](../backlog/B-151-one-limit-for-the-whole-client.md), [B-152](../backlog/B-152-one-cap-on-connections-for-the-whole-client.md)) |
| the list of torrents | `torrents/<info hash>.torrent` — a **copy**, not a pointer | a torrent is added |
| each torrent's own choices | `torrents/<info hash>.properties` — name, directory, paused, unwanted files, sequential | added, paused, resumed |
| which pieces are verified | `resume/<info hash>.resume` — here, and not beside the data since [B-150](../backlog/B-150-resume-records-live-with-the-client.md) | the engine's own rule ([B-23](../backlog/B-23-atomic-resume-file.md)) |

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
* **The screens are common; the platform is not.** 32 files and 6 132 lines live in `commonMain` —
  every composable, every mapping function, the theme — and eleven in `desktopMain`. Two of them are
  `expect`/`actual` pairs and the rest are the desktop's own: a tray menu AWT draws itself, a launch
  agent and a `Run` key, the Windows command line, the single-instance lock. **The two seams were
  found by tooling and not by reading**: the compiler named the font loader, and this repository's
  lint named `java.awt.Cursor`, which compiled fine with one target and would have failed on the
  second ([B-80](../backlog/B-80-the-ui-moves-to-commonmain.md)). The holder stays on the desktop
  until the engine has another target: the UI cannot be more common than the engine it drives.
* **The window's state and its engine are not the window's.** Both live on a `ClientModel`, held one
  level above the window, and the composable reads them through `by model.selected` — the same text
  the `remember` was. Lifting the sampling loop's *body* out of the composable would have moved
  nothing: a `LaunchedEffect` is cancelled with its composition, so the holder owns the scope and
  the window only starts it. **Whoever builds the holder closes it** — a window that made its own
  takes it down, one handed a holder by the application leaves the engine running
  ([B-79](../backlog/B-79-the-windows-state-outlives-its-composition.md)).
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
* **A speed is a size per second, with its unit.** `Figures.speed` scales by the byte-size rule —
  `5.70 MiB/s`, `48.2 KiB/s`, `900 B/s` — in the list, the status bar, the tray and the details
  panel alike; the bare grouped KiB/s the mockup drew was unreadable in use
  ([B-145](../backlog/B-145-a-speed-says-its-unit.md)).
* **A control either has a command or a reason, never neither.** `ToolbarAction` refuses to be
  built without one of the two and the handler switches on an enum, because four buttons that
  looked available and fell into an `else ->` shipped once ([B-56](../backlog/B-56-dead-toolbar-controls.md)).
* **An icon button says what it does when the pointer rests on it.** `Hint` is an `expect`
  composable — `TooltipArea` on the desktop — showing `ToolbarAction.hint`: the label, and for a
  greyed control the reason it is greyed, which until then only the semantics tree carried.
  *Force re-announce* sends the details panel's `Announce` from the bar
  ([B-144](../backlog/B-144-the-toolbar-says-what-its-buttons-do.md)).
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
  does, so nothing before it showed the fault. oldge-ui cuts a read-only field's value the same way
  for the same reason, by its own measurement (youndie/oldge-ui#50), and the oldge kit's folder
  fields ask it to (B-183).
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
