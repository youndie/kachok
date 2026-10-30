---
id: B-167
title: "Every seeded scheme keeps the error row's tint and the accent apart from the surface and the warning"
status: wip
priority: P3
size: S
stage: phase-2-ui
---

# B-167 — A seeded scheme keeps its states apart

Seen in B-161's goldens. Under the orange seed with the Vibrant style the error row's tint nearly
disappears into the warm surface — the error still reads from its red name, figures and bar — and
*Seeding*, drawn in the accent, sits close to *Checking* and *Stopping* in the warning amber
(`ui/src/desktopTest/snapshots/main_window-orange.png`). Both follow from deriving roles by tone
from the generated scheme: a warm seed brings the surface's hue towards red and the accent's
towards amber.

- **The decision and its reason.** Test the distinctions the design relies on rather than eyeball
  them per seed: over every offered swatch and style, the error row tint differs from the surface
  by a stated minimum, and the accent's hue is at least a stated distance from the warning's. Where
  a combination fails, the derivation gets a floor (a chroma floor for the tint, a hue nudge for the
  accent) — or the combination is not offered.
- Not covered: seeds typed into the settings file by hand, which are the person's own choice.

- AC: a test over all swatches × styles asserts both distances and passes; the orange golden shows
  a visible error row tint.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/material/SeededDesign.kt`.
