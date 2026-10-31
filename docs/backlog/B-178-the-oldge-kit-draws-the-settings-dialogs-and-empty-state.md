---
id: B-178
title: "The oldge kit draws the settings fields, the dialogs and the empty state's button with its own components"
status: done
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

## Done — 2026-10-31

**What exists.** `KachokKit` has six more members: `SettingField`, `FolderField` and `Segmented` for
the settings screen, `InvitationButton` for the empty state, and `DialogFrame` and `SaveToField` for
the dialogs. The screens keep the layout, the semantics the tests find them by and the commands. The
Material kit's six are the screens' old drawing, moved into `theme/material/MaterialComponents.kt`.
The dialogs' body takes its side margin from the frame, 20 dp under Material as before. The oldge
kit draws them with the library's own components:

- **A number field** is `OldgeTextField`: the sunken well in the 16 sp `body`, 44 dp high. The
  component always draws its label above the well, and the row already shows it on its left, so the
  field is laid out with only the well inside its bounds (`wellOnly`) — set from outside. Its width
  grows for the words of an absent value: at the Material field's width `no limit` was cut.
- **A changed value** is ringed in `accent-ink` over the well's own edge. The component's border
  knows only `edge` and `danger`, and the ring is the Material field's "you changed this".
- **The directory** is `OldgeTextField` with *Folder*, 180 dp. It is made unfocusable from outside:
  the component has no read-only state, and a well that dropped what somebody typed would be the
  screen's own defect. *Browse…* is `OldgeButton`, *Secondary*, at the well's 44 dp.
- **The segmented choice** is `OldgeSegmented`, set to 36 dp from outside and as wide as its widest
  label twice over. Its segments share the width they are given, and a settings row gives all of it.
- ***Add a torrent*** is `OldgeButton`, *Primary*, 44 dp, with *Plus*.
- **Both dialogs' frame** is `OldgeDialog`, not modal: the skin's bezel with the title in its bar,
  in place over the window's own scrim. Its 358 dp is the design system's phone width, so the width
  is set from outside, first in the chain: 520 and 420 dp as before. The window pads its body 16 dp,
  so the screens' margin is 4.
- ***Save to*** is `OldgeTextField` under its own label, which here is the dialog's, with *Folder*.
  *Browse…* stands level with the well. The note is drawn under them in the help line's style.

**Goldens.** The Material goldens are byte-identical after `viddikRecord` on the mac. Five oldge
goldens changed and one is new.

- **`settings_screen-oldge.png` and `-crystal.png`:** *Interface* is the chrome capsule with
  *oldge-ui* lit. *Save to* is a black well (a white one in Crystal) ringed in the accent, because
  `~/Downloads` is not the default, with a chrome *Browse…*. `250`, `16` and `no limit` sit in 44 dp
  wells and nothing is cut. At 210 dp the path well pushed *Save to* onto two lines; at 180 the row
  is as wide as the Material one.
- **`add_dialog-oldge.png`:** the bezel window with *Add torrent* in its bar and the glowing body.
  *Save to* is the field's bold label over the well, *Browse…* is level with it, and the note is
  under them. The whole dialog fits the sheet.
- **`remove_dialog-oldge.png` and `-crystal.png`:** both answers in the bezel window. The name card,
  the checkbox and the buttons sit at the design's 20 dp from the body's edge.
- **`main_empty-oldge.png` (new):** the empty window under oldge, *Add a torrent* a lime button
  under the invitation.

**Verified.** `:ui:check` on the Linux box: 349 tests in 55 classes, no failures, read off
`ui/build/test-results/desktopTest/*.xml`. `OldgeFormsTest` is seven of them:

- a number typed into the field reaches the setting;
- the field's own label is not on screen — two nodes carry the words, one is displayed;
- the segmented choice reports the other segment and not the chosen one;
- *Browse…* reports the press, and a press on the directory's well does not focus it;
- *Add a torrent* reports the press;
- the add dialog is 520 dp wide in the library's window, its *Save to* field is shown, and
  *Browse…* and *Cancel* report;
- the remove dialog is 420 dp wide, shows its card, and its checkbox reports.

Ten mutations of the oldge wiring were each run against the class, on the committed tree, and each
made it fail:

- the field's typing not passed on;
- the label not lifted, which put the field's label on screen;
- the segmented choice reporting the chosen index;
- the segmented choice reporting a press on the chosen segment;
- *Browse…*'s press not wired, in the settings and in the add dialog;
- the directory's well focusable;
- *Add a torrent*'s press not wired;
- the dialog's width left to the library, which made both dialogs 358 dp;
- the dialog's body not drawn.

`make check` passes on the mac.

**What is left out.**

- The dialogs' checkboxes and radio buttons — *Sequential download*, the file list's ticks, *Start
  immediately*, *Add paused*, *Delete the downloaded data* — are still the screens' glyphs. They are
  not fields; `OldgeCheckbox` exists and would be the next item.
- *Clear the filter* on the no-match state, and the clipboard prompt's *Add it*: not in this item.
- The *Colours* swatches: oldge-ui has no colour picker.

**Gaps in the library.**

- `OldgeTextField` always draws its label, and has no read-only state, no end alignment, no size
  other than `body`, no middle ellipsis for a path and no way to mark a changed value. The lifted
  label stays in the semantics tree, so a screen reader meets a settings field's name twice.
- `OldgeSegmented` has no disabled state, and throws outside two to four options. Under this kit a
  count outside that range is drawn by the screen's old drawing.
- `OldgeDialog` is at most 358 dp wide from inside, and its close orb is named «Закрыть» with no
  label parameter. The dialogs have *Cancel* and do not show the orb.
