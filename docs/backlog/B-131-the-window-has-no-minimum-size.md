---
id: B-131
title: "The window has no minimum size, and the interface scale moved where it breaks"
status: done
priority: P3
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-131 — The window has no minimum size

`rememberWindowState(size = DpSize(1200.dp, 760.dp))` gives the window a *default* and nothing else:
there is no minimum, so a person can drag it down to whatever the operating system allows. Below a
certain width the toolbar's rightmost controls are clipped and the status bar's last group is cut
off — which was true before [B-130](B-130-the-interface-is-too-small.md) and is true after it, at a
width 20 % larger.

Found while looking at B-130's goldens rather than accepting them, which is what that item asked
for: at 600 × 420 the narrow fixture used to show the whole toolbar and now clips it. **That is the
fixture telling the truth about a window that size** — the same artboard in design units now needs
720 × 504 pixels, and the goldens were rescaled to match, but a person resizing the real window to
600 px gets the clipped layout the old fixture no longer shows.

- **The decision this needs.** What the smallest useful window is, in design units, and then to say
  so — `window.minimumSize`, which AWT enforces and the person therefore cannot cross. The narrow
  artboard is the obvious candidate at 600 × 420, because it is the size the design itself draws the
  narrow layout at; whether that is still comfortable is a question for whoever looks at it.
- The alternative that was rejected: making the toolbar wrap or scroll below some width. It is more
  work and it answers a question nobody asked — a window nobody wants to use at 400 px does not need
  a layout for it, it needs a floor.
- Not covered: what the *default* size should be now that everything is drawn larger. 1200 × 760 is
  still a comfortable window on this owner's display and the contents now fill more of it, which is
  what was wanted.

## The decision, taken here rather than asked for

**The design's own narrow layout is the floor: 600 x 420 design units.** It is the smallest
arrangement this window has ever been claimed to work in — the narrow artboard is drawn at exactly
that — and a floor taken from anywhere else would be a number somebody invented. In the units AWT
measures a window in, that is the artboard **times the interface scale**: 720 x 504.

Set on the window itself, because AWT is the only thing that will enforce it: a person dragging the
frame is not passing through any composable.

## What writing the test found: this repository was shipping 1.15, not 1.20

`theFloorIsTheNarrowArtboardAtTheInterfaceScale` failed on its first run — *expected 690 but was
720* — because `INTERFACE_SCALE` on `main` was **1.15**, while
[B-130](B-130-the-interface-is-too-small.md), its commit and its pull request all say the owner
chose 1.20.

The mistake is mine and it is worth naming exactly: B-130 rendered candidates by rewriting the
constant in a loop, and the last pass of that loop left the file at 1.15. The goldens were then
re-recorded against it, so everything was *self-consistent* and wrong — which is precisely the shape
of error a golden cannot catch, because it is a photograph of whatever it was shown.

Fixed here: the constant is 1.20, the goldens are re-recorded, and the two numbers can no longer
drift apart without a test saying so.

- AC: the window cannot be resized to a size where the toolbar clips; the narrow golden is recorded
  at the smallest size the window allows, so that the fixture and the floor are the same number.
  **Both met**, and the second is now asserted rather than arranged: `MinimumWindowTest` reads the
  golden's own pixels and compares them with the floor.
  **Automated:** `ui/src/desktopTest/.../MinimumWindowTest.kt` —
  `theFloorIsTheNarrowArtboardAtTheInterfaceScale`,
  `theNarrowGoldenIsRecordedAtTheSmallestWindowAllowed`.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`,
  `ui/src/desktopTest/kotlin/io/github/youndie/kachok/ui/main/MainWindowSheet.kt`.
