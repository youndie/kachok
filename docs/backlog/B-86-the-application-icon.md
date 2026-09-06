---
id: B-86
title: "The application icon, drawn from its own geometry"
status: done
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
1024 = 322.6), and times 1000 it is not.

That is all the grid settles, and it is worth saying what it does *not*. *"Redraws exactly at 16 px"*
is a separate claim, about the mark being rectangles rather than about the grid: nothing in the
geometry lands on a pixel boundary there — the bar is 1.08 px tall and the ring's stroke 1.23. Drawn
straight, the 16 px raster is a grey smear where the bar should be, which is the size Windows uses
in a file list.

So sizes up to 48 are **hinted**: every edge is snapped to a whole output pixel before it is filled,
each shape keeping at least one pixel and the ring's hole two, and the ring's centre landing on a
boundary so both its edges do. Above 48 the shapes are wide enough that snapping would move them
visibly instead of sharpening them, and nothing is snapped. Compared side by side at 48 and 64 the
mark does not change proportion across that line.

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

**A deliberate deviation, because the design says 20 in two places and means two numbers.** The
caption says twenty units below centre; the canvas achieves ten. Its `padding-top: 0.02em` is 20.5
units, and under `justify-content: center` a top padding moves the centre by *half* of itself — so
the drawing anyone looked at is offset by 10 and the sentence beside it says 20. Rendered side by
side at 152 px the difference is a pixel and a half and both read. `BELOW_CENTRE = 20` here: the
caption is the number somebody wrote down on purpose, and a padding that lands on half its own
value is the shape of a slip rather than of an intent. One line in `scripts/make_icon.py` if the
owner reads it the other way.

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
- Anchors: [`scripts/make_icon.py`](../../scripts/make_icon.py),
  [`ui/build.gradle.kts`](../../ui/build.gradle.kts),
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/icons/AppIcon.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/icons/AppIcon.kt),
  [`ui/src/desktopMain/resources/icon/`](../../ui/src/desktopMain/resources/icon).

## Done

`scripts/make_icon.py` draws the table above with a pure-standard-library rasteriser — a coverage
mask supersampled 4× and boxed down, PNG and ICO written by hand, `iconutil` for the ICNS container
— and writes `icon.png` (512), `icon.ico` (16…256) and `icon.icns` (16…1024, with `@2x`) into
`ui/src/desktopMain/resources/icon/`. `nativeDistributions` names one per platform, and the running
window takes the PNG through `appIcon`, because `jpackage` only dresses an *installed* build and a
jar run from Gradle is the build everyone here develops against.

The colour is read out of `Colors.kt` by regex rather than written down a second time, so a palette
change that misses the icon is a build failure and not a mark in last season's green.

`make check` runs `make_icon.py --check`, which regenerates into a temporary directory and diffs.
The ICNS is the one file that cannot be rebuilt on the Linux runner — `iconutil` is macOS only — so
there it compares the other two and the container is checked on the mac. Both machines run the same
command; the gap is which files exist to compare, not which rules apply.

**Automated:** `python3 scripts/make_icon.py --check` (in `make check`) ·
`ui/src/desktopTest/.../icons/AppIconTest.kt` — that one is the other half: the script proves the
committed files match the geometry, and the test proves one of them is on the classpath under the
name the window asks for. Verified by moving the resources directory aside, at which point the test
fails and nothing else does.
