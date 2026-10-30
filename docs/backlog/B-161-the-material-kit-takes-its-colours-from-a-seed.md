---
id: B-161
title: "The Material kit takes its colours from a seed the person picks, through MaterialKolor"
status: open
priority: P3
size: M
stage: phase-2-ui
blocked_by: [B-159]
---

# B-161 — The Material kit's colours come from a seed

The current colours are one hand-written `darkColorScheme` (`ui/theme/material/Colors.kt`), read off the
design's swatch row: teal accent, dark only. The person is to be able to pick the colour instead.
[MaterialKolor](https://github.com/jordond/materialkolor) is the Compose port of Google's
material-color-utilities: a `ColorScheme` from one seed colour, with a choice of scheme style and
contrast level.

- **The decision and its reason.** A seed and a style in the settings, the scheme generated from
  them at the root of the Material kit. **Generated roles are not all of this design's roles**:
  `warning` and the middle text level (`KachokPalette`) have no place in a Material `ColorScheme`,
  which is why they were written by hand. They are derived from the generated scheme in the kit —
  `warning` from a fixed hue harmonised towards the seed, so *checking* and *stopping* stay amber
  whatever the person picks — and B-159's token layer is where that derivation lives.
- The design's own teal stays the default and is a seed like any other; the first frame after this
  item must look as it does now, and a golden says so.
- **Dark only, unless decided otherwise here.** MaterialKolor produces a light scheme just as
  readily, and `Colors.kt` explains why there is none: the design is dark-first, and a light
  scheme generated here would be a second design nobody drew. Offering one is a choice for the
  owner, recorded in this item, not a side effect of the library.
- Upstream MaterialKolor from Maven Central, not the portfolio's fork: the fork
  (`io.github.youndie.materialkolor`) exists for a `linuxX64` target and carries only
  `material-color-utilities`, and this is a JVM window. The fork's conformance work found two
  defects of the port on the JVM — contrast −1.0 gives a white dark scheme, and the `VIBRANT`
  2025 spec differs in `outlineVariant` — so contrast −1.0 is not offered.
- Not covered: oldge-ui's colours, which are its skins (B-162).

- AC: the person picks a colour and a style in the settings and the window redraws in a generated
  scheme without a restart, warning states still amber; the default seed renders the current
  goldens unchanged.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/material/Colors.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/Theme.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/StoredPreferences.kt`,
  `gradle/libs.versions.toml`.
