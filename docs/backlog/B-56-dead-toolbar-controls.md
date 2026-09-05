---
id: B-56
title: "Controls that reported themselves and nobody listened"
status: done
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-51]
---

# B-56 — Controls that reported themselves and nobody listened

Found by reading `App.kt` after the stage closed, not by any test: **five controls in the shipped
window did nothing.** The column header drew itself sortable — the sorted column in `onSurface`
with a `primary` arrow — and `onSort` was never handled. Four toolbar buttons looked available and
fell into an `else -> Unit`.

Nothing caught it. The goldens drew all five correctly, because drawing them correctly is what was
wrong. `MainWindowTest` clicked the two that worked. The handler was a `when` on **labels** with an
`else` at the bottom, which is a `when` that cannot be exhaustive and therefore cannot complain.

## What was done

- **Sorting works**, and sorts on the values rather than on the cells. Every column here except the
  name is a number wearing a unit: `14.6 GiB` sorts before `3.70 GiB` as text and after it as a
  size, `100%` before `52%`, `11.4` before `2.07`. An ETA of never goes last whichever way the list
  is turned, because the question the column answers is *what finishes next*. Descending is the
  ascending order reversed rather than a second comparator, so the two cannot disagree about ties.
- **The four toolbar buttons are greyed, and each names the item that would enable it.** Greyed
  rather than hidden, which is what the design does with *Resume*; greyed rather than live and
  inert, which is what they were.
- **`ToolbarAction` cannot exist with neither a command nor a reason** — one of the two, checked in
  its `init`. The handler switches on a `ToolbarCommand` enum, so adding a control without handling
  it does not compile, and `ToolbarStateTest` asserts the invariant over every control on the bar.

## What driving the window found next

The five above were found by reading the code. Running it found four more, none of which any test
or golden could have shown, because all four need a real machine's data:

- **`Save to` ran into its own label and was cut at the wrong end** — `Save to/private/tmp/claude-501/-Users-youndi…`,
  in all three places a path is drawn. Every visible character was the same for every torrent on the
  disk. `TextOverflow.StartEllipsis` compiles against Compose Multiplatform 1.12 and truncates at
  the end anyway, with and without `softWrap = false`; `PathText` measures instead.
- **Selection was a row number.** Sorting reordered the list under it and left the highlight, and
  the details panel, on whatever had moved into that position. It is an info hash now.
- **A directory that is not the default was not marked changed** in settings, where a changed
  number is — the folder control took a different path through the row and nobody passed it down.
- **The settings screen showed the port somebody wished for, not the one the listener bound.** The
  status bar shows the second from the same process; the first time 6881 is busy they disagree.

And one that is not a UI bug and is worse: two different torrents saving to the same file, with
nothing that notices — [B-60](B-60-two-torrents-one-path.md).

## Deviation, and why

- **Four toolbar buttons are greyed where the design draws three of them live.** The design shows
  the finished client; this shows what works. A button that looks available and does nothing is
  worse than one that admits it, and the design itself greys *Resume* for the same reason.

- AC: every enabled control has somewhere for its press to go; every disabled one says which item
  would enable it; the column header sorts the list it heads.
  **Automated:** `ui/src/desktopTest/.../main/ToolbarStateTest.kt` — the invariant over every
  control — `ui/src/desktopTest/.../session/SortingTest.kt`, whose every case would pass on strings
  for one input and fail for another, `.../theme/PathTextTest.kt`, and the golden
  `details_long-path.png`, which exists because `~/Downloads/iso` fits and a real directory does
  not.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/Sorting.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/Toolbar.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`.
