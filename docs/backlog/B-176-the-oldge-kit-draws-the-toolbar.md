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

## Done — 2026-10-31

**What exists.** `KachokKit` has three more members: `ToolButton`, `ToolMainButton` and
`FilterField`. `Toolbar.kt` keeps the layout, the hints and the commands. The Material kit's three
are the toolbar's old drawing, moved into `theme/material/MaterialComponents.kt` together with the
field's two widths. The oldge kit draws them with the library's own components:

- *Add torrent* is `OldgeButton`, *Primary*, small. In a narrow bar it is `OldgeIconButton`, the
  glyph alone, still named *Add torrent*.
- The eight icon controls are `OldgeIconButton`, 36 × 36, *Secondary*. A toggle that is on is
  *Primary*.
- The filter is `OldgeSearchBar` at 36 dp: 220 dp wide, 150 narrow.

The glyphs map to oldge-ui's icons in `TOOL_ICONS`: *Plus*, *Attach* (paste magnet), *Pause*,
*Play*, *Trash*, *Refresh* (re-check), *Share* (re-announce), *Info* (details panel) and *Levels*
(settings). A glyph with no entry would draw *More*.

**Goldens.** The Material goldens are byte-identical after `viddikRecord` on the mac: only the
three oldge windows changed, and one is new.

- **Toxic, Media, Crystal:** glossy chrome buttons, a lime *Add torrent*, the details toggle lit,
  and the search capsule in its chrome ring. It reads in all three skins, Crystal's light ground
  included.
- **`main_narrow-oldge.png` (new):** the 600 dp window with a filter typed. The whole bar fits:
  *Add torrent* as a lime square, «deb» readable in the field beside its clear orb, and the
  settings button at the right edge.

**What is still Material in the oldge window.** The degraded banner's *Show it* button and the
details panel's tabs are drawn by the screens, not by the kit — they were not part of the top bar.

**A gap in the library.** `OldgeSearchBar` names its clear button «Очистить» and takes no label for
it, so under this kit the English window has one Russian accessible name. The Material field's
clear button is *Clear the filter*.
