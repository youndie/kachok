---
id: B-72
title: "The keyboard map the empty state advertises"
status: done
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

## The decision, taken

**`onKeyEvent` on the window, never `onPreviewKeyEvent`.** Preview runs top-down and would take
`⌘V` out of the filter field and out of every number in the settings before they saw it. `onKeyEvent`
runs after the focused component has had its turn, so a text field that handles the press keeps it
and an unfocused window gets it here — which is the item's "neither fires while a text field has
focus", obtained from the event order rather than from a flag somebody has to remember to set.

`AppFrame` takes an `onKeyEvent`, so the handler sits where the window is and not on a modifier that
only fires when its subtree has focus.

## Deviations, and why

- **The decision is a function of three values, not of a `KeyEvent`.** In Compose Multiplatform 1.12
  a `KeyEvent` wraps an `InternalKeyEvent` a test cannot construct: building one from
  `java.awt.event.KeyEvent` compiles and throws `ClassCastException` on the first accessor —
  checked. `shortcutFor(type, key, modified)` leaves the whole decision testable and the window's
  handler one call around it.
- **The request is an object, not the enum.** The effect that acts on it is keyed on the value, and
  two presses of the same key have to be two different values or the second does nothing.
- **What the live check could and could not confirm.** `⌘O` was driven against the running window
  with a real hardware keycode and opened the file chooser. It could not be driven any other way:
  both the automation tool and AppleScript's `keystroke` send letters as keycode 0 with the
  character in a Unicode payload, which the JVM reports as `Key: A` whatever letter was asked for —
  so every synthetic `⌘O` arrived as `⌘A`. `⌘V` runs through the same handler and the same branch;
  the case where a focused field must keep the press is argued from the event order above and is not
  covered by a test.

- AC: `⌘O` opens the file chooser and `⌘V` reads a magnet from the clipboard, from the empty state
  and from a filled list; neither fires while a text field has focus. On Windows and Linux the same
  keys with Ctrl.
  **Automated:** `ui/src/desktopTest/.../ShortcutTest.kt` — both keys, both modifiers, a bare
  letter, a key release, and two presses being two requests.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/main/EmptyState.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`.
