---
id: B-170
title: "No offered seed and style draws the accent in the error's colour"
status: done
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

## Done — 2026-10-31

**The metric and the threshold.** HCT hue distance between `accent` (and `accentText`, which is
*Seeding*'s label) and `error`, at least 30° — the same metric and the same number B-167 holds the
accent to against `warning`, for the same reasons: all three are light colours at one tone (the
accent, `error` and `warning` all near tone 80), so hue is what parts them, and 30° is one hue
name's width (the amber band `warning` is held to is 66–98°, 32° wide). One threshold for the three
state colours keeps the rule simple: no state colour may be within one hue name of another.

**Why the orange accents do not pass as they were.** At 26–28° they are under a hue name from the
error's salmon, and `#FCB484` beside `#FFB4AB` at one tone is told apart mostly by a little yellow;
holding them to the same 30° as the warning costs them five degrees. B-167 said an orange accent
has nowhere to go between error and amber — that was for turning it a full 30° *towards the red*.
Turning it *away* from the red has room: from the error at 25.1° to the far edge of amber `warning`
may reach (82.5° + 15° = 97.5°) is 72°, enough for an accent with 32° on either side, as long as
the warning moves on with it — which B-167's rule already does.

**Measured, every swatch × style.** Read off a throwaway test on the Linux box; before is `main`
(`a6448f1`), after is this change. Hue in HCT degrees; *A–E* is the accent's distance from `error`
(25.1° in every pair, the error family does not follow the seed); `accentText` equals `accent` in
every Material pair, so its distance is the same. Teal *Tonal* is the hand-written design. **Bold**
is below 30°.

| Seed | Style | Accent before | after | Accent hue before → after | A–E before → after | Warning hue before → after | A–W before → after |
|---|---|---|---|---|---|---|---|
| Teal | Tonal (design) | `#4FD9C2` | `#4FD9C2` | 182.2 → 182.2 | 157.1 → 157.1 | 82.5 → 82.5 | 99.6 → 99.6 |
| Teal | Vibrant | `#00DCC2` | `#00DCC2` | 182.3 → 182.3 | 157.3 → 157.3 | 97.0 → 97.0 | 85.3 → 85.3 |
| Teal | Expressive | `#FCB573` | `#FCB573` | 61.9 → 61.9 | 36.8 → 36.8 | 94.0 → 94.0 | 32.1 → 32.1 |
| Teal | Neutral | `#B6C8C3` | `#B6C8C3` | 183.5 → 183.5 | 158.5 → 158.5 | 97.0 → 97.0 | 86.5 → 86.5 |
| Green | Tonal | `#A1D195` | `#A1D195` | 143.7 → 143.7 | 118.6 → 118.6 | 97.0 → 97.0 | 46.6 → 46.6 |
| Green | Vibrant | `#00E32A` | `#00E32A` | 143.6 → 143.6 | 118.6 → 118.6 | 97.0 → 97.0 | 46.6 → 46.6 |
| Green | Expressive | `#FDB1AA` | `#F3B2CD` | 23.4 → 352.9 | **1.7** → 32.2 | 66.6 → 66.6 | 43.2 → 73.8 |
| Green | Neutral | `#BDC8B5` | `#BDC8B5` | 142.3 → 142.3 | 117.2 → 117.2 | 97.0 → 97.0 | 45.2 → 45.2 |
| Blue | Tonal | `#A3C6FD` | `#A3C6FD` | 259.4 → 259.4 | 125.7 → 125.7 | 97.0 → 97.0 | 162.3 → 162.3 |
| Blue | Vibrant | `#A3C6FD` | `#A3C6FD` | 259.4 → 259.4 | 125.7 → 125.7 | 97.0 → 97.0 | 162.3 → 162.3 |
| Blue | Expressive | `#A2D188` | `#A2D188` | 138.6 → 138.6 | 113.6 → 113.6 | 97.0 → 97.0 | 41.6 → 41.6 |
| Blue | Neutral | `#BDC4D2` | `#BDC4D2` | 257.0 → 257.0 | 128.1 → 128.1 | 97.0 → 97.0 | 159.9 → 159.9 |
| Violet | Tonal | `#CCBBFC` | `#CCBBFC` | 298.0 → 298.0 | 87.1 → 87.1 | 66.6 → 66.6 | 128.6 → 128.6 |
| Violet | Vibrant | `#CCBBFC` | `#CCBBFC` | 298.0 → 298.0 | 87.1 → 87.1 | 66.6 → 66.6 | 128.6 → 128.6 |
| Violet | Expressive | `#77D5BD` | `#77D5BD` | 178.1 → 178.1 | 153.0 → 153.0 | 97.0 → 97.0 | 81.0 → 81.0 |
| Violet | Neutral | `#C9C1D3` | `#C9C1D3` | 300.4 → 300.4 | 84.7 → 84.7 | 66.6 → 66.6 | 126.2 → 126.2 |
| Pink | Tonal | `#FAAED3` | `#FAAED3` | 349.9 → 349.9 | 35.2 → 35.2 | 66.6 → 66.6 | 76.7 → 76.7 |
| Pink | Vibrant | `#FCADD3` | `#FCADD3` | 350.3 → 350.3 | 34.7 → 34.7 | 66.6 → 66.6 | 76.3 → 76.3 |
| Pink | Expressive | `#7ECEF3` | `#7ECEF3` | 229.6 → 229.6 | 155.5 → 155.5 | 97.0 → 97.0 | 132.5 → 132.5 |
| Pink | Neutral | `#D7BDC7` | `#D7BDC7` | 350.2 → 350.2 | 34.8 → 34.8 | 66.6 → 66.6 | 76.4 → 76.4 |
| Orange | Tonal | `#FCB484` | `#FAB681` | 52.8 → 57.3 | **27.8** → 32.2 | 84.8 → 89.5 | 32.0 → 32.2 |
| Orange | Vibrant | `#FCB484` | `#FAB681` | 52.8 → 57.3 | **27.8** → 32.2 | 84.8 → 89.5 | 32.0 → 32.2 |
| Orange | Expressive | `#C6BCFC` | `#C6BCFC` | 292.8 → 292.8 | 92.2 → 92.2 | 66.6 → 66.6 | 133.8 → 133.8 |
| Orange | Neutral | `#DBBEAE` | `#DBBFAD` | 51.0 → 55.5 | **26.0** → 30.4 | 82.5 → 87.4 | 31.5 → 31.9 |

Four pairs failed: green *Expressive* and the three orange accents. Twenty were untouched, byte for
byte. Pink's accents (34.7–35.2°) pass as they were, so pink *Tonal* — offered since B-161 beside
the error rows — is the measure of what this rule calls far enough.

**The rule, not a withdrawal.** In `SeededDesign.kt`, generated schemes only: where the generated
`primary` — the accent family's anchor — is nearer than 32° of hue to the generated `error` (30
plus two for 8-bit rounding, as B-167's warning rule), the anchor turns away from the error, on the
side it is already on, to 32°, its chroma and tone kept; every accent role follows, since the family
takes its hue from the anchor. B-167's warning rule then keeps the warning 32° from the accent that
is actually drawn, within 15° of the design's amber. Green *Expressive*'s accent was rotated onto the
error by the style itself, 120° from its seed, so turning it 29.5° further — to a pink, `#F3B2CD`,
where the pink swatch's own accent sits — costs it nothing the style had not already spent; and
with room on both sides for every offered pair, no combination needs to come off the screen. The
orange accents move 4.5° towards the amber (to 4.6° from the seed's own hue; `anotherSeedMovesTheAccent`
allows 25°), and their warning 4.7–4.9° further from the design's amber, to 89.5° and 87.4°, still
inside B-167's 15° bound. Orange *Neutral* lands at 30.4°, not 32°: at chroma 12 an 8-bit colour
rounds its hue by more than two degrees; it passes, with less room than the others.

**Test.** `SeededColorsTest.theAccentIsNotTheErrorsColour`, over all 24 offered pairs, the design
included, `accent` and `accentText` each. **Mutation:** with the rule removed (the anchor left as
generated) it failed naming exactly green *Expressive* (1.7°), orange *Tonal* and *Vibrant* (27.8°)
and orange *Neutral* (26.0°), each for `accent` and `accentText`, and only it failed — the warning
test still passed, because the warning follows whatever accent is drawn; restored, it passes.

**Goldens.** `viddikRecord` on the mac changed exactly two, both orange: `main_window-orange.png`
and `settings_screen-orange.png`; every other golden, violet included, is byte-identical. Read,
both. Main window: the seeding bars, glyphs and labels are `#FAB681` (were `#FCB484`, read off the
pixels), a touch more golden than the error row's salmon `#FFB4AB`, which did not move; the
checking bar went `#EFC56F` → `#EAC76F`, a slightly yellower amber. Settings: the selected style
segment, the toggle and the focused field's border in the new accent; the orange swatch is the seed
itself and did not change.

**Verified.** `:ui:check` (viddikVerify excluded, the goldens recorded on the mac) on the Linux
box: 336 tests in 52 classes, no failures, read off `ui/build/test-results/desktopTest/*.xml` —
B-167's 335 and this one; `SeededColorsTest` is 9. `make check` on the mac exits 0.

**Not covered.** Seeds typed into the settings file by hand: for an accent already inside the amber
band the warning cannot reach 32° within its 15° bound, and no test holds such a seed to it.
