---
id: B-181
title: "A style can turn the chosen colour into another: the swatches show the seed, not the accent"
status: done
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

## Done — 2026-10-31

**What exists.** Under the Material kit each seed swatch is drawn in the accent its seed gives
under the chosen style: `MaterialKit.of(MaterialColours(seed, style)).colors.accent`. The swatch's
stored value (`colourSeed=#RRGGBB`), its ring and its accessible name stay the seed's. Only the
square's colour changed. The pieces:

- `swatchAccents(style)` in `theme/material/MaterialColours.kt` gives the six accents, in
  `SEED_SWATCHES`' order. Each style's list is generated the first time it is asked for and kept
  (a `lazy` per style), because the settings state is rebuilt on every snapshot while the screen
  is open.
- `MaterialKit.accentOf` gives one accent without going through `of`. `of` remembers only the
  last pair, and asking it for six swatches would leave the last swatch's scheme in that cache
  instead of the window's.
- `seedsOf` in `session/SettingsFrom.kt` builds each `Swatch` from the seed's hex and the accent.

Under oldge-ui the row is the skins' and did not change. The design's teal under *Tonal* is the
hand-written scheme, whose accent is the seed, so that square is still `#4FD9C2`.

**Goldens.** `viddikRecord` on the mac changed exactly two, both seeded settings screens, and added
one. The default settings golden and every main-window golden are byte-identical: the *Colours* row
is below the fold of `settings_screen.png`. I looked at all three.

- `settings_screen-violet.png` (violet, *Tonal*). Five squares moved to the tone-80 pastels
  *Tonal* draws: green `#A1D195`, blue `#A3C6FD`, violet `#CCBBFC`, pink `#FAAED3`, orange
  `#FAB681`. The teal is the design's and did not move. The ringed violet square now matches the
  selected *Tonal* segment and the toggle. It was the darker seed `#A78BFA` before.
- `settings_screen-orange.png` (orange, *Vibrant*). Every square moved. The teal is `#00DCC2`, the
  green a loud `#00E32A`, blue and violet as under *Tonal*, pink `#FCADD3`. The ringed orange is
  `#FAB681`, the colour of the *Vibrant* segment beside it.
- New: `settings_screen-violet-expressive.png` shows the *Interface* section alone, 744 × 260,
  violet under *Expressive*. The squares read in order: teal → orange `#FCB573`, green → pink
  `#F3B2CD`, blue → green `#A2D188`, violet → teal `#77D5BD` (ringed, the same colour as the
  *Expressive* segment), pink → sky `#7ECEF3`, orange → lilac `#C6BCFC`. Under *Expressive* the
  row is a permutation of hues.

**Verified.**
- `:ui:check` on the Linux box: 367 tests in 58 classes, no failures. That count includes
  `SwatchAccentsTest`, which has four tests:
  - every swatch holds its scheme's accent, for every style the row's own segments offer (all four)
    and under each of the six seeds chosen;
  - violet under *Expressive* is drawn `#77D5BD`, not `#A78BFA`, and the teal under *Tonal* is
    `#4FD9C2`;
  - a pick stores the seed, not the accent, and the ring finds that swatch again under every style;
  - the oldge skins are still drawn as ground and accent.
- **Mutation:** with the swatch drawn in its seed again, the new test failed in two of its four
  tests. One reported "Green under Tonal, Teal chosen: expected `#A1D195` but was `#7BC96F`". The
  other reported violet still `#A78BFA`. Restored, it passes.
- `make check` on the mac exits 0.

**Not covered.** Under *Expressive* the square named *Teal* is orange, and a screen reader says
*Teal* over it. The name is the seed's, by the decision, and the window it gives is not teal.
Saying both, e.g. "Teal, drawn orange", would need a name for every generated accent, and that is
not part of this item. A seed typed into the settings file by hand rings no swatch, as before.
