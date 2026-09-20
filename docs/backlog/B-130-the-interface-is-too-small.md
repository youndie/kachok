---
id: B-130
title: "The whole interface wants to be 10–20 % larger"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-130 — The interface is drawn too small

Reported by the owner about the running window: **everything should be 10–20 % larger.** The type
scale is 10 to 19 sp ([Type.kt](../../ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/theme/Type.kt)
— column heads at 10 sp, body at 12.5 sp, the headline at 19 sp), and there are **230 hard-coded
`N.dp` literals** in `ui/src/desktopMain`, so this is not a number that lives anywhere.

- **The decision and its reason.** Scale it at the **one seam that scales everything**: give
  `KachokTheme` a `LocalDensity` of `Density(density * scale, fontScale)`. Compose resolves every
  `dp` and every `sp` through that density, so one value moves the type, the paddings, the row
  heights, the icons and the corner radii together — and nothing has to be found. The alternative
  is editing 230 literals and a type scale, which is the same change made 240 times, each one an
  opportunity to round differently.
- The alternative that was rejected for *now*: an "Interface scale" setting on the Settings screen.
  It is the better end state and it is a larger item — a stored preference, a control, a live
  re-layout — and it does not answer "it is too small today". A fixed bump now does, and it is the
  same seam the setting would use.
- **The number is not decided and the item must decide it by looking.** 10 % and 20 % are both in
  the request, and the difference between them is a row of the torrent list. Render the main window
  at 1.10, 1.15 and 1.20, put the three side by side, and pick with the owner rather than for them.
- **What this breaks on purpose, and it is the expensive half.** Every viddik golden is a photograph
  at a fixed size: all twelve change, and they have to be re-recorded on the mac and *looked at*
  rather than accepted. A golden that is re-recorded without being read is a golden that has stopped
  guarding anything.
- The other seam that does not see the density: the tray menu
  ([TrayMenuScale.kt](../../ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/TrayMenuScale.kt)
  — *"It is not drawn by Compose, it does not see the window's density"*). It will stay its own
  size, and whether that matters is a question for the run, not for this paragraph.
- Not covered: the window's default and minimum size. A window whose contents grew 15 % and whose
  minimum did not is a window that can be resized into a broken layout — check it in the run and
  make it a line here if it shows.

- AC: the owner opens the window, agrees the size, and the goldens are re-recorded and read; the
  scale is one value in one place, so that the setting this becomes later has somewhere to write to.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/theme/Theme.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/theme/Type.kt`,
  `ui/src/desktopTest/snapshots/`.
