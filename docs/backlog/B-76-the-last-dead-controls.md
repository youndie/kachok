---
id: B-76
title: "The copy button, Show it, and the add dialog's ticks"
status: done
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

## Deviations, and why

- **The copy button acknowledges in colour, not with a tick.** The glyph lights up in the accent for
  a second and a half. A tick reads better and needs a twenty-eighth glyph; the icon font is subset
  by codepoint from a 15 MB source that is deliberately not in the repository, so adding one means
  fetching that font and re-running `scripts/subset_icon_font.sh` — for a signal the existing glyph
  already carries.
- **The two radios stay as they are.** *Add paused* wears the badge; *Start immediately* does not,
  because it is the option already chosen and pressing a selected radio is a no-op in any dialog.
  Wiring it would move a dot to *Add paused* and start the torrent anyway, which is worse than not
  responding.
- **What the button copies is not what the field shows.** The panel has room for ten of the forty
  hex characters, so `DetailsField` gained `copyText`. The test asserts the length, because copying
  the shortened one is the easy mistake and looks right on screen.

- AC: the copy glyph puts the info hash on the clipboard and says so; *Show it* opens the details
  panel on the degraded torrent; every non-acting control in the add dialog carries the badge, and
  `WiringTest` enumerates the dialog's controls the way it enumerates the settings'.
  **Automated:** `ui/src/desktopTest/.../main/WiringTest.kt` —
  `theCopyButtonLeavesTheWindowWithTheWholeHash`, `showItLeavesTheWindow`; and
  `ui/src/desktopTest/.../add/AddTorrentTest.kt` — `everyControlWaitingOnTheEngineWearsTheBadge`.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/details/DetailsPanel.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/add/AddTorrent.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/main/MainWindow.kt`.
