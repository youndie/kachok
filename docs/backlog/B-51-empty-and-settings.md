---
id: B-51
title: "The empty state and the settings screen"
status: open
priority: P2
size: S/M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-48]
---

# B-51 — The empty state and the settings screen

- **The decision and its reason.** Settings shows **the measured default beside every field**,
  which is the design's own rule and this project's: the defaults in `SessionConfig` were measured,
  so a blank field would throw that away. The DHT toggle is off and carries the paragraph
  explaining why.
- Rejected: a preferences window per platform. One screen, in the window.
- Not covered: applying a change to a running session, which the design marks `planned`.

- AC: goldens against `docs/design/screens/empty-state.png` and `settings.png`; every field's
  default label equals the value in `SessionConfig`, read from it rather than typed.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/settings/`.
