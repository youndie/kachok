---
id: B-50
title: "Add torrent: the dialog, the drop target, the clipboard magnet"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-48]
---

# B-50 — Add torrent: the dialog, the drop target, the clipboard magnet

- **The decision and its reason.** One gesture then one dialog: drop a `.torrent` on the window,
  or paste a magnet, and the same dialog opens showing what was recognised. A magnet has no name
  and no size, and the dialog says so rather than filling the gap.
- Rejected: a wizard. There is one decision — where to save — and one option worth showing.
- Not covered: file selection and sequential download, both `planned` in the design and both
  needing engine support.

- AC: goldens against `docs/design/screens/add-torrent.png`, including the drop overlay and the
  clipboard prompt; the magnet case shows the hash where the name would be.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/add/`.
