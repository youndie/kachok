---
id: B-131
title: "The window has no minimum size, and the interface scale moved where it breaks"
status: open
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

- AC: the window cannot be resized to a size where the toolbar clips; the narrow golden is recorded
  at the smallest size the window allows, so that the fixture and the floor are the same number.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`,
  `ui/src/desktopTest/kotlin/io/github/youndie/kachok/ui/main/MainWindowSheet.kt`.
