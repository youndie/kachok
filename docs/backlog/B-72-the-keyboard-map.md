---
id: B-72
title: "The keyboard map the empty state advertises"
status: open
priority: P1
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-62]
---

# B-72 — The keyboard map

The empty state tells a person to press `⌘O` for a file and `⌘V` for a magnet. Neither key does
anything: nothing in the window handles a key event at all.

- **The decision this needs.** Where the handler sits. A `Window`'s `onKeyEvent` sees every press
  before the focused field does, so a shortcut written there swallows `⌘V` in the settings' port
  field; a handler on the list has focus only while the list has it, and the design's own screen has
  no focused element when it opens.
- Rejected in advance: leaving the hint and not the keys. Text that names a shortcut is a promise;
  it is the same defect as a button drawn enabled.
- Not covered: making the map discoverable — no menu bar, no cheat sheet.

- AC: `⌘O` opens the file chooser and `⌘V` reads a magnet from the clipboard, from the empty state
  and from a filled list; neither fires while a text field has focus. On Windows and Linux the same
  keys with Ctrl.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/EmptyState.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`.
