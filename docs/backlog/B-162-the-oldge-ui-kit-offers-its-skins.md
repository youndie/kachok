---
id: B-162
title: "The oldge-ui kit offers its skins as its colour choice"
status: wip
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
