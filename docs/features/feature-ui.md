---
id: feature-ui
title: The desktop window — a client you can watch
type: feature
status: active
owner: unassigned
involved_services:
  - ui
  - engine
client_entries: []
api: []
tags: [phase-2, compose, design]
---

# The desktop window

> **Implemented on 2026-09-05** by [B-46](../backlog/B-46-ui-theme-and-calibration.md) through
> [B-54](../backlog/B-54-many-torrents.md); every scenario below names the test that covers it.
> The screens it is drawn from are `docs/design/`, and what was read out of them is
> `docs/design/design-tokens.md`.

## 1. Overview

The phase-2 surface: a window on the same engine the headless client runs. It samples every
session once a second and draws what the design specifies — a toolbar, a docked banner when a
session is degraded, a nine-column list, a details panel, a status bar.

**What makes this feature a feature rather than a rendering exercise** is the rule it is built on:
*the window may not say more than the engine knows*. Every number is derived from a `SessionState`
or from the metainfo beside it; the three things the design draws that the engine cannot produce
are drawn as what they are, with the design's own `planned` badge, and each names the change it is
waiting for.

## 2. Business rules

* **A row's colour is a function of (state, cell)** and not of the cell alone: the same figure is
  the headline of a downloading row and a frozen leftover on a stopping one. Twelve cells, seven
  states, and the hexes are the design's.
* **A degraded session outranks everything.** `sessionError` non-null is the *Error* row whatever
  else is true, and the banner that carries it verbatim is docked and does not time out.
* **A tracker's refusal is not a degraded session.** `trackerError` is set, `sessionError` is not,
  and no banner appears — the torrent is a download with nobody to talk to.
* **The two rates are the surface's own arithmetic.** `SessionState` counts up and has nothing
  per-second; `RateMeter` divides two samples by the time between them, against an injected
  `TimeSource`.
* **There is no paused torrent**, and the design says so on the row. Nothing the engine can report
  maps to *Paused*.
* **A magnet is shown as what it carries**: a hash, a display name if it has one, and a sentence
  saying the metainfo comes from the swarm first. Never blanks where a size would be. Once said yes
  to it is a *Metadata* row until `fetchMetainfo` returns, and a real torrent after.
* **Settings prints the measured default beside every field**, read out of `SessionConfig` rather
  than repeated. `no limit` is the words, not a zero.
* **A control either does something or says why it does not.** Nothing on the toolbar is drawn
  available and inert; the four that cannot work yet are greyed and each names the item that would
  enable it.
* **One window holds several torrents; one `Session` still holds one.** The listener, the DHT and
  the dispatcher are shared; the buffer pool is not, because the cap is the back-pressure.

## 3. Flow

```
TorrentSet ── one listener ─┬─ Session A ─┐
             one DHT        └─ Session B ─┤
                                          ├──▶ StateFlow<SessionState> ──▶ rowOf / detailsOf ──▶ MainWindow
                            RateMeter ────┘         (once a second)

toolbar ── Add torrent ──▶ file chooser ──▶ MetainfoParser ──▶ addFrom ──▶ dialog ──▶ Channel ──▶ TorrentSet.add
```

## 4. Code anchors

| Service | Code |
|---|---|
| ui | `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt` — the window, the sampling loop, the clean stop on close |
| ui | `.../ui/list/RowColors.kt` — which colour every cell of every state is, as a function of the scheme |
| ui | `.../ui/session/` — `SessionState` as rows, details, settings and an add dialog |
| ui | `.../ui/main/`, `.../ui/details/`, `.../ui/add/`, `.../ui/settings/` — the screens |
| engine | `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/runtime/TorrentSet.kt` — several torrents in one process |
| ui | `ui/src/desktopTest/snapshots/` — the goldens, verified by `make check` on the machine that recorded them |

## 5. Scenarios (BDD / test cases)

### Scenario: The list shows a real download progressing and then seeding
* **Given:** a local swarm — an HTTP tracker naming a peer that speaks BEP 3 over a socket.
* **When:** the window opens the torrent and samples it the way it samples any session.
* **Then:** a row is seen *Downloading* at a percentage between nothing and all before it is seen
  *Seeding* at 100 %, its size is the torrent's, its ETA is ∞, and the bytes came off the wire
  rather than off the disk.
* **Automated:** `ui AppDownloadTest#theListShowsARealDownloadProgressingAndThenSeeding`
  and `#theWindowItselfShowsTheDownload`, which drives the composable `main` builds rather than the
  mapping under it.

### Scenario: A tracker that refuses is not a degraded session
* **Given:** the same swarm with a tracker that answers `failure reason: forbidden`.
* **When:** the announce fails.
* **Then:** `trackerError` names the refusal, `sessionError` stays null, the window shows no
  banner, and the row is still *Downloading*.
* **Automated:** `ui AppDownloadTest#aTrackerThatRefusesIsNotADegradedSession`

### Scenario: The banner carries the exception verbatim and does not time out
* **Given:** a session whose `sessionError` is `writer loop failed: java.nio.channels.ClosedChannelException`.
* **When:** a minute of the composition's clock passes.
* **Then:** the banner is still on screen, still carrying the class name — which is the half a
  person searches for.
* **Automated:** `ui MainWindowTest#theBannerCarriesTheExceptionVerbatimAndDoesNotTimeOut`

### Scenario: Every cell of every state is the colour the design draws it
* **Given:** the design's seven rows, and its one selected row.
* **When:** each of the twelve cells is resolved through `rowColor`.
* **Then:** every one equals the hex read out of the design document — including the selected row,
  whose twelve come out of one mapping with nothing listed as a special case.
* **Automated:** `ui RowColorsTest#everyCellOfEveryStateIsTheColourTheDesignDrawsIt`
  and `#aSelectedRowIsTheSameRowDrawnOnTheContainer`. The golden
  `list_seven-states.png` is the other half: this says what the colours are, that says the row
  actually calls it.

### Scenario: Two torrents download at once and the status bar adds them up
* **Given:** two local swarms and one `TorrentSet`.
* **When:** both are added and started.
* **Then:** both finish, both came off the wire, the status bar reads `2 torrents, 2 seeding,
  0 paused`, both trackers were told the same port, and each torrent has its own buffer pool.
* **Automated:** `ui ManyTorrentsTest#twoTorrentsDownloadAtOnceAndTheStatusBarAddsThemUp`

### Scenario: A magnet says what it cannot say yet
* **Given:** a magnet link with a display name and no metainfo.
* **When:** it is pasted.
* **Then:** the dialog shows the hash and the display name, no file list, and a summary with no
  number in it at all; a magnet with no `dn` is named by its own hash.
* **Automated:** `ui AddFromTest#aMagnetSaysWhatItCannotSayYet` and
  `#aMagnetWithoutADisplayNameIsNamedByItsHash`

### Scenario: A magnet becomes a torrent and downloads it
* **Given:** a local swarm whose peer serves the `info` dictionary over BEP 9 on a socket, and a
  magnet naming its hash and its tracker.
* **When:** the magnet is added.
* **Then:** it is a *Metadata* row first; `fetchMetainfo` returns a metainfo that hashes to the hash
  the magnet named; the torrent downloads and the file on disk matches the swarm's content byte for
  byte.
* **Automated:** `ui MagnetTest#aMagnetBecomesATorrentAndDownloadsIt`,
  `#aMagnetIsARowBeforeItIsATorrent`, `#aMagnetWithNoNameShowsItsHashWhereTheNameGoes`

### Scenario: Every settings default is the engine's own
* **Given:** the settings screen with nothing changed.
* **When:** each field's `default` label is compared with `SessionConfig()`.
* **Then:** they are equal, `no limit` is the words rather than a zero, and typing a default back
  into a field is not counted as a change.
* **Automated:** `ui SettingsFromTest#everyDefaultComesOutOfTheEnginesOwnConfig`,
  `#aLimitOfNothingSaysTheWordsRatherThanZero`, `#typingTheDefaultBackIsNotAChange`

### Scenario: The column header sorts the list it heads
* **Given:** torrents whose sizes, percentages and ratios sort one way as text and another as
  numbers.
* **When:** each column head is used.
* **Then:** the order is the numeric one; an ETA of never is last whichever way the list is turned;
  clicking the sorted column reverses it and clicking another starts it ascending.
* **Automated:** `ui SortingTest#sizeIsSortedAsANumberAndNotAsItsCell`,
  `#anEtaOfNeverIsLastAscendingAndFirstDescendingButNeverInTheMiddle`, `#descendingIsAscendingBackwards`

### Scenario: Every control that can be pressed has somewhere for the press to go
* **Given:** the toolbar as the window builds it.
* **When:** every control on it is examined.
* **Then:** each one has either a command or a reason it is disabled, never neither and never both,
  and every reason names the backlog item that would enable it.
* **Automated:** `ui ToolbarStateTest#everyControlEitherDoesSomethingOrSaysWhyItDoesNot`,
  `#everyDisabledControlNamesTheItemThatWouldEnableIt`

### Scenario: A path too long for its cell keeps the end that identifies it
* **Given:** a save directory longer than the cell it is drawn in.
* **When:** the details panel draws it.
* **Then:** the front is replaced by an ellipsis and the last components are visible; a path that
  fits is untouched; a wider cell shows more of it.
* **Automated:** `ui PathTextTest#aPathThatDoesNotFitLosesItsFrontAndKeepsItsEnd`,
  `#aPathThatFitsIsLeftAlone`, `#aWiderCellShowsMoreOfTheSamePath`, and the golden
  `details_long-path.png`.

### Scenario: Every control that can be pressed is heard outside the window
* **Given:** the window with a list, a details panel, the settings screen and the add dialog.
* **When:** every column head, every details tab, every editable setting and the toolbar's live
  controls are pressed.
* **Then:** each one reports itself to the window's caller, and each reports *its own* identity —
  not a neighbour's.
* **Automated:** `ui WiringTest#everyColumnHeadLeavesTheWindow`, `#everyDetailsTabLeavesTheWindow`,
  `#everyEditableSettingLeavesTheWindow`, `#theSettingsScreensChangesLeaveTheWindow`,
  `#theAddDialogsBrowseLeavesTheWindow`

### Scenario: Nothing to show is a place to start
* **Given:** a window with no torrents.
* **When:** it is drawn.
* **Then:** the empty state offers the three ways in, the column header is absent, and the status
  bar still says the port is listening.
* **Automated:** `ui MainWindowTest#anEmptyWindowInvitesRatherThanShowingAnEmptyTable`

## 6. Out of scope

* The wasm build, which is [B-40](../backlog/B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md).
* Drag-and-drop and clipboard *events*: the overlay and the prompt are drawn from state and the
  window has no listener for either yet ([B-50](../backlog/B-50-add-torrent.md)).
* Applying a settings change to a running session, `planned` in the design.
* Per-file selection, sequential download, the peers list and the trackers list — four engine
  changes, each named by the screen that is waiting for it.

## 7. Quirks

* **A golden is a picture of one rasteriser's output**, so `viddikVerify` runs only on the machine
  that recorded it — and `make check` runs it there, so what is switched off is the duplicate
  rather than the gate.
* **A 12 px figure never reaches its own colour.** Antialiasing puts the brightest pixel in a small
  glyph 10–15 % short of the colour it is drawn in, which is why the per-cell colours are asserted
  through a function and not by counting pixels.
* **The design's own numbers do not always divide.** Its details panel writes a ratio of `0.11`
  where its own two figures give `0.14`; the panel divides rather than copies.
* **The window keeps its toolbar and status bar when the list is empty**, where the design drops
  most of both. They are the process's state rather than the list's.
* **Four toolbar buttons are greyed where the design draws three of them live.** The design shows
  the finished client; this shows what works. They did nothing at all for one release, which is the
  defect that made the rule.
