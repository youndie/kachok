---
id: B-162
title: "The oldge-ui kit offers its skins as its colour choice"
status: done
priority: P3
size: S
stage: phase-2-ui
blocked_by: [B-160]
---

# B-162 — The oldge-ui kit offers its skins

oldge-ui's colours are not a seed but a closed set: `OldgeSkin` is an enum — Toxic, Media and
Crystal today — each carrying its own colours and shadows, passed to `OldgeTheme(skin = …)`.

- **The decision and its reason.** The colour setting belongs to the kit, not to the window: with
  the Material kit chosen it is B-161's seed and style, with oldge-ui it is a choice of skin. One
  *Colours* row whose contents depend on the kit, rather than two rows of which one is always
  meaningless. The list of skins is read from `OldgeSkin.entries`, so a skin added to the library
  appears here with a version bump and no change in kachok.
- A stored skin name the library no longer has falls back to its first skin rather than failing.
- Rejected: a seed for oldge-ui too. Its skins are drawn — gloss, glow and shadows tuned per skin —
  and a generated palette would be a fourth skin nobody designed.

- AC: with oldge-ui chosen, the person switches between its skins and the window redraws without a
  restart; the goldens hold one frame per skin.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/StoredPreferences.kt`.

## Done — 2026-10-30

**What exists.** `OldgeKit` is a class: `OldgeKit.of(skin)` hands back one instance per entry of
`OldgeSkin`, built once, so the root's recompositions do not redraw the window and a skin a
release of oldge-ui adds is a kit with the version bump. `OldgeKit.Default` is Toxic, the library's
own default and the skin B-160 drew. Every role comes from the chosen skin's tokens by the mapping
B-160 wrote for Toxic, unchanged. `InterfaceKit.kit(colours, skin)` hands each kit its own choice
and `Preferences` keeps both — `materialColours` and `oldgeSkin` — so Material, oldge-ui,
Material finds the seed where it was left, and the other way round.

The *Colours* row is one row whose contents are the kit's: under oldge-ui it is
`OldgeSkin.entries` as swatches, each the skin's `ground` with its `accent` as an 8 dp dot
(`Swatch.mark`). Two of the three skins share their lime, so the ground is what tells them apart
and the dot says the square is a skin rather than a seed. The row's note lists the names from the
same entries. A swatch picked reports `SettingChange.Swatched(Colours, name)`, and
`Preferences.swatched` reads it as the colour choice of the kit the row was drawn for. Stored in
`settings.properties` as `oldgeSkin=<name>` (the library's enum name). A name the library no
longer has reads as its first skin, not as the caller's fallback, by the rule `interface` follows.

**The two roles B-161 left as literals.** The settings footnote's ground (`#111716`) and a
dialog's border (`#303836`) are the Material design's teal-greys. They cannot be written from
Toxic's tokens. Its grounds and lines are all green and the literals have almost no chroma, so no
token is either value and no mix of two lands on one exactly. **Toxic keeps both literals**, which is why its four goldens did
not move. What the other skins get is the literals' *place* between Toxic's tokens. In Oklab
lightness, the space Compose's `lerp` mixes in, `#111716` sits 0.070 of the way from Toxic's
`surface` to its `ground`, and `#303836` 0.021 of the way from its `line` to its `edge`. So
`footnoteGroundOf` is `lerp(surface, ground, 0.07)` and `dialogBorderOf` is
`lerp(line, edge, 0.02)`, and `OldgeSkinsTest` holds both fractions to that claim: mixed from
Toxic's own tokens, each has the literal's lightness within 0.002. In practice the two are nearly
the skin's `surface` and `line` (Media `#0C2964` and `#274A90`, Crystal `#FDFEFD` and `#CDDAC5`).

**Goldens.** The 19 others are byte-identical after `viddikRecord` on the mac. **One existing
golden changed, and had to:** `settings_screen-oldge.png` puts the *Interface* section first, and
the *Colours* row now appears there under oldge-ui, which is this item's acceptance criterion. Read
row by row against the old file, it is identical down to y = 114. Below that, everything is the
old picture moved down 60 px — the new row, 50 dp at the sheet's 1.2 — except five anti-aliased
text pixels and the strip clipped above the fixed footnote. New: `main_window-media.png` and
`main_window-crystal.png` (the design's sixteen rows with the title bar and «Мастер и Маргарита
(1…» in row three), `settings_screen-crystal.png` (Crystal ringed) and `remove_dialog-crystal.png`.
The last one is there because a dialog is the only place the derived border shows.

I looked at all five. **Media** reads throughout: royal-blue bezel and body, white text with
`inkMuted` paused rows clearly a level down, *Checking* and *Stopping* amber, the error row salmon
on its own tint, the selected row a brighter blue band, Cyrillic in the same face as its
neighbours. **Crystal** is the one light frame of this window: the dark smoked bezel, a white
body, ink text and grey-green muted rows, *Checking*/*Stopping* in its brown `warning` (5.9:1),
the error row in its darker `danger` (5.7:1) on a pink tint, the selected row a deep green band
with white text, and the dialog legible with its border visible on the mint panel. **What Crystal
shows that is worse:** wherever a screen sets text in `accent` — *Seeding*, the ∞ beside it, the
accent figures in the details panel — the text is Crystal's lime `#8FE010` on white, 1.6:1, and
reads faintly. A changed field's lime outline on white is faint for the same reason. The library
has `accentInk` (`#357000` in Crystal, 6.1:1) for accent-coloured text. Using it means a role for
"accent as text" that the screens do not have, and mapping that role to `accentInk` moves Toxic's
goldens (`#A8FF3A` against `#A4FF1F`). Filed as a finding instead of done here.

**Verified.** `:ui:check` on the Linux box: 326 tests in 51 classes, no failures, read off
`ui/build/test-results/desktopTest/*.xml`. That is B-161's 316 plus `OldgeSkinsTest` (5), two more
in `KitSwitchTest` (where B-161's "the row is absent under oldge-ui" became "the row is the
kit's") and three in `StoredPreferencesTest`. The ProGuard release image built and passed its
preflight. Every new test was mutated and failed:
- a kit built afresh per call;
- every skin mapped from Toxic's tokens;
- the footnote ground always Toxic's literal;
- the fraction moved to 0.3;
- an unknown name read as no name;
- the skin not written;
- a missing key read as Toxic instead of the fallback;
- the *Colours* row under oldge-ui showing the seeds;
- `InterfaceKit.kit` ignoring the skin;
- choosing a kit resetting the skin.

**Deliberately left out.** `accentInk` for accent-coloured text (the finding above). The skins'
`OldgeShadows` are the library's to apply inside its own components, as `OldgeTheme(skin)` already
does, and the window draws no shadows of its own beyond the tooltip's. The app was not launched:
the switch is proven in a composition wired the way `App.kt` wires it.
