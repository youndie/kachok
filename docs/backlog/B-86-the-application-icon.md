---
id: B-86
title: "The application icon, drawn from its own geometry"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-86 — The application icon, drawn from its own geometry

The build ships no icon. `nativeDistributions` names none, there is no `.icns`, `.ico` or `.png`
anywhere in the tree, and what a person sees in a dock or a task list is whatever `jpackage`
generates by default.

The design has one — *App icon: pump and powerlifter in one mark*, variant **2f, "Round chamber"** —
and it is specified as geometry rather than drawn: a barbell over a ring, rounded rectangles and one
stroked circle, nothing else.

## The mark, in units

**The grid is 1024.** The design lists the shapes as `ring ø 358, stroke 79 · rod 69×118, overlap 13
· bar 322×69 · plates 84×179`, and the canvas draws them as `em` fractions of the tile — 0.35, 0.0775,
0.315, 0.0825. Every one of those times 1024 is the listed number to within half a unit (0.315 ×
1024 = 322.6), and times 1000 it is not. That matters: on a 1024 grid one unit is 1/64 of a pixel at
16 px, which is what "redraws exactly at 16 px" means.

| Shape | Size | Radius | Note |
|---|---|---|---|
| plate (×2) | 84 × 179 | 29 | one either side of the bar |
| bar | 322 × 69 | 35 | 29 of clear space between it and each plate |
| rod | 69 × 118 | — | square ends; hangs from the bar's centre |
| ring | ø 358 | stroke 79 | **overlaps the rod by 13**, so the mark is one object |
| tile | 1024 | 180 | ground `#0B100F` |

The mark is `#4FD9C2` — the same value as `KachokDarkColors.primary`, verified, not eyeballed. The
whole group sits **20 units below centre**: the design says optical rather than arithmetic, and
centring it arithmetically is the version that looks wrong.

The design's own note on why the ring is what it is: it grew to 358 so it outweighs the plates
instead of hanging under them; the stroke went to 79 because a curve reads thinner than a straight
edge of the same width; the rod tucks under it by one stroke so the mark is one connected object
rather than three stacked ones.

- **The decision this needs.** How the raster is produced. The shapes are five rounded rectangles
  and an annulus, so a script that renders them from the table above is exact at every size and
  re-runnable; the alternative is a hand-drawn SVG that somebody has to keep in step with the
  numbers. Given the design's own framing — *this is geometry, not rendered art* — the script is the
  form that keeps the claim true.
- Rejected in advance: exporting a PNG from the canvas. It would be a picture of the geometry at one
  size, and the whole point of the specification is that 16 px is not a shrunk 1024.
- Not covered: a monochrome or template variant for a macOS menu-bar item, which only matters once
  there is one ([B-83](B-83-autostart-and-its-setting.md) mentions the tray).

## What it has to feed

`jpackage` wants a different format per platform and will not convert between them: `.icns` on
macOS, `.ico` on Windows, `.png` on Linux, named in `nativeDistributions { macOS { iconFile }, … }`.
The `.ico` and `.icns` both carry several sizes, and 16 is the one the geometry was designed for.

- AC: the icon is generated from the numbers above by something re-runnable; the distribution on
  each platform carries it; the 16 px raster has no half-pixel edges; the mark's colour is
  `KachokDarkColors.primary` and not a copy of it that can drift.
- Anchors: `ui/build.gradle.kts`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/theme/Colors.kt`, `scripts/`.
