---
id: B-164
title: "Text drawn in the accent stays readable on a light skin: an accent-as-text role in the kit"
status: open
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
