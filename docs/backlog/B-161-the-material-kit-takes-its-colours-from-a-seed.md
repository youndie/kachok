---
id: B-161
title: "The Material kit takes its colours from a seed the person picks, through MaterialKolor"
status: done
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
- **Dark only — the owner's decision, 2026-10-30:** no light scheme and no following the system.
  MaterialKolor produces a light scheme just as readily, and `Colors.kt` explains why there is
  none: the design is dark-first, and a light scheme generated here would be a second design
  nobody drew.
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

## Done — 2026-10-30

**What exists.** `MaterialKit` is a class: `MaterialKit.of(MaterialColours(seed, style))`, equal by
its colours and cached for the last pair, so the root's recompositions do not redraw the window.
`MaterialKit.Default` — the design's teal `#4FD9C2` under *Tonal* — **is the hand-written scheme,
not a generated one**: `KachokDarkColors`, `KachokPalette` and `KachokWarning` stay the reference,
and MaterialKolor's tonal-spot scheme for the same teal is a calmer, different teal. Every other
pair, the teal under another style included, is generated in `ui/theme/material/SeededDesign.kt`
by `dynamicColorScheme(seed, isDark = true, style, SPEC_2021)` — the spec named so a library
release that moves its default cannot move a golden; contrast is the default and −1.0 is never
asked for. `com.materialkolor` is named in `ui/theme/material/` only, and `make check` now refuses
it anywhere else.

The *Colours* row sits under *Interface* in the same section: six swatches (teal, green, blue,
violet, pink, orange — no red beside the error rows, no amber beside *checking* and *stopping*) and
four styles as segments (*Tonal*, *Vibrant*, *Expressive*, *Neutral*; *Rainbow* and *Fruit salad*
do *Expressive*'s job, *Monochrome* leaves a selected row grey on grey, *Fidelity* and *Content*
put the seed into the container and read as a muddier *Vibrant* at this design's tones).
`Setting.swatches` and `SettingChange.Swatched` carry the seed; the styles are the row's
`choices`. **Under oldge-ui the row is absent** — not empty — until B-162 makes the skins its
contents; the Material seed and style are kept in the preferences meanwhile, so coming back finds
them. Stored in `settings.properties` as `colourSeed=#RRGGBB` and `colourStyle=<name>`; a seed that
is not `#RRGGBB` reads as the design's teal and a style this build does not know as *Tonal*, each
key on its own, so a damaged seed does not cost the style. Any well-formed hex is a seed, not only
the six; the swatch row then rings none. `App.kt` builds the kit from both above `AppFrame`, so a
pick redraws the window, title bar included, without a restart.

**How every role is derived — every role, not only the ones Material has no name for.** The
hand-written scheme borrows Material's names at tones Material does not give them: its
`surfaceVariant` is the raised ground at tone 10 where Material's is 30, its `onSurfaceVariant`
the faint text at 60 where Material's is 80, its `outline` a hairline at 28 where Material's is 60.
Copying a generated scheme across by name would draw the column header three times brighter, the
hairlines as borders, and two of the three text levels at one tone. So the generated scheme gives
*anchors*, and each of this design's roles keeps its relation to its family's anchor, in HCT:
**the hand-written role's own tone** (L\*, so every contrast the design drew survives any seed),
**the anchor's hue plus the role's offset** from the hand-written anchor (the error row's tint sits
six degrees warmer than `error`, and still does), and **the role's chroma scaled** by the generated
anchor's over the hand-written one's (so *Neutral* quiets a family together and *Vibrant* lifts
it). The relations are computed at run time from the hex values in `Colors.kt`; nothing is copied
into a second table. The families, as hand-written anchor → generated anchor:

| Family | Anchors | Roles |
|---|---|---|
| accent | `#4FD9C2` (T79) → `primary` (T80) | `accent`, `onAccent`, `accentContainer`, `onAccentContainer`, `accentBright`, `accentContainerHigh`, `selectedFigure`, `selectedTrack` |
| neutral | `#DDE4E1` (T90) → `onSurface` (T90) | `text`, `surface`, `raised`, `raisedHigh`, `raisedHighest`, `panel`, `rowHairline`, `neutralCard`, `footnoteGround` |
| neutral variant | `#BEC9C6` (T80) → `onSurfaceVariant` (T80) | `textMuted`, `textFaint`, `outline`, `divider`, `stoppingBar`, `dialogBorder` |
| error | `#FFB4AB` (T80) → `error` (T80) | `error`, `onError`, `errorContainer`, `errorFigure`, `errorRowTint`, `errorTrack`, `degradedBanner` |
| warning | `#F3C46B` → that amber `harmonize`d towards the generated `primary` | `warning`, `onWarning`, `warningContainer`, `onWarningContainer`, `warningCard`, `onWarningCard` |

The anchors are high-tone roles on purpose: at tone 6 the gamut leaves a surface almost no chroma,
and a ratio taken there would describe sRGB rather than the scheme. The hover hint is the one pair
copied by name (`inverseSurface`, `inverseOnSurface`): the hand-written scheme never set them
either, it takes Material's baseline. `MaterialTheme` gets the generated scheme with the design's
roles written over the names the hand-written one uses, so `AppFrame`'s bar matches the window.
`harmonize` turns a hue at most 15° towards the seed, so `warning` stays between 66° and 98° of
hue for every seed and style offered — amber.

**Two literals became roles.** The settings footnote's ground (`#111716`) and the add and remove
dialogs' border (`#303836`) were colours written in the screens, which the first orange golden
showed as a teal stripe under a brown window. They are `KachokColors.footnoteGround` and
`.dialogBorder` now, in the neutral and neutral-variant families; the oldge kit returns the same
two literals, so its goldens did not move, and mixing them from the skin is B-162's.

**Goldens.** The sixteen existing ones are byte-identical after `viddikRecord` on the mac (the
working tree showed only the four new files), so the default draws the hand-written design. New:
`main_window-violet.png` (violet, *Tonal*) and `main_window-orange.png` (orange, *Vibrant*), the
design's sixteen rows with the title bar; `settings_screen-violet.png` and
`settings_screen-orange.png` with the *Interface* section moved to the top so the ringed swatch and
the chosen style are in the picture. Read, all four. In both windows the three text levels stay
apart, *Checking* and *Stopping* are amber, the error row's name, figures and bar are red, and the
selected row reads — in violet as a muted lilac band, in orange as a brown one, because an orange
at tone 30 is brown. **What the orange one shows that is worse:** the error row's tint, six
degrees off the error's hue, nearly disappears into a surface that is itself warm, so under a warm
seed the error row is told by its text and bar rather than its ground; and *Seeding*'s peach and
*Checking*'s amber are closer than the design's teal and amber. Both are readable; neither is the
design's own contrast.

**Verified.** `:ui:check` on the Linux box: 316 tests in 50 classes, no failures, read off
`ui/build/test-results/desktopTest/*.xml` — B-160's 304, plus `SeededColorsTest` (6), two in
`KitSwitchTest` and four in `StoredPreferencesTest`; the ProGuard release image built and passed
its preflight with MaterialKolor in it, no rule added. Every new test was mutated and failed: the
design's seed generating (`theDesignsSeedIsTheHandWrittenSchemeFieldForField` and both
`KitSwitchTest` cases), one hand-written role wired to a neighbour, `warning` not harmonised,
derived tones taken from the anchor, the error family following the seed, the accent left teal, a
malformed seed or an unknown style read as the fallback, a seed hex accepting a sign, the seed
written under another key, a dead swatch, the row shown under oldge-ui, and `InterfaceKit.kit`
ignoring the colours.

**Deliberately left out.** A light scheme and system-follow (the owner's decision above). A free
colour picker: any `#RRGGBB` in the file works, the screen offers six. Contrast levels: the
default only. The oldge kit's two new roles from its skin (B-162). The app was not launched; the
switch is proven in a composition wired the way `App.kt` wires it.
