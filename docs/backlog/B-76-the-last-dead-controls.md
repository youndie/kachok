---
id: B-76
title: "The copy button, Show it, and the add dialog's ticks"
status: open
priority: P1
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-62]
---

# B-76 — The copy button, *Show it*, and the add dialog's ticks

What is left after [B-62](B-62-dead-controls-on-two-more-screens.md), found by reading every
`Composable` that draws a control rather than by clicking:

| Drawn | Where | What happens |
|---|---|---|
| The copy glyph beside the info hash | `DetailsPanel.kt` | nothing — a `Glyph`, no `clickable` |
| *Show it* on the degraded banner | `MainWindow.kt` | nothing — `DegradedBanner` takes `onShow` and is called without one |
| A checkbox per file in the add dialog | `AddTorrent.kt` | nothing; it carries the design's `planned` badge |
| *Sequential download* in the add dialog | `AddTorrent.kt` | nothing; badge as above |
| The two radios in the add dialog | `AddTorrent.kt` | nothing; no badge, and no `onClick` either |

The first two are wiring. The last three are [B-67](B-67-per-file-selection.md) and
[B-65](B-65-sequential-download.md) reaching the surface, and the radios are the one case with
neither a badge nor a behaviour — the worse of the two failures, because a person cannot tell.

- **The decision this needs.** None for the first two. For the rest: a control waiting on an engine
  change wears the badge, and one that will never do anything is not drawn.
- Rejected in advance: hiding the copy button until the engine can produce the hash — it can.

- AC: the copy glyph puts the info hash on the clipboard and says so; *Show it* opens the details
  panel on the degraded torrent; every non-acting control in the add dialog carries the badge, and
  `WiringTest` enumerates the dialog's controls the way it enumerates the settings'.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/add/AddTorrent.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/MainWindow.kt`.
