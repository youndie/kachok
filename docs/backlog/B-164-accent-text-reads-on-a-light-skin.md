---
id: B-164
title: "Text drawn in the accent stays readable on a light skin: an accent-as-text role in the kit"
status: done
priority: P2
size: S/M
stage: phase-2-ui
---

# B-164 — Accent text reads on a light skin

Found while building B-162. Crystal is oldge-ui's light skin, and every place the screens draw
*text* in `KachokTheme.colors.accent` — *Seeding*, its `∞`, the accent figures in the details panel,
a changed field's outline — comes out lime `#8FE010` on white, about 1.6:1
(`ui/src/desktopTest/snapshots/main_window-crystal.png`). oldge-ui has a token for exactly this,
`accentInk` (`#357000` in Crystal, 6.1:1); the kit has no role to put it in, because on the dark
designs the accent was always readable as text.

- **The decision and its reason.** Add a role for the accent used as text (`accentText`), point the
  screens' text and outline uses at it, and fill it per kit: the Material kits with `accent` (their
  goldens must not move), the oldge kit with `accentInk` — or, if that moves Toxic's goldens
  (`#A8FF3A` against `#A4FF1F`), with `accentInk` only where `accent` fails a stated contrast
  against `surface`. Which of the two is an owner's call, asked before the goldens are re-recorded.
- Rejected: darkening Crystal's accent in the kit. The accent fills buttons and bars too, where the
  lime is the skin.
- Not covered: other contrast pairs on Crystal, which looked readable in the goldens.

- AC: on Crystal, accent-coloured text measures at least 4.5:1 against its ground in the goldens;
  the Material and Toxic goldens are unchanged, or changed by the owner's decision.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/Kit.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/oldge/OldgeKit.kt`.

## Done — 2026-10-30

**The owner's answer (2026-10-30): `accentInk` everywhere in the oldge kit**, every skin, Toxic and
Media included — not the variant that takes the ink only where `accent` fails a contrast. A new role
`KachokColors.accentText` is the accent written as text, a glyph, a figure, a thin outline or a
caret; `accent` stays the accent as a fill. The Material kit and every seeded scheme fill
`accentText` with their own `accent`, so one colour still plays both roles there; the oldge kit
fills it with `skin.colors.accentInk` (Toxic `#A8FF3A` against the fill's `#A4FF1F`, Media
`#B8FF5C`, Crystal `#357000` against `#8FE010`).

**Every `.accent` read in `ui/src/commonMain` and `ui/src/desktopMain`, and what it became** (the
glyphs are Material Symbols drawn as `Text`, so they count as text):

| Where | What it colours | Now |
|---|---|---|
| `settings/Settings.kt` number field | a changed field's outline | `accentText` |
| `settings/Settings.kt` number field | the caret | `accentText` |
| `settings/Settings.kt` folder field | a changed field's outline | `accentText` |
| `settings/Settings.kt` `Segmented` | the chosen segment's ground | `accent` |
| `details/DetailsPanel.kt` tabs | the chosen tab's label | `accentText` |
| `details/DetailsPanel.kt` tabs | the 2 dp indicator under it | `accent` (a bar) |
| `details/DetailsPanel.kt` Overview | a *good* field's figure | `accentText` |
| `details/DetailsPanel.kt` Trackers | *Re-announce*, glyph and word | `accentText` |
| `details/DetailsPanel.kt` Trackers | the DHT card's detail line | `accentText` |
| `details/DetailsPanel.kt` Trackers | a *good* tracker status | `accentText` |
| `details/DetailsPanel.kt` Files | *Ask in order* / *Ask rarest first* | `accentText` |
| `details/DetailsPanel.kt` Files | a wanted file's tick glyph | `accentText` |
| `details/DetailsPanel.kt` Peers | the U and I legend letters, a peer's U and I flags | `accentText` |
| `details/DetailsPanel.kt` copy button | the copy glyph while it says "copied" | `accentText` |
| `add/AddTorrent.kt` | the source card's glyph, a wanted file's tick, the sequential tick, the chosen radio | `accentText` |
| `add/AddTorrent.kt` drop overlay | the dashed 1 dp outline and the download glyph | `accentText` |
| `add/AddTorrent.kt` clipboard prompt | the link glyph | `accentText` |
| `main/ColumnHeader.kt` | the sort arrow | `accentText` |
| `main/StatusBar.kt` | the download rate's arrow | `accentText` |
| `main/Toolbar.kt` filter | the caret | `accentText` |
| `main/EmptyState.kt` | the arrow in the ring | `accentText` |
| `main/EmptyState.kt` | the *Add a torrent* button's ground | `accent` |
| `list/RowColors.kt` `labelColor` | *Seeding*, in the row and the panel header | `accentText` |
| `list/RowColors.kt` glyph cell | a downloading or seeding row's state glyph | `accentText` |
| `list/RowColors.kt` bar cell | metadata, downloading and seeding bars | `accent` |
| `list/RowColors.kt` `onContainer` | a selected row brightens the accent | both `accent` and `accentText` map to `accentBright` |
| `session/SettingsFrom.kt` | a skin swatch's dot (the skin's own `accent`, not the kit's) | `accent` (a fill) |

The kits' own components (`MaterialComponents.kt`'s toggle and dialog button, the oldge kit's
Material scheme for `AppFrame`) read `accent` as a fill and were left alone. The seeding row's `∞`
the item names is drawn in `textFaint` by the row's emphasis rules, not in the accent, so it did not
change; the accent figure beside it is the state glyph and the word *Seeding*.

**Measured.** `OldgeSkinsTest.theAccentWrittenAsTextReadsOnEverySkin` holds `accentText` to at least
4.5:1 (WCAG AA for body text) against the grounds the screens write it on, and to the skin's
`accentInk`; `theMaterialAccentIsItsOwnText` holds the Material default's `accentText == accent`,
and `SeededColorsTest` lists the role, so the design's seed must still draw it as the hand-written
`primary` and every other seed at its tone. The ratios it prints:

| Skin | on `surface` | on `raised` | on `panel` |
|---|---|---|---|
| Toxic | 14.49:1 | 13.14:1 | 16.59:1 |
| Media | 11.37:1 | 9.63:1 | 15.18:1 |
| Crystal | 6.05:1 | 5.51:1 | 6.05:1 |

With `accentText` pointed back at `accent` it fails on `Crystal: accent text on surface is 1.64:1`,
restored. Read off the re-recorded Crystal golden (darkest pixel of each mark against its ground):
*Seeding*, its row glyph, the panel's *Unchoked by them* `4` and *Requests in flight* `61`, the
*Overview* tab label — `#8FE010` at 1.64:1 before, `#357000` at 6.05:1 now; the status bar's arrow
5.51:1 and the sort arrow 5.18:1 on the raised header.

**Goldens.** Six changed, all oldge: `main_window-crystal`, `main_window-oldge`, `main_window-media`,
`settings_screen-crystal`, `settings_screen-oldge`, `add_dialog-oldge` (about 2 800 pixels on each
main window, 1 700 on each settings screen, 950 on the add dialog). Looked at: on Crystal every
accent word, figure and glyph is a dark green that reads on white, and a changed field's outline is
`#357000`; on Toxic and Media the same marks are a step lighter and yellower (`#A4FF1F` →
`#A8FF3A`, → `#B8FF5C` on Media), which is visible only side by side. `remove_dialog-*` has no
accent text and did not move; no Material or seeded golden moved.

**Not covered, for a new item.** On Crystal a changed field's outline (`#357000`) is now hard to
tell from an unchanged one (`edge`, `#5D7A50`): 1.26:1 between them, where the lime was 2.94:1 —
the outline reads against white, the "you changed this" difference barely does. The tab indicator
under the chosen tab stays the lime fill, 1.6:1 on white.

**Verified.** `:ui:check -x :ui:viddikVerify` on the Linux box: 327 tests, 0 failures (two new).
Goldens recorded, `:ui:viddikVerify` and `make check` on macOS green.
