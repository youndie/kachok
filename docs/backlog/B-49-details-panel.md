---
id: B-49
title: "The details panel and its four tabs"
status: done
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-48]
---

# B-49 — The details panel and its four tabs

- **The decision and its reason.** A right-hand panel at the design's 340 dp with a 32 dp tab row.
  *Overview* is built from `SessionState` and every line of it is real; *Files*, *Peers* and
  *Trackers* say which engine change they are waiting for, because the engine does not carry those
  lists yet.
- **Recessed, not raised.** There is no elevation anywhere in this design, so a secondary panel is
  a darker ground with a hairline rather than a surface with a shadow.
- **The panel is built from a `SessionState`, and so is its golden.** The fixture behind
  `main_window.png` is a session carrying the design's numbers, put through the same `detailsOf`
  the running window uses. A fixture of finished strings would have proved the panel draws strings.
- Rejected: inventing the three lists in the UI. A screen that shows numbers nothing produced is
  worse than one that says it is waiting for them.
- Not covered: the engine changes those three tabs need; each becomes its own item. Also not
  covered: dragging the panel's edge — the 280–520 dp range is declared in `Details` and nothing
  moves it yet.

## Deviations, and why

- **The three planned tabs are not drawn full of rows.** `docs/design/screens/details-tabs.png`
  shows nine files, ten peers and three trackers; a mockup can. Each tab instead names the engine
  change it needs — no per-file progress, no peer identities, one `trackerError` for the whole
  session — which is the item's own rejected-alternative applied to its own acceptance criteria.
  The golden `details_planned-tabs.png` is what that looks like.
- **`Ratio` is 0.14 where the design writes 0.11.** The mockup's own numbers do not divide: 412 MiB
  over 2.89 GiB is 0.14. Derived rather than copied, so the panel says what the session is. Same
  reason `Left` is a field on `SessionState` and not `total − downloaded` — BEP 3 is explicit that
  it is not, after a resume.
- **`Save to` reads `~/Downloads/iso` and the reference reads `Downloads/iso/~`.** The design sets
  `direction: rtl` on that cell to ellipsize a path from the left, and the browser moved the tilde
  to the end. The path is right here and wrong there.
- ~~**The window golden lost its title bar.**~~ *It has one now.* The bar the reference draws is in
  the design's own colours, which is a custom frame rather than OS chrome; connecting AppFrame
  ([B-61](B-61-appframe-title-bar.md)) made the golden the whole 1200 × 760. Every horizontal
  boundary — bar, toolbar, banner, header, each of the sixteen rows, the status bar — lands at the
  reference's, offset by the two pixels of window border the reference PNG includes and a window's
  inside does not.

- AC: goldens against `docs/design/screens/details-tabs.png`; every field in Overview reads from
  `SessionState` and the planned ones are visibly marked.
  **Automated:** `ui/src/desktopTest/.../session/DetailsFromTest.kt`, and the goldens
  `main_window.png` and `details_planned-tabs.png` verified by `:ui:viddikVerify` in `make check`.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/DetailsFrom.kt`.
