---
id: B-107
title: "Dropping a .torrent on the window does nothing on macOS"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
---

# B-107 — Dropping a .torrent on the window does nothing on macOS

The window has a drop target: `App.kt` wraps `MainWindow` in `Modifier.dragAndDropTarget`, reads
the AWT transferable's `javaFileListFlavor` in `droppedPaths`, draws an overlay from `dropping`
while a file hovers, and hands the first `.torrent` to `pendingDrop` on release, which opens the
add dialog. On the owner's Mac none of that happens: a `.torrent` dragged from Finder onto the
window produces no overlay and no dialog.

**Nothing in the repository could have said otherwise, and it says so.** `WiringTest` covers the
overlay and the dialog *from state* — "turning an AWT drop into that state is the half no test
drives: `DragAndDropEvent` wraps a type a test cannot construct, and dragging a file between two
applications needs a second application." So the listener was written, wired, and never once fed a
real drop on any platform. And `feature-ui.md` §6 still reads "the window has no listener for
either yet", which was true when B-50 wrote it and is not true now — a sentence that outlived its
reason, and the reason nobody went looking.

- **The decision and its reason: reproduce on the Mac before touching a line.** The symptom has
  at least three candidate mechanisms and a fix for the wrong one passes exactly the same
  non-test. In order of how cheap each is to rule out:
  - *`onEntered` never fires.* The target is not registered for the AWT window — `dragAndDropTarget`
    may need to sit on the composition root under the `ComposeWindow` rather than on `MainWindow`'s
    content, or `shouldStartDragAndDrop = { true }` is not the predicate macOS consults.
  - *It fires, and `droppedPaths` returns empty during the hover.* macOS exposes a drag's data
    **at drop time**; during the hover the flavour list is available and `getTransferData` may not
    be. `onEntered` calls `droppedPaths`, so the overlay stays empty — and `onDrop`, which calls it
    again, could still succeed. If the dialog *does* open and only the overlay is missing, this is
    it, and the owner's "nothing happens" is about the overlay.
  - *`onDrop` gets the files and refuses them.* The `.torrent` filter is on the path's string; a
    Finder drop can arrive as a file promise or through a flavour other than `javaFileListFlavor`,
    in which case the list is empty and `onDrop` returns `false` — which tells macOS the drop was
    refused, and it animates the file back to where it came from. Indistinguishable, to a person,
    from no listener at all.
- **Then split the decision from the event, so the decision has a test.** `droppedPaths` and the
  "first `.torrent`" rule take a `DragAndDropEvent`, which is why they are untestable. Take a
  `Transferable` instead, and the hover-time and drop-time cases — data present, data absent, wrong
  flavour, no `.torrent` among the files — are a hand-built transferable each. The AWT event stays
  in `App.kt` as the one line that unwraps it.
- Rejected: swapping Compose's `dragAndDropTarget` for a raw AWT `DropTarget` on the
  `ComposeWindow`. It may turn out to be the fix; it is not a fix to reach for before the mechanism
  is known, because it also discards the overlay's hover states.
- Rejected: reading the file on `onEntered` to show its name in the overlay. If the second
  mechanism is real, the overlay has to make do with "a file" until the drop — and it should, rather
  than staying invisible.
- Not covered: dropping a magnet link or text, which the clipboard prompt already handles for the
  link case; dropping onto the tray icon; dropping several torrents at once, which `onDrop`
  deliberately narrows to the first because the dialog asks about one.
- Not covered: Linux, until somebody reports it. The AWT drop path there is the same as Windows'.

- AC: on macOS, the owner drags a `.torrent` from Finder over the window and sees the overlay, lets
  go, and sees the add dialog for that file — and the same on Windows afterwards, because the fix
  must not trade one platform for the other. `droppedPaths`' decision has a unit test with a
  hand-built transferable for each case above, including "flavour advertised, data unavailable",
  and it is mutation-checked. `feature-ui.md` §6 stops saying there is no listener.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`,
  `ui/src/desktopTest/kotlin/io/github/youndie/kachok/ui/main/WiringTest.kt`,
  `docs/features/feature-ui.md`.
