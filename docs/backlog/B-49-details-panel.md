---
id: B-49
title: "The details panel and its four tabs"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-48]
---

# B-49 — The details panel and its four tabs

- **The decision and its reason.** A resizable right-hand `Surface`, 280–520 dp, with
  `SecondaryTabRow` at 32 dp. *Overview* is built from `SessionState` and is real; *Files*, *Peers*
  and *Trackers* are drawn from the design and marked `planned`, because the engine does not carry
  those lists yet.
- Rejected: inventing the three lists in the UI. A screen that shows numbers nothing produced is
  worse than one that says it is waiting for them.
- Not covered: the engine changes those three tabs need; each becomes its own item.

- AC: goldens against `docs/design/screens/details-tabs.png`; every field in Overview reads from
  `SessionState` and the planned ones are visibly marked.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/`.
