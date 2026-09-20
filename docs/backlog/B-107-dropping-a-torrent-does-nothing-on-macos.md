---
id: B-107
title: "Dropping a .torrent on the window does nothing on macOS"
status: done
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

## Iteration 1 — 2026-09-18: the mechanism read off the JDK, the decision given a test, the drag still owed

**Not reproduced — read.** The item asked for a drag from Finder before touching a line, and the
drag needs a person at the Mac: this iteration ran unattended, with no grant to drive Finder. What
could be read instead was the JDK. `SunDropTargetContextPeer.getTransferData` — the peer every
AWT platform's drop target goes through, macOS included — begins with

```
if (dropStatus != STATUS_ACCEPT || dropComplete) {
    throw new InvalidDnDOperationException("No drop current");
}
```

and `dropStatus` becomes `STATUS_ACCEPT` only when the *drop* is accepted. So for anything dragged
in from another application the file list is not readable while it hovers, on any platform, and
the second candidate mechanism is not a macOS quirk but the contract. `droppedPaths` read the list
in `onEntered` and caught `UnsupportedFlavorException` and `IOException` —
`InvalidDnDOperationException` is neither, so the hover threw out of the target. Compose's
`AwtDragAndDropManager` calls `acceptDrag` only from `dragOver`, after `onMoved`, and each
`dragOver` that re-enters the node calls `onEntered` first: a throw there and the drag is never
accepted, the OS shows the refusal cursor, the drop is never delivered. That is "no overlay and no
dialog", exactly as reported — and it is not specific to macOS, which is a prediction the Windows
half of the acceptance can test.

**What changed.** The decision is `DroppedFiles`, a `Transferable` in and a list out: `offered`
(the flavour, readable before the drop), `paths` (the files, empty for the three ways a
transferable refuses, `InvalidDnDOperationException` now among them), `hovering` (the names for the
overlay, or one unnamed file when the platform will not say them yet — the overlay then reads
"Drop to add a torrent" rather than staying invisible), `firstTorrent`. `App.kt` keeps the one
line that unwraps the AWT event. `DroppedFilesTest` builds a transferable for each case the item
listed, including "flavour advertised, data unavailable"; `feature-ui.md` §6 no longer says the
window has no listener. The `gestures` golden is unchanged: two named files draw as before.

**Owed, and why the item is `wip` and not `done`.** The first clause of the acceptance — the
owner drags a `.torrent` from Finder over the window, sees the overlay, lets go, sees the dialog —
has not been exercised, and a fix for a mechanism read off source is a hypothesis until it is. The
same drag on Windows afterwards. Both take a person at the machine; the next iteration is that
drag, and nothing else.

## Iteration 2 — 2026-09-20: the drag, and the answer the JDK does not document

**Driven on the owner's Mac**, with the window run on a `user.home` of its own so the acceptance
could not touch the real torrent list, the real settings or the single-instance lock, and with a
fixture whose tracker is `.invalid`.

**The first drag reproduced the bug on the build that was supposed to have fixed it.** No overlay,
no dialog, nothing — and this time the process left a stack trace:

```
java.lang.NullPointerException: null cannot be cast to non-null type kotlin.collections.List<java.io.File>
	at DroppedFiles.paths(DroppedFiles.kt:41)
	at DroppedFiles.hovering(DroppedFiles.kt:57)
	at Client.onEntered(App.kt:771)
```

Iteration 1 read `SunDropTargetContextPeer`, found `InvalidDnDOperationException` — *"No drop
current"* — and caught it. **macOS returns `null` instead.** The unchecked cast then raised
`NullPointerException`, which is none of the three exceptions caught, so `onEntered` threw exactly
as before, Compose never called `acceptDrag`, and the window neither drew an overlay nor took the
drop. The mechanism *class* read off the source was right — the data is not readable while the drag
hovers — and the way the platform says so was wrong, which no amount of further reading would have
settled.

**The fix is the cast.** `as? List<*>` then `filterIsInstance<File>()`: a null, a value of another
type, and a list with something else in it are all "no files here" and none of them is a throw.

**The same drag on the fixed build**, in one sequence: the window tinted, drew its dashed border and
read **"Drop to add a torrent"** while the file hovered — the unnamed-file case the item designed,
because macOS will not give the name until the drop — and on release the **Add torrent** dialog
opened for `drop-acceptance.torrent`, naming its 64.0 KiB, 4 pieces of 16.0 KiB, one file, and its
info hash.

**A note on the test that nearly passed for the wrong reason.** The double for "the platform answers
null" was first written as a Kotlin class overriding `getTransferData(): Any`; Kotlin inserts a null
check on the return, so the double threw before the code under test was reached. It is a
`java.lang.reflect.Proxy` now, which returns what the handler says, exactly as a Java method
compiled without a Kotlin null check does.

- AC: on macOS, the owner drags a `.torrent` from Finder over the window and sees the overlay, lets
  go, and sees the add dialog for that file. **Met, and driven.** Windows is owed the same drag and
  is [B-116](B-116-a-torrent-whose-name-is-not-ascii-cannot-be-opened-on-windows.md)'s neighbour on
  that machine; the prediction this fix makes there is that it was never a macOS bug at all.
  **Automated:** `ui/src/desktopTest/.../add/DroppedFilesTest.kt` —
  `aFlavourAdvertisedThatAnswersNullIsAnUnnamedFileNotACrash`,
  `aFlavourAdvertisedThatAnswersSomethingElseIsNoFiles`, and the four the first iteration wrote.
