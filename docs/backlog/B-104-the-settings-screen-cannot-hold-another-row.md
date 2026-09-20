---
id: B-104
title: "The settings screen is exactly full: an eleventh row pushes the tenth somewhere nobody can reach it"
status: done
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
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`,
  `ui/src/desktopTest/kotlin/io/github/youndie/kachok/ui/main/WiringTest.kt`,
  `ui/src/desktopTest/snapshots/settings_screen.png`.

**Done 2026-09-17.** The sections scroll; the footnote below them does not. Landed in two commits on
purpose, because the two halves of the acceptance criterion prove different things and would have
proved neither together.

The first is the scroll alone, with ten rows, and `viddikVerify` **passed without the golden being
re-recorded** — the fix costs nothing where nothing was wrong. The second adds the eleventh row,
[B-97](B-97-the-announce-never-says-how-many-peers-it-wants.md)'s *Ask every tracker*, and the
golden moved by 9.49 % of its pixels and was re-recorded and looked at.

**The guard needed changing too, and the distinction matters.** With the screen scrolling,
`everyEditableSettingLeavesTheWindow` still failed on *Join the DHT*: `performClick` on a row below
the fold reaches nothing. The rows are now scrolled to before they are touched, which is what a
person does. That is not the check being loosened to get green — its claim is unchanged, every
editable row still has to report its change, and only the way the test reaches the row moved.
Clicking blind was the part that was wrong, and it is what made this guard accuse the wrong setting
in the first place.

The same lesson arrived twice more the same day, when `maxPeers` was measured
([B-98](B-98-how-many-peers-does-this-client-meet.md)): `SettingsScreenTest` found its field by the
text `50` and `MainWindowTest` asserted `default 50`, so both failed on a screen drawing exactly
what it should. They were a second home for a number that lives in `SessionConfig`; both now read
the engine's own default, and neither can go stale again.

**What the new golden shows, and why it is right.** The DHT row at the bottom is cut off
mid-sentence. That is the honest un-scrolled state of a screen with more content than height, and
the cut is the affordance that says so. A golden is a photograph of what the screen does, not an
assertion that it looks finished.
