---
id: B-47
title: "The torrent row and its seven states"
status: open
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

- **The decision and its reason.** A `Row` of fixed-width `Box` cells, not `ListItem` — the
  inventory says `ListItem` cannot do nine columns. The progress cell and the peers cell are custom
  for reasons the design writes down: a 4 dp track with no wave because it redraws at 1 Hz, and one
  `AnnotatedString` of three spans whose colour carries the state.
- Rejected: colour alone for the state. Glyph *and* colour, so the column survives a monochrome
  screenshot and a colour-blind reader — the design says this outright.
- Not covered: sorting and selection, which belong to the shell ([B-48](B-48-main-window-shell.md)).

- AC: a viddik golden per state, compared against `docs/design/screens/row-states.png`; a
  *metadata* row shows no size, no ratio and no ETA, and a *seeding* row's zero outstanding is not
  drawn as a stall.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/list/`.
