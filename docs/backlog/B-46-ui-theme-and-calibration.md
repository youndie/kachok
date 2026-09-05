---
id: B-46
title: "The theme: colour roles, type, and the desktop calibration"
status: done
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
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/theme/`,
  `ui/src/desktopTest/kotlin/ru/workinprogress/kachok/ui/theme/ThemeColorsTest.kt`.

**Done.** The eight roles, the three families and the 4 dp shapes, with `warning` as an extension
reached through `MaterialTheme.warningColors` — an extension rather than a borrowed `tertiary`,
because `tertiary` means something else here and a borrowed role is one nobody can change on its
own.

**The criterion is checked against the golden's pixels, not against the source.** Asserting
`KachokDarkColors.primary == Color(0xFF4FD9C2)` would compare a constant with a copy of itself and
pass through every way a colour can fail to reach the screen — a role wired to the wrong slot, a
theme not applied, a swatch drawn from something else. `ThemeColorsTest` opens the recorded PNG and
counts pixels. Checked that it can fail: one hex changed by one bit turns it red.

The three fonts are **bundled**, not asked of the system. A golden is a comparison of pixels, and
pixels drawn in whatever face the machine had are a comparison of that machine. All three are
variable fonts, so one file covers every weight.

**Two findings about where viddik may run, and neither is optional.**

`viddikRecord` on the WSL replica **passes and records nothing that survives**. The test runs — the
report says one test, no failures — and the PNG it writes is on the replica, which `wsl-run` erases
on its next `mutagen sync flush` before the following command. A golden is source, source lives on
the mac, so the viddik tasks run there with `LOCAL=1`, like `ktlintFormat` and for the same reason.

That is also why goldens are recorded and verified on **one** platform. Two renderers do not agree
pixel for pixel; a golden recorded on one and verified on the other measures the difference between
rasterisers rather than a change to the UI.
