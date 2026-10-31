---
id: B-170
title: "No offered seed and style draws the accent in the error's colour"
status: wip
priority: P3
size: S
stage: phase-2-ui
---

# B-170 — A seeded accent is not the error's colour

Found while building B-167, in its measured table: under the green swatch with the Expressive style
MaterialKolor rotates the accent to `#FDB1AA`, 1.7° of hue from the error's own `#FFB4AB`. Every
seeding row's glyph, bar and *Seeding* label is then drawn in the error colour, and a seeding row
reads as a failed one. The orange accents sit 26–28° from the error, which B-167 left as they are:
turning orange's accent away from the error turns it into the warning amber, which is why B-167
moved the warning instead.

- **The decision and its reason.** Assert the third distinction the design relies on — accent and
  `accentText` against `error` — over every offered swatch × style, with a threshold stated and
  measured, and resolve the pairs that fail. Whether that is a rule in `SeededDesign.kt` or taking
  the pair off the screen (green × Expressive is one combination of twenty-four) is decided by what
  the table shows; an accent that has nowhere to go between error and warning is a combination not
  worth offering.
- Not covered: seeds typed into the settings file by hand.

- AC: a test over all swatches × styles asserts the accent–error distance and passes; no offered
  combination draws a seeding row in the error's hue.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/material/SeededDesign.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/material/MaterialColours.kt`.
