---
id: B-180
title: "The Material kit's toolbar buttons are named but are not buttons to the platform's accessibility"
status: done
priority: P2
size: S
stage: phase-2-ui
---

# B-180 — The Material toolbar is invisible to accessibility

Seen in the live app on 2026-10-31, read through macOS accessibility. The two kits expose the
toolbar very differently:

- **oldge kit:** every toolbar control is an `AXButton` with its name — *Add torrent*, *Pause*,
  *Settings*, and the rest.
- **Material kit:** the same window exposes none of them. The only actionable element in the summary
  is the filter's text field.

The Material controls are drawn boxes with `clickable` and a `contentDescription`
(`theme/material/MaterialComponents.kt`). The name is enough for Compose's test tree, which is why
every test finds them, but without `role = Role.Button` the platform has no reason to call them
buttons. oldge-ui's buttons set the role themselves.

- **The decision and its reason.** Every drawn control in the Material kit gets the role it is:
  - `Role.Button` for the toolbar's, the banner's, the dialogs' and the empty state's buttons;
  - `Role.Tab` for the details tabs;
  - `Role.Switch` for the settings switch.

  Then nothing a screen reader reaches in one kit is missing from the other.
- AC: a test over each kit's toolbar asserts the role on every control; the live app read through
  the platform's accessibility lists the toolbar's buttons under the Material kit.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/material/MaterialComponents.kt`.

## Done — 2026-10-31

**What exists.** Every drawn control a screen reader should reach now says what it is, in both kits.
In the Material kit (`theme/material/MaterialComponents.kt`):

- `Role.Button` on the toolbar's icon buttons, *Add torrent*, the filter's clear button, the
  banner's *Show it*, the dialogs' buttons, both *Browse…* and the empty state's *Add a torrent*;
- `Role.Tab` on the details tabs, through `selectable`, so the current one is also `selected`; the
  strip is a `selectableGroup`;
- `Role.Switch` on the settings switch, through `toggleable` as `OldgeSwitch` has it, so the platform
  gets on/off as a state and not only as the words in `stateDescription`.

The screens draw some controls themselves in both kits, and those had no role either:

- `Role.Button` on the details panel's *Re-announce*, *piece order*, a file's priority glyph, *Copy*
  and *Move*; on the clipboard prompt's *Add it* and *Dismiss*; on the filtered list's *Clear the
  filter*; and on the column heads, which sort when pressed;
- `Role.Checkbox`, through `toggleable` with its state, on the add dialog's file rows and
  *Sequential download* and on the remove dialog's *Delete the downloaded data*.

Every `contentDescription` and `stateDescription` is unchanged. The indication is the same
`LocalIndication` that `clickable` used, so nothing is drawn differently.

**Left without a role, on purpose.** Found by grepping `clickable`, `selectable`, `toggleable` and
`combinedClickable` in `ui/src`:

- *A torrent row* (`list/TorrentRow.kt`): a whole-row click that selects a row of the table. It is
  an item, not a control; Compose has no list-item or row role, and calling it a button would tell
  a screen reader that pressing it does something.
- *A file row in the Files tab* (`details/DetailsPanel.kt`, `FileLine`): a whole-row double-click
  that opens the file. Its single click is empty on purpose, so a button role would announce a
  press that does nothing. The controls inside the row, the priority glyph, have their role.
- *The settings segments and swatches* already had `Role.RadioButton` and needed nothing.

**oldge-ui.** No component was missing a role. The same test passes under the oldge kit without
changing it: `OldgeButton`, `OldgeIconButton`, `OldgeOrbButton` (the search bar's *Очистить*),
`OldgeTabs` (`Role.Tab`, selected), `OldgeSwitch` and `OldgeSegmented`.

**Goldens.** `viddikRecord` on the mac re-recorded thirty goldens. None changed: `git status` was
clean after it.

**Verified.**

- `:ui:check` on the Linux box: 363 tests in 57 classes, no failures.
- `ControlRolesTest` has nine tests. Each runs once per kit (`MaterialKit.Default` and
  `OldgeKit.Default`), and asserts with `SemanticsMatcher.expectValue(SemanticsProperties.Role, …)`:
  - every toolbar control at both widths, the filter's clear button included;
  - *Show it*;
  - the details tabs, with the current one selected;
  - the panel's buttons;
  - the settings switch and its state, *Browse* and the segments;
  - both empty states;
  - the column heads;
  - both dialogs' buttons and tick boxes, with their state.
- Mutation, on the committed tree: with `Role.Button` dropped from the toolbar's icon button (the
  same line in the dialog button went with it) and the tabs back to a plain `clickable`, four of
  the nine tests failed. All of them failed under the Material kit: nine `Role = 'Button'` and
  three `Role = 'Tab'` assertions.
- Live, in the release bundle on the mac with an isolated home, read through macOS accessibility
  under the Material kit:
  - the toolbar is nine `AXButton`s, *Add torrent* through *Settings*, beside the filter's
    `AXTextField`;
  - the empty state's *Add a torrent* is an `AXButton`;
  - on the settings screen, *Browse* is an `AXButton` and the switches are `AXCheckBox`, macOS's
    role for a toggle.

  The details tabs were not read live, because the empty window has no torrent to open them on.
