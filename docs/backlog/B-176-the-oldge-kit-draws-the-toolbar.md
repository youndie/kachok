---
id: B-176
title: "The oldge kit draws the toolbar with its own buttons, icons and search bar"
status: wip
priority: P2
size: S
stage: phase-2-ui
---

# B-176 — The oldge kit draws the toolbar

Under oldge-ui the toolbar barely changes: *Add torrent*, the eight icon buttons and the filter box
are drawn in `main/Toolbar.kt` in the Material design's shapes — a flat 28 dp square, a tonal
rectangle, an outlined box — and in Material Symbols glyphs, so only their colours follow the kit.
It is the first thing in the window and the one place where the window still reads as Material
after the kit is switched (the owner, 2026-10-31).

- **The decision and its reason.** The three controls become kit members, the way the dialog
  button and the switch did in B-160: `ToolButton`, `ToolMainButton` and `FilterField`. The Material
  kit draws what `Toolbar.kt` draws now, moved and not rewritten, so its goldens do not move. The
  oldge kit uses the library's own components — `OldgeIconButton`, `OldgeButton` and
  `OldgeSearchBar` — and the library's icon set, mapped from the toolbar's glyphs. `Toolbar.kt`
  keeps the layout, the hints and the commands.
- **Sizes.** oldge-ui's dense button is 36 dp, against the design's 28, and its hit rule (44 dp
  wide for an icon-only button) is a touch rule; the toolbar sets 36 × 36. At the narrow window's
  600 dp the bar no longer fits with *Add torrent*'s label, so a narrow bar asks the kit for the
  compact button, and the oldge kit drops the label there. The Material button fits and keeps it.
  The filter's width moves into the kit with it: the search bar's 16 sp text, magnifier and clear
  orb leave one letter visible at the Material field's narrow 110 dp, so the oldge field is 150.
- **A toggle that is on** (*Details panel*, *Settings*) is the library's *Primary*: the design
  system fills a selected filter chip with its accent, and it has no icon-only toggle.

- AC: under the oldge kit every toolbar control is an oldge-ui component in oldge-ui's icon; the
  Material goldens are byte-identical; a golden of the narrow window under oldge shows the whole
  bar inside it.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/main/Toolbar.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/oldge/OldgeKit.kt`.
