---
id: B-167
title: "Every seeded scheme keeps the error row's tint and the accent apart from the surface and the warning"
status: done
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

## Done — 2026-10-31

**The two metrics, and why these.**

- **Error row tint vs surface: distance in Oklab's a–b plane, at least 0.02.** Oklab because it
  is built to be perceptually uniform and is two matrices and a cube root — the test carries its
  own copy of Ottosson's matrices, independent of the Compose colour space the derivation uses.
  The a–b plane and not the full ΔE because the derivation keeps every tone: the tint is one
  lightness step above the surface (ΔL 0.019–0.024) under *every* seed, the same step a raised row
  has, and what a warm surface takes away is the redness. The full ΔE lets the lightness step pass
  for it — orange under *Vibrant* was 0.021 in ΔE and 0.009 in the a–b plane, and that is the
  golden where the tint vanished. 0.02 is Oklab's just-noticeable difference, the value CSS Color 4
  maps gamuts by; the design's own pair, `#1F1614` on `#0F1513`, is 0.0239.
- **Accent (and `accentText`, *Seeding*'s colour) vs `warning` (*Checking*, *Stopping*): HCT
  hue at least 30° apart.** HCT because the derivation and `harmonize` both work in it, and hue
  because both are light colours at nearly one tone (≈80) and one chroma, so hue is all that tells
  them apart. 30° is one hue name's width: the amber the existing test holds `warning` to is
  66–98°, 32° wide. The design's teal is 99.6° from its amber.

**Measured, every swatch × style.** Before is `main` (`5d62eec`), after is this change; read off
a throwaway test on the Linux box. *Tint gap* is the a–b distance from `surface`; *warning* is its
HCT hue (before: the harmonised anchor, which the role rounds to within 0.4°); *A–W* is the
accent's hue distance from the warning; *A–E* the accent's from `error`, which this change does
not touch. Teal *Tonal* is the hand-written design. **Bold** is below the threshold.

| Seed | Style | Tint gap before | after | Tint after (HCT chroma) | Warning hue before → after | A–W before → after | A–E |
|---|---|---|---|---|---|---|---|
| Teal | Tonal (design) | 0.0239 | 0.0239 | `#1F1614` (6.0) | 82.5 → 82.5 | 99.6 → 99.6 | 157.1 |
| Teal | Vibrant | 0.0310 | 0.0310 | `#1F1614` (6.0) | 97.0 → 97.0 | 85.3 → 85.3 | 157.3 |
| Teal | Expressive | 0.0281 | 0.0281 | `#1F1614` (6.0) | 72.6 → 94.0 | **10.7** → 32.1 | 36.8 |
| Teal | Neutral | **0.0168** | 0.0255 | `#231512` (9.6) | 97.0 → 97.0 | 86.5 → 86.5 | 158.5 |
| Green | Tonal | 0.0224 | 0.0239 | `#201614` (6.7) | 97.0 → 97.0 | 46.6 → 46.6 | 118.6 |
| Green | Vibrant | 0.0264 | 0.0264 | `#1F1614` (6.0) | 97.0 → 97.0 | 46.6 → 46.6 | 118.6 |
| Green | Expressive | 0.0252 | 0.0252 | `#1F1614` (6.0) | 67.0 → 66.6 | 43.6 → 43.2 | **1.7** |
| Green | Neutral | **0.0147** | 0.0250 | `#231411` (10.3) | 97.0 → 97.0 | 45.2 → 45.2 | 117.2 |
| Blue | Tonal | 0.0231 | 0.0245 | `#201614` (6.7) | 97.0 → 97.0 | 162.3 → 162.3 | 125.7 |
| Blue | Vibrant | 0.0307 | 0.0307 | `#1F1614` (6.0) | 97.0 → 97.0 | 162.3 → 162.3 | 125.7 |
| Blue | Expressive | 0.0244 | 0.0244 | `#1F1614` (6.0) | 97.0 → 97.0 | 41.6 → 41.6 | 113.6 |
| Blue | Neutral | **0.0174** | 0.0245 | `#221512` (8.9) | 97.0 → 97.0 | 159.9 → 159.9 | 128.1 |
| Violet | Tonal | **0.0195** | 0.0259 | `#221512` (8.9) | 67.0 → 66.6 | 129.0 → 128.6 | 87.1 |
| Violet | Vibrant | 0.0262 | 0.0262 | `#1F1614` (6.0) | 67.0 → 66.6 | 129.0 → 128.6 | 87.1 |
| Violet | Expressive | 0.0200 | 0.0254 | `#221512` (8.9) | 97.0 → 97.0 | 81.0 → 81.0 | 153.0 |
| Violet | Neutral | **0.0155** | 0.0239 | `#231512` (9.6) | 67.0 → 66.6 | 126.6 → 126.2 | 84.7 |
| Pink | Tonal | **0.0110** | 0.0248 | `#25130F` (12.7) | 67.0 → 66.6 | 77.1 → 76.7 | 35.2 |
| Pink | Vibrant | **0.0156** | 0.0242 | `#25130F` (12.7) | 67.0 → 66.6 | 76.7 → 76.3 | 34.7 |
| Pink | Expressive | **0.0097** | 0.0257 | `#28120D` (15.7) | 97.0 → 97.0 | 132.5 → 132.5 | 155.5 |
| Pink | Neutral | **0.0130** | 0.0255 | `#241410` (11.1) | 67.0 → 66.6 | 76.8 → 76.4 | 34.8 |
| Orange | Tonal | **0.0024** | 0.0256 | `#29120C` (16.6) | 67.4 → 84.8 | **14.6** → 32.0 | 27.8 |
| Orange | Vibrant | **0.0094** | 0.0245 | `#2B100A` (19.8) | 67.4 → 84.8 | **14.6** → 32.0 | 27.8 |
| Orange | Expressive | **0.0093** | 0.0250 | `#29110C` (17.3) | 67.0 → 66.6 | 134.2 → 133.8 | 92.2 |
| Orange | Neutral | **0.0100** | 0.0241 | `#251410` (11.8) | 67.4 → 82.5 | **16.4** → 31.5 | 26.0 |

Thirteen of the twenty-four failed the tint gap — not only the warm seeds: every *Neutral*, whose
surface keeps a trace of the seed and whose tint, scaled with nothing, is the same `#1F1614`
everywhere (the error family does not follow the seed, so the tint never moved while the surface
did). Four failed the hue gap: the three orange accents, and teal under *Expressive*, whose accent
MaterialKolor puts at an orange hue (`#FCB573`).

**The two rules, in `SeededDesign.kt`, generated schemes only.**

- **A chroma floor for the tint, relative to the surface.** Where the tint is nearer the surface
  in the a–b plane than the design drew it (0.0239, computed at run time from the hand-written
  pair, not written down), it gains HCT chroma in its own hue and tone, half a unit at a time, until
  it is that far. Tone stays, so `everyRoleKeepsTheDesignsTone` holds; hue stays, so it is red.
  The floor is the design's distance rather than the test's 0.02 so a seed comes out as far apart
  as the design, not one rounding away from the threshold; it lifts three combinations that were
  already above 0.02 (green and blue *Tonal*, violet *Expressive*).
- **A hue nudge for the warning, away from the accent — not the accent away from the warning.**
  The item asked for an accent nudge; it cannot work for orange. An orange accent (52.8°) sits
  between `error` (25.1°) and the warning (67.4° after harmonising), 42° between the two: turned 30°
  from the amber it lands 12° from the error's red, which is the collision the swatch row was
  drawn without red to avoid, and turned the other way it is yellow, not orange. What brought the
  warning to 14° from the accent is `harmonize` itself, which turned the amber 15° towards the
  orange seed; so the warning is harmonised, and where that leaves it nearer than 32° (30 plus two
  for 8-bit rounding of the hue) to the accent, it turns back, away from the accent, never further
  than 15° from the design's amber — the bound `harmonize` already had, so
  `warningStaysAmberWhateverTheSeed` holds unchanged. Orange's warning goes to 84.8° (82.5° under
  *Neutral*), the design's own amber within 2.3°; teal *Expressive*'s to 94°. The accent stays the
  seed's, so `anotherSeedMovesTheAccent` holds too.
- **Nothing was withdrawn.** Every combination passes with the two rules, so none needs to be taken
  off the screen.

The default — the design's teal under *Tonal* — is the hand-written scheme and never runs this code.

**Tests.** `SeededColorsTest` gains `theErrorRowTintStaysApartFromTheSurface` and
`theAccentStaysApartFromTheWarning`, over all 24 offered pairs, the design included, by the names
the settings show. **Mutation:** with the floor and the nudge both removed, the first failed naming
the thirteen combinations in bold above, with the before values, and the second naming teal
*Expressive* (10.7°) and orange *Tonal*, *Vibrant* (14.2°) and *Neutral* (16.0°), each for
`accent` and `accentText`; restored, both pass.

**Goldens.** `viddikRecord` on the mac changed exactly two: `main_window-orange.png` and
`main_window-violet.png`; the other twenty-four, both `settings_screen-*` seeded ones included, are
byte-identical. Read, both. Orange: the error row is a dark red band, `#2B100A` on the surface's
`#1D110B` (it was `#1F1614`, 0.0094 apart in the a–b plane; the pixels now measure 0.0245), and
*Checking* and *Stopping* are a yellower amber beside *Seeding*'s peach. Violet: the error row
`#221512` on `#141318` (0.0195 → 0.0259 from the pixels); its warning did not move.

**Verified.** `:ui:check` (viddikVerify excluded, the goldens recorded on the mac) on the Linux
box: 335 tests in 52 classes, no failures, read off `ui/build/test-results/desktopTest/*.xml`;
`SeededColorsTest` is 8 (B-161's 6 and these 2). `make check` on the mac exits 0.

**Left out, and found.** The accent's distance from `error` is measured above and not asserted:
green under *Expressive* puts the accent at 23.4° — `#FDB1AA`, the error's own salmon (`#FFB4AB`)
within 1.7° — so every seeding row's glyph and bar there are drawn in the error's colour. The orange
accents are 26–28° from it. That is a separate distinction, not this item's, and a candidate for its
own.
