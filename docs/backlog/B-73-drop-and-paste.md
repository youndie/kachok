---
id: B-73
title: "Dropping a file on the window, and a magnet on the clipboard"
status: done
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

## The decision, taken

**Compose's own `dragAndDropTarget`, not AWT's `DropTarget`.** It knows the composition, and on
desktop in 1.12 it hands over the `awtTransferable` anyway — so the AWT route buys window-pixel
coordinates nobody needs and a second lifetime to get wrong. The target sits on `Client`'s
`MainWindow` rather than inside it: reading a file is the shell's business and `MainWindow` stays a
view.

**The clipboard is read when the window comes back into focus, and only then.** Polling is what puts
an application in the system's clipboard-access indicator once a second. Offered once per link: the
magnet last offered is remembered, so returning to the window ten times is one prompt, and
dismissing is a person saying no to *that* link.

**One `.torrent` per drop**, the first. The dialog asks about one torrent, and four would need a
queue the window has not got.

## What was found and fixed on the way

- **`HeadlessException` is not `IllegalStateException`.** Reading the clipboard threw straight out of
  the focus effect on a machine with no display, which the window's own end-to-end test is. Both
  clipboard paths catch it now.
- **The prompt covered the status bar.** It is anchored to the bottom of the window, and so is the
  status bar; without the bar's height in the padding it sat on the rates and the port.
- **The drop reads on an effect, not in the callback**, which runs while a composition is already in
  flight.

## Not verified by driving it

Turning an AWT drop into window state is the one half no test reaches: `DragAndDropEvent` wraps a
type a test cannot construct — the same obstacle `KeyEvent` presented in
[B-72](B-72-the-keyboard-map.md) — and dragging a file between two applications needs a second
application, which this session was refused access to. What *is* covered is the state and the
presses, and the file-reading path is `torrentAt`, shared with the chooser and `⌘O` and exercised by
both.

- AC: dragging a `.torrent` over the window shows the overlay and dropping it opens the add dialog
  with that file; returning to the window with a magnet on the clipboard shows the prompt, and it is
  not shown twice for the same magnet.
  **Automated:** `WiringTest.theClipboardPromptsAnswersLeaveTheWindow` and
  `aDragOverTheWindowDrawsTheOverlayNamingWhatIsBeingDropped`. Checked by hand: a magnet put on the
  clipboard, the window focused, the prompt offered it and *Add it* opened the dialog on that
  link.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/DropOverlay.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`.
