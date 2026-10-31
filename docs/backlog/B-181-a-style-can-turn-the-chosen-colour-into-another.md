---
id: B-181
title: "A style can turn the chosen colour into another: the swatches show the seed, not the accent"
status: open
priority: P3
size: S
stage: phase-2-ui
---

# B-181 — A style turns the chosen colour into another

Seen in the live app on 2026-10-31. With *Violet* picked and *Expressive* chosen, the window's accent
is teal, close to the design's own default. With *Tonal* the same violet is the lilac
`main_window-violet.png` shows.

This is MaterialKolor working as specified, not a defect: *Expressive* moves the primary's hue away
from the seed's. But the person picked a violet square and got teal. Nothing on the screen says the
style would do that, because the swatch shows the seed and not the accent the window will draw.

- **The decision and its reason.** Each swatch shows the accent its seed produces *under the chosen
  style*. The swatches are the colours the window will be, and a style that rotates hues shows it the
  moment it is chosen. Rejected:
  - Dropping *Expressive*. Some of its schemes are the most distinct of the four, and B-170's rule
    already keeps them off the error's hue.
  - A note under the row. A note explains a surprise after the fact, where a true swatch prevents it.
- AC: under *Expressive* the *Violet* swatch is drawn in the generated `primary`, not `#A78BFA`; a
  test holds every swatch to its scheme's accent for every offered style.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/material/MaterialColours.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`.
