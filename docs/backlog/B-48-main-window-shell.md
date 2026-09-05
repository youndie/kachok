---
id: B-48
title: "The main window: toolbar, column header, status bar, degraded banner"
status: open
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-47]
---

# B-48 — The main window: toolbar, column header, status bar, degraded banner

- **The decision and its reason.** `TopAppBar` at 48 dp with no title, a sortable column header,
  a 24 dp status bar carrying the session totals, and the degraded banner — docked and permanent,
  because a `Snackbar` that disappears on a timer is the wrong shape for "a session is degraded and
  needs a person".
- Rejected: hiding the diagnosis behind a hover. The engine's rule is that "no peers, no reason" is
  a state nobody can act on; the UI inherits it.
- Not covered: the keyboard map, and the reflow below 800 dp.

- AC: a viddik golden of the whole window against `docs/design/screens/main-window.png`; the
  status bar shows down/up, torrent counts, DHT nodes, port and heap; the banner carries
  `sessionError` verbatim and does not time out.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/`.
