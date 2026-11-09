---
id: B-184
title: "The toolbar has no re-announce, and under oldge-ui settings is a gear"
status: done
priority: P3
size: S
stage: phase-2-ui
---

# B-184 — The toolbar loses re-announce, and settings is a gear

Seen in the installed window on 2026-11-01 (`kachok-0.1.237`): two of the toolbar's icons did not
say what they did.

- **Re-announce, in both kits.** Material drew `campaign`, a megaphone; oldge-ui drew *Share*,
  chosen in [B-176](B-176-the-oldge-kit-draws-the-toolbar.md) as "sent out". Neither reads as
  *tell the trackers again*, and neither icon set has a shape that does. **The decision:** the
  button goes, rather than a third guess at a glyph. The command stays where it was first: the
  Trackers tab's *Re-announce* sends the same `Announce`, beside the trackers it goes to. The bar
  had it since [B-144](B-144-the-toolbar-says-what-its-buttons-do.md), for a person who had not
  opened that tab; a button nobody can read is not much use to that person either.
- **Settings, under oldge-ui.** *Levels* was the nearer shape to Material's `tune` sliders and read
  as an equaliser. It is *Gear* now. The Material kit keeps `tune`.

- AC: the bar offers no re-announce in any selection, and every command it has is reachable.
  **Automated:** `ToolbarStateTest#everyEnabledControlCarriesACommand`,
  `#everyCommandIsReachableFromSomeSelection` (the enum lost `Reannounce`, so a handler for it
  would not compile).
- AC: the Trackers tab still re-announces, under both kits. **Automated:**
  `TrackersTabTest`, `ControlRolesTest` (its *Re-announce* is a button).
- AC: every toolbar glyph still has an oldge-ui icon. **Automated:**
  `OldgeToolbarTest#everyToolbarGlyphHasAnOldgeIcon`.
- AC: the goldens with a toolbar in them are recorded again on the mac (`:ui:viddikRecord`): the
  bar is one control shorter, and the oldge windows draw the gear. **Not done** — the goldens are a
  mac's rasteriser, and this change was made on Windows.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/main/Toolbar.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/oldge/OldgeKit.kt`.

## Done — 2026-11-09: the goldens recorded on the mac

`LOCAL=1 ./gradlew :ui:viddikVerify` on the mac was red on exactly the five goldens of the main
window under oldge-ui — `main_window-oldge`, `main_window-crystal`, `main_window-media`,
`main_empty-oldge`, `main_narrow-oldge` — each about 1 370 pixels apart (0.10–0.38 %, tolerance
0.05 %). The `_DIFF`s were looked at: the red is the Re-announce slot the bar no longer has and the
gear where *Levels* was, and nothing else. `:ui:viddikRecord` rewrote those five and left the other
28 byte for byte; `main_narrow-oldge` was looked at after recording (gear, no Re-announce), and
`viddikVerify` is green again, 33 of 33. The Material goldens did not move, which agrees with the
item: the Material kit keeps `tune`. `make check` on the mac, which runs `viddikVerify`, is green
with them.
