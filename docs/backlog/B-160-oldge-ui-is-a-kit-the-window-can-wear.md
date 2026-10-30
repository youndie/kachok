---
id: B-160
title: "oldge-ui is a second kit, and the settings choose between the two"
status: open
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
