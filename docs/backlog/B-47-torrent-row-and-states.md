---
id: B-47
title: "The torrent row and its seven states"
status: done
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-46]
---

# B-47 — The torrent row and its seven states

The densest thing in the product and the one the design spends the most words on: nine columns at
28 dp, and seven states each of which is allowed to say a different amount
(`docs/design/design-tokens.md` §5).

- **The decision and its reason.** A `Row` of fixed-width cells, not `ListItem` — the inventory says
  `ListItem` cannot do nine columns. The progress cell is custom because the design says so: a 4 dp
  track with no wave, because it redraws at 1 Hz.
- **The colour rule is a function, not a composable.** `RowColors.kt` maps (state, cell) to a
  colour and takes the scheme as an argument, so all twelve cells of all seven states are checked
  against the design's own hexes by an ordinary test. Inside `@Composable` code the only available
  check would have been the golden, and a golden cannot answer "is this figure `#BEC9C6` or
  `#DDE4E1`": a 12 px glyph never reaches its full colour anywhere in its antialiasing, and several
  cells in the recorded picture peak 10–15 % short of the colour they are drawn in.
- Rejected: colour alone for the state. Glyph *and* colour, so the column survives a monochrome
  screenshot and a colour-blind reader — the design says this outright.
- Not covered: sorting and selection, which belong to the shell ([B-48](B-48-main-window-shell.md)).

## Deviations, and why

- **The peers cell is one colour, not three.** This item and the design's own component inventory
  both said "one `AnnotatedString`, three spans, colour carries the state", and the first
  implementation dimmed a zero unchoked and a zero in flight separately. The mockup does not: all
  28 peers cells in `kachok Phase 2 Desktop.dc.html` are a single span in a single colour, and a
  metadata row's `6/0 · 0` is `onSurface` *including* both zeros. Pixels beat prose here — "colour
  carries the state" describes the cell taking the row's colour, which is what is now built. Three
  spans would have been an invention, and it read as "six peers, and something is wrong with them".
- **Selection is not drawn.** The design's main window has its first row selected, so that one row
  of `docs/design/screens/main-window.png` is a different palette from the golden. Deliberate: this
  item declares selection out of scope, and a half-drawn selection (a background tint without the
  six recoloured cells that go with it) would be worse than none.
- **Text antialiasing differs from the reference.** Skia's rasteriser is not Blink's, so the
  golden's glyph edges are not the reference's even where the colour, the size and the position all
  match. Compared by ink extent instead: every column's ink starts and ends within a pixel of the
  reference's on the same row.

- AC: a viddik golden per state, compared against `docs/design/screens/row-states.png`; a
  *metadata* row shows no size, no ratio and no ETA, and a *seeding* row's zero outstanding is not
  drawn as a stall.
  **Automated:** `ui/src/desktopTest/.../list/RowColorsTest.kt`, and the golden
  `ui/src/desktopTest/snapshots/list_seven-states.png` verified by `:ui:viddikVerify` in
  `make check`.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/list/`.
