---
id: B-180
title: "The Material kit's toolbar buttons are named but are not buttons to the platform's accessibility"
status: open
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
