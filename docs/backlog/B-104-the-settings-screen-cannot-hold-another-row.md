---
id: B-104
title: "The settings screen is exactly full: an eleventh row pushes the tenth somewhere nobody can reach it"
status: open
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-104 — The settings screen is exactly full: an eleventh row pushes the tenth somewhere nobody can reach it

`SettingsScreen` lays its sections out in a `Column(Modifier.weight(1f).fillMaxWidth())` with no
`verticalScroll` anywhere in the file. At the design's own size — 620×760, which is what
`settings_screen.png` is recorded at — the ten editable rows fill it exactly. There is no slack and
no way to see past the bottom: a row that does not fit is not scrolled to, it is clipped.

This was found by adding an eleventh row for
[B-97](B-97-the-announce-never-says-how-many-peers-it-wants.md) and watching
`everyEditableSettingLeavesTheWindow` fail — **naming the wrong setting**. The new row reported its
change; *Join the DHT*, the last one on the screen, reported nothing, because it was no longer on
the screen. The guard did its job; without it the symptom would have shipped as "the DHT toggle
disappeared in this version" and been attributed to the item that had nothing to do with it.

Two things follow. The screen cannot take another setting until this is fixed, which makes it a
blocker rather than a tidy-up — [B-97](B-97-the-announce-never-says-how-many-peers-it-wants.md)'s
*Ask every tracker* toggle is the first thing waiting on it, and [B-99](B-99-the-dht-is-off-and-its-reason-for-being-off-expired.md)
and [B-103](B-103-upnp-and-nat-pmp-port-mapping.md) both end in a switch if they go the way they are
written. And the same is presumably true at any window smaller than the artboard, where the tenth
row is already gone today — [B-75](B-75-the-window-below-800dp.md) sized the window, not this screen.

- **The decision and its reason.** Make the sections scroll, with the footnote staying put below
  them. Scrolling and not a smaller row or a second column: the rows carry a note each because the
  screen's rule is that a measured default earns an explanation beside it, and the fix must not be
  one that makes the next setting a judgement call about whether it deserves its sentence.
- Rejected: a taller default window. It moves the number at which this happens and does not remove
  it, and the artboard is the size the design chose.
- Rejected: leaving it and adding settings to the CLI only. The window is the product for most of
  the people who have one; a setting only the headless client can reach is half a feature.
- Not covered: what the scrollbar looks like. If the design has no opinion, the platform's own is
  the answer and the golden records whatever it draws.
- Not covered: the same question on any other screen. The details panel scrolls its tabs already;
  nothing else here is a list that grows with the code.

- AC: with eleven editable settings the screen reaches all of them — `everyEditableSettingLeavesTheWindow`
  passes with a row added — and at the artboard size with ten it is pixel-identical to today's
  golden, so the fix costs nothing where nothing was wrong. A window shorter than the artboard
  reaches the last row by scrolling.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`,
  `ui/src/desktopTest/kotlin/io/github/youndie/kachok/ui/main/WiringTest.kt`,
  `ui/src/desktopTest/snapshots/settings_screen.png`.
