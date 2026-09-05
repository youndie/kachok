---
id: B-46
title: "The theme: colour roles, type, and the desktop calibration"
status: open
priority: P1
size: S/M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-39]
---

# B-46 — The theme: colour roles, type, and the desktop calibration

Everything else in phase 2 rests on this. The design's calibration is three global decisions
(`docs/design/design-tokens.md` §3), and applying them through the theme is what makes a component
nobody listed still come out right.

- **The decision and its reason.** An M3 `darkColorScheme` with the design's eight named roles, a
  `Typography` on the three families, and `Shapes` at 4 dp — plus `warning` / `onWarningContainer`
  as an extension, because M3 has no such role and the design needs one for *checking* and
  *stopping*. Elevation is not themed away; it is simply never used, and hairlines are drawn.
- Rejected: a full custom theme. The inventory is mostly stock M3; a custom theme would make every
  stock component a liability instead of a saving.
- Not covered: light mode. The design is dark-first and ships dark only.

- AC: a viddik golden of a swatch-and-type sheet whose colours equal the hexes in
  `design-tokens.md` §1 read from the golden's own pixels, not from the source; `warning` is
  reachable without touching `MaterialTheme.colorScheme`.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/theme/`.
