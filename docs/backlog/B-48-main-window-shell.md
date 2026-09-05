---
id: B-48
title: "The main window: toolbar, column header, status bar, degraded banner"
status: done
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-47]
---

# B-48 — The main window: toolbar, column header, status bar, degraded banner

- **The decision and its reason.** A 48 dp toolbar with no title, a sortable column header, a 24 dp
  status bar carrying the session totals, and the degraded banner — docked and permanent, because a
  `Snackbar` that disappears on a timer is the wrong shape for "a session is degraded and needs a
  person".
- **Every bar is the same shape.** `Chrome.Bar` is a height, a background and one hairline on the
  side facing the content, because that is all the design's chrome is — there is no elevation
  anywhere in it. Four bars built four times drift; one built once cannot.
- **The header reads its widths from `TorrentColumns`.** A header with its own copy of the row's
  nine numbers is a header that goes wrong by one and puts every heading half a cell from what it
  names.
- **Three toolbar controls are drawn rather than configured.** `FilledTonalButton` is 40 dp high
  with a full-height radius and the design asks for 28 and 4; `OutlinedTextField` reserves room for
  a floating label and a supporting line and cannot be 28 dp with either. What is left of either
  component after overriding all of that is nothing.
- Rejected: hiding the diagnosis behind a hover. The engine's rule is that "no peers, no reason" is
  a state nobody can act on; the UI inherits it, so the exception is on the banner, in mono,
  verbatim.
- Not covered: the keyboard map, and the reflow below 800 dp.

## What this found, and the deviations

- **`#151C1A` is a border, never a background.** The theme built in
  [B-46](B-46-ui-theme-and-calibration.md) read it as the raised surface and gave `surfaceVariant`
  and `surfaceContainer` that value; the design uses `#161D1B` for every one of its eleven raised
  backgrounds and `#151C1A` for all seventy-two of its borders. The column header and the status
  bar were a shade too dark, which is invisible until two surfaces meet in one picture — they do
  here. Corrected in `theme/Colors.kt`.
- **The details panel is closed in the golden.** The reference draws it open and drawing it is
  [B-49](B-49-details-panel.md); a placeholder would put something in a golden that the product
  does not have. Everything full width is directly comparable and matches to the pixel; the list is
  1200 px wide rather than 859, which is what `minmax(0, 1fr)` does to the name column when the
  panel is not there. The toolbar's panel toggle is therefore drawn *off*, where the reference has
  it on — the toggle agreeing with the panel is the point of it.
- **The filter field is 220 dp, not the design's `width: 200px`.** CSS measures that inside the
  border and the padding. Nothing else in the design has padding or a border to add, so this is the
  only place the two systems disagree about what a number means.
- **The status bar's `·` is given a width.** It is the one span in the whole design document with
  no face on it: it inherits the browser's default, which is not one of the three families this
  ships, and an unconstrained mono dot is four pixels wider — enough to push everything after it
  out of line by the third separator.

- AC: a viddik golden of the whole window against `docs/design/screens/main-window.png`; the
  status bar shows down/up, torrent counts, DHT nodes, port and heap; the banner carries
  `sessionError` verbatim and does not time out.
  **Automated:** `ui/src/desktopTest/.../main/MainWindowTest.kt` — the banner is still on screen a
  minute later, and the exception it carries still contains the class name — and the golden
  `ui/src/desktopTest/snapshots/main_shell.png` verified by `:ui:viddikVerify` in `make check`.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/`.
