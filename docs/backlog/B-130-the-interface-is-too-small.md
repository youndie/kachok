---
id: B-130
title: "The whole interface wants to be 10–20 % larger"
status: done
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-130 — The interface is drawn too small

Reported by the owner about the running window: **everything should be 10–20 % larger.** The type
scale is 10 to 19 sp ([Type.kt](../../ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/Type.kt)
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

## Taken at 1.20, chosen by looking

The main window was rendered at 1.00, 1.10, 1.15 and 1.20 in the same 1200 × 760 frame — the window
does not grow, its contents do, which is how a person will meet this — and the owner picked **1.20**,
the top of the range they asked for.

## What reading the goldens found, which is why the item insisted on it

**Four of the twelve came out broken, and none of the breakage was in the product.** At 1.20 the
settings screen's *Save to* label wrapped one character to a line with the default text overlapping
it, and the narrow window clipped its toolbar. Both looked like the scale breaking the layout.

They are artboards. A `@ViddikScreenshot` names a size in **pixels**, so drawing everything 1.2
times larger leaves the same fixture with 1/1.2 of the *design units* it had — the settings sheet
went from 620 to 516 dp of width, which is narrower than that screen has ever been asked to be. The
app is not narrower: the settings screen is drawn *instead of the list*, in the window's own width,
and the window at 1200 × 760 renders cleanly at 1.20 with every column, the banner and all four
groups of the status bar.

So the twelve fixtures were scaled by the same 1.2, and every golden then showed the composition it
showed before, larger. One test had to be told: `theSheetIsTheSizeItsFixtureAsksFor` asserts the
theme sheet's golden is exactly its fixture's size, and it is the reason the change could not be
made quietly.

**What the narrow fixture was telling the truth about is now its own item.** A person can still
resize the real window down to where the toolbar clips; the scale moved that point 20 % out and
there is no minimum size to stop them — [B-131](B-131-the-window-has-no-minimum-size.md).

- AC: the owner opens the window, agrees the size, and the goldens are re-recorded and read; the
  scale is one value in one place, so that the setting this becomes later has somewhere to write to.
  **All met** — the size was agreed from renders rather than described, and reading the goldens is
  what turned "the scale broke the settings screen" into "the artboards are in pixels".
- `INTERFACE_SCALE` in `theme/Theme.kt` is that one value; an *Interface scale* setting would write
  to it and is still its own item.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/Theme.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/Type.kt`,
  `ui/src/desktopTest/snapshots/`.
