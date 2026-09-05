---
id: B-64
title: "A click waited for the tick"
status: done
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-62]
---

# B-64 — A click waited for the tick

Reported as "clicks are handled with some delay" and "settings take a very long time to open". Both
are one defect: **the whole window state was rebuilt inside the sampling loop**, which runs once a
second. Opening the settings screen set a flag, and the flag was read the next time the engine was
sampled — so every interaction was up to a second late, and a screen that appears a second after
the click reads as a screen that failed to open.

- **The decision and its reason.** What the engine says is sampled on a timer; what the person
  decides is read in composition. `EngineSnapshot` holds one second's worth of session state and
  nothing else — no open panel, no sort order, no selection, no typed setting — and the window is
  composed from it plus the interface state, so a click is a recomposition rather than a wait.
- Rejected: sampling faster. It would have hidden the delay behind a shorter one and cost a
  redraw of sixteen rows sixty times a second to make a toggle feel immediate.
- **`windowOf` was already pure**, which is why this was a move rather than a rewrite: the same
  function, called from composition instead of from a coroutine.

- AC: a click on the settings toggle, a column head, a row or a details tab is reflected in the
  next frame rather than on the next tick.
  **Automated:** indirectly — `WiringTest` composes `MainWindow` and asserts every callback
  arrives, which only passes if the state a click sets is read where the window is built. Driven by
  hand as well: settings now open within a frame of the press.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`.
