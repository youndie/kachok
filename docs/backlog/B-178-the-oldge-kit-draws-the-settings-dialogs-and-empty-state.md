---
id: B-178
title: "The oldge kit draws the settings fields, the dialogs and the empty state's button with its own components"
status: open
priority: P3
size: M
stage: phase-2-ui
blocked_by: [B-177]
---

# B-178 — The oldge kit draws the settings, the dialogs and the empty state

After B-176 and B-177 the main window's chrome is oldge-ui's own, but several controls under the oldge
kit are still drawn by the screens in the Material design's shapes, only in the skin's colours. The
live run of 2026-10-31 and the goldens `settings_screen-oldge.png` and `add_dialog-oldge.png` show
them:

- the settings screen's number and directory fields (outlined boxes);
- the *Interface* row's segmented choice and the *Colours* swatches;
- the *Browse…* button;
- the empty state's *Add a torrent* (a flat accent rectangle);
- the add and remove dialogs' frame and fields. Their buttons have been the kit's since B-160.

- **The decision and its reason.** The same as B-176's. Each becomes a kit member, the Material kit
  draws what the screen draws now, moved and not rewritten, and the oldge kit uses the library:
  - `OldgeTextField` for the fields;
  - `OldgeSegmented` for the choice;
  - `OldgeButton` for *Browse…* and *Add a torrent*;
  - `OldgeDialog` for the dialogs' frame, if its sizing allows a desktop dialog.

  The swatches stay the screen's drawing: oldge-ui has no colour picker.
- Not covered: the table, the column header and the status bar. oldge-ui has no table, and the
  window's dense rows are its own design (B-160).

- AC: under the oldge kit each control above is an oldge-ui component; the Material goldens are
  byte-identical; the oldge goldens of the settings screen and both dialogs show them.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/main/EmptyState.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/add/AddTorrent.kt`.
