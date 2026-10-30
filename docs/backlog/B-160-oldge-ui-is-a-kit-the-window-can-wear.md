---
id: B-160
title: "oldge-ui is a second kit, and the settings choose between the two"
status: done
priority: P3
size: L
stage: phase-2-ui
blocked_by: [B-159]
---

# B-160 — oldge-ui is a kit the window can wear

[oldge-ui](https://github.com/youndie/oldge-ui) is the portfolio's Compose implementation of a
design system in mid-2000s style — glossy frames, chrome orb buttons, LCD readouts — with 51
components, `jvm("desktop")` among its targets, and `OldgeTheme(skin = …)` at its root. It becomes
the second implementation of B-159's kit, and the window gains an *Interface* setting with the two.

- **The decision and its reason.** The kit maps kachok's roles onto oldge-ui's components where the
  library has one (`OldgeButton`, `OldgeSwitch`, `OldgeTextField`, `OldgeMenu`, `OldgeDialog`,
  `OldgeProgressBar`, `OldgeBanner`, `OldgeReadout` for the figures) and draws the rest from
  `OldgeTheme`'s tokens. **The library is a mobile kit**: it has no table, no column header, no
  splitter and no dense desktop row, which are most of this window. Those are drawn in its tokens
  here, and anything that turns out to be generally useful goes into oldge-ui rather than staying a
  copy in kachok.
- **The components move into the kit here, not in B-159.** B-159 put the tokens and `Text` behind
  `KachokKit` and left the window's own components — toggle, fields, dialog button, toolbar
  action, progress cell, banner — drawn on `foundation` from those tokens. Each one oldge-ui draws
  differently becomes a member of the kit in this item, with the Material kit's implementation
  being the code that is there today.
- The setting is stored with the others in `settings.properties`, applies without a restart, and
  falls back to the current kit on a value it does not know — a settings file written by a newer
  build must not stop an older one from opening.
- **Two findings from oldge-ui's own research that land here.** Share Tech Mono and Silkscreen, the
  fonts the design system names, contain no Cyrillic at all, and torrent names here are often
  Russian — the kit has to say which font draws them and the goldens have to include such a name.
  And `OldgeWindowBar` is a title bar of its own, while the window's frame is appframe's: one of the
  two draws it, decided here.
- **Where it resolves from.** oldge-ui is published to reposilite only (`0.1.0` and
  `0.1.1.<build>`), and every dependency of this public repository so far is on Maven Central. A
  release of oldge-ui to Central comes first, or the item records why a second repository is
  acceptable.
- Rejected: copying the components into kachok. Two copies of one design system drift, and the
  library is ours to change.
- Not covered: choosing among the skins (B-162).

- AC: the person picks *oldge-ui* in the settings and the whole window — list, details, dialogs,
  status bar — redraws in it without a restart, and back; the goldens cover both kits, a Cyrillic
  torrent name among them.
- Anchors: `ui/build.gradle.kts`, `gradle/libs.versions.toml`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/StoredPreferences.kt`.

## Done — 2026-10-30

**What exists.** `ui/theme/oldge/OldgeKit.kt`: the second `KachokKit`, oldge-ui's Toxic skin. Every
`KachokColors` role is a skin token where the design system has one for the job — `accent`,
`ink`/`ink-muted` for the outer text levels, `surface`/`surface-2`/`well` for the grounds,
`line`/`edge`, `danger`, `warning`, `select`, `balloon` — and a mix of tokens where it has none: the
middle text level halfway between `ink` and `ink-muted`, the row and card tints as `danger` or
`warning` over `surface`, so the warning family stays amber and the error family red because their
sources are. `Root` is `OldgeTheme(Toxic)` with a `MaterialTheme` inside it whose scheme comes from
the same tokens (the title bar is the skin's `bezel`, its title `on-bezel`) and whose type is the
design system's `ui` face; the library's press indication is put back inside it, because
`MaterialTheme` would otherwise replace it with a ripple in the whole window. The tooltip is the
`balloon` plate. Three components moved into the kit, each with the Material kit's drawing being
the code that was there, moved to `theme/material/MaterialComponents.kt`: the settings switch
(oldge: `OldgeSwitch` with its label as the accessible name only), the dialog button (`OldgeButton`
at its dense size) and the row's progress bar (`OldgeProgressBar`'s look — a sunken well, glossy
8 dp blocks with 2 dp gaps — at 10 dp, in the colours `RowColors.kt` chose for the row's state).
`KachokKit` members have no defaults; `DialogButton` keeps its defaults in the top-level wrapper, as
`Text` does.

The *Interface* setting: `Setting.choices`, `SettingChange.Chose`, a segmented control drawn from
the kit's tokens in `settings/Settings.kt`; `SettingKey.Interface` in its own section, last on the
screen. `InterfaceKit` names the two kits by what `settings.properties` stores (`interface=material`
or `oldge-ui`). `App.kt` reads the choice off `ClientModel.preferences` above `AppFrame` and passes
the kit to `KachokTheme(kit = …)`.

**Owner decisions, 2026-10-30.**
- **oldge-ui resolves from reposilite's snapshots**, pinned to `io.github.youndie:oldge-core:0.1.1.14`.
  A second repository is acceptable because the owner chose it over waiting: a release of oldge-ui
  to Maven Central is a separate, later step, and this build was already not Central-only (AppFrame
  comes from reposilite's releases, sborka from its snapshots). `settings.gradle.kts` names the
  snapshot server *exclusively* for `oldge-core` and `oldge-core-desktop`. The releases
  declaration had to be narrowed from the whole `io.github.youndie` group to AppFrame's two modules
  to make that possible: two exclusive filters cannot overlap — each excludes what it claims from
  every other repository — and the group-wide one made `oldge-core` resolvable from nowhere.
- **The title bar stays `AppFrame`'s.** It carries the host's window controls; `OldgeWindowBar` is
  the in-app top bar of a mobile screen, not a window's title bar. `AppFrame` reads
  `MaterialTheme.colorScheme` itself, so the oldge kit's root provides one, and `make check`'s kit
  guard allows `material3` in `ui/theme/oldge/` too (the Makefile says why). It still refuses the
  Material kit's palette there.

**Which font draws Cyrillic — the finding, with its evidence.** The faces' `cmap` tables were read
directly (a 40-line parser over the TTF files, U+0400–04FF counted): Share Tech Mono 0 of 256,
Silkscreen 0, **Archivo 0**, DejaVu Sans Condensed 98 (Russian complete, `Ё` included), Fira Sans
Bold 240, JetBrains Mono 98, Source Serif 4 150. oldge-ui's public `OldgeText` sets text in the face
its style names and does nothing for a missing glyph — the companion-face join (PT Mono, Tiny5) is
`OldgeScriptText`, which is internal and used only inside the library's own components. So the kit
decides by swapping families: whatever a screen sets in Archivo is drawn in DejaVu Sans Condensed
(`ui`), Source Serif in Fira Sans (`title`), and JetBrains Mono stays — the design system's mono
would be Share Tech Mono, whose LCD look suits a figure and which cannot draw a verbatim error from a
Russian-locale system. `main_window-oldge.png` shows «Мастер и Маргарита (1…» in the third row in
the same face as its Latin neighbours, no tofu, no fallback face.

The same reading says something about the **Material** kit: Archivo has no Cyrillic, so a Russian
torrent name there is drawn by whatever fallback font the platform's Skia finds — a different face
from the rows around it, and a different one on each OS. Not in this item's scope; filed as a
finding.

**Goldens.** The twelve existing ones are byte-identical after `viddikRecord` on the mac — `git
status` showed only the four new files — so the moved components draw what they drew. New, oldge
kit: `main_window-oldge.png` (the whole window with its title bar, the Cyrillic name in row three),
`settings_screen-oldge.png` (the *Interface* section moved to the top so the chosen segment is in
the picture, `OldgeSwitch` on and off), `add_dialog-oldge.png` and `remove_dialog-oldge.png` (both
answers, a Cyrillic name). Read, all four. The first recording clipped the column header's `RATIO`
to `RATI`: the `section` role at `label`'s 13 sp does not fit nine dense columns, so the kit's
`section` is `label`'s face and weight at 11 sp — re-recorded, and it fits.

**Verified.** `:ui:check` on the Linux box: 304 tests in 49 classes, no failures, read off
`ui/build/test-results/desktopTest/*.xml` — B-159's 300 plus `KitSwitchTest` and three in
`StoredPreferencesTest`. `anUnknownKitIsMaterial` killed a mutant that read an unknown name as
"no value" (the fallback, there `Oldge`); `KitSwitchTest` killed a mutant whose `KachokTheme`
ignored its `kit` argument.

**Deliberately left out.** The skins (B-162). `OldgeTextField`, `OldgeMenu`, `OldgeDialog`,
`OldgeBanner` and `OldgeReadout` for the figures: the window's fields are 30 dp desktop fields where
the library's are 44 dp touch targets with a label slot, its dialogs are already a panel of their
own, and an LCD readout in a 28 dp row is the Share Tech Mono question again. They stay drawn in
the kit's tokens until the owner wants one of them in the library's shape. Not seen in a running
window: the switch was proven in a composition wired the way `App.kt` wires it, and the application
itself was not launched.
