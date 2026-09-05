---
id: B-50
title: "Add torrent: the dialog, the drop target, the clipboard magnet"
status: done
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-48]
---

# B-50 — Add torrent: the dialog, the drop target, the clipboard magnet

- **The decision and its reason.** One gesture then one dialog: open a `.torrent`, or paste a
  magnet, and the same dialog opens showing what was recognised. A magnet has no name and no size,
  and the dialog says so rather than filling the gap.
- **What the dialog shows comes out of the parsed source.** `addFrom` takes a `Metainfo` or a
  `MagnetLink`, so the screen cannot claim a detail the file did not carry — which is what makes
  the magnet case a different screen rather than the same one with blanks in it.
- **Reading the clipboard is not consent to download what is in it.** The magnet is shown and
  waits; nothing is dialled until somebody says so.
- Rejected: a wizard. There is one decision — where to save — and one option worth showing.
- Not covered: file selection and sequential download, both `planned` in the design and both
  needing engine support. Also not covered: the drop and focus *events* — `DropOverlay` and
  `ClipboardMagnetPrompt` are drawn from `MainWindowState` and the window has no drag-and-drop
  listener yet; the toolbar's two buttons are what opens the dialog today.

## Deviations, and why

- **`Add` is greyed, and the dialog says why.** The engine is one `Session` per torrent and nothing
  above it holds several — [B-52](B-52-ui-on-the-real-engine.md)'s first finding — so a window
  already running one has nowhere to put a second. There are two goldens because there are two real
  screens: `add_dialog.png` is the design's, with the button live, and `add_refused.png` is what a
  person meets today. A live button that silently did nothing would be worse than either.
  [B-54](B-54-many-torrents.md) is what makes the first one true.
- **A file size is three significant figures here as everywhere else.** The design's file list
  writes `1.2 KiB` and `61 KiB` where its torrent list writes `3.70 GiB` and `48.2 MiB`; one rule
  gives `1.20 KiB` and `61.0 KiB`. Two rules would put `61 KiB` in one column and `61.0 KiB` in
  another for the same number.
- **The info hash is five groups of eight.** The design breaks its own at 20, 16 and 4, which is a
  mockup's line-wrap rather than a rule.
- **The drop overlay's border is dashed**, as the design draws it — Compose has no dashed border,
  so it is a `drawBehind` stroke with a path effect rather than a `Modifier.border`.

- AC: goldens against `docs/design/screens/add-torrent.png`, including the drop overlay and the
  clipboard prompt; the magnet case shows the hash where the name would be.
  **Automated:** `ui/src/desktopTest/.../session/AddFromTest.kt` — including a magnet with no `dn`,
  whose only name *is* its hash — and the goldens `add_dialog.png`, `add_magnet.png`,
  `add_refused.png` and `add_gestures.png`. `MainWindowTest` asserts the three screens are reachable
  from the window rather than only from a golden.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/add/`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/AddFrom.kt`.
