---
id: B-73
title: "Dropping a file on the window, and a magnet on the clipboard"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-72]
---

# B-73 — Dropping a file, and a magnet on the clipboard

Two components exist and nothing raises them. `DropOverlay` draws the design's dashed target and is
composed from a state flag no event sets; the clipboard prompt — *a magnet link is on your
clipboard* — is drawn the same way. Both are reachable only by constructing the state by hand,
which is what their goldens do.

- **The decision this needs.** For the drop: AWT's `DropTarget` on the window, which is outside the
  composition and gives coordinates in window pixels, versus Compose's own drag-and-drop, which
  knows the composition and in 1.12 receives an `awt` transferable anyway. For the paste prompt:
  when to look. Reading the clipboard on every focus gain is what the design describes and is also a
  process reading the clipboard whenever you alt-tab to it.
- Rejected in advance: polling the clipboard. It is what makes an application show up in the
  system's clipboard-access indicator once a second.
- Not covered: dropping a magnet as *text*, and dropping several files at once.

- AC: dragging a `.torrent` over the window shows the overlay and dropping it opens the add dialog
  with that file; returning to the window with a magnet on the clipboard shows the prompt, and it is
  not shown twice for the same magnet.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/DropOverlay.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`.
