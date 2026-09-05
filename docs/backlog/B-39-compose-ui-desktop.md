---
id: B-39
title: "Phase 2: a Compose Multiplatform desktop UI on the engine's StateFlow"
status: open
priority: P3
size: XL
stage: phase-2-ui
blocked_by: [B-19]
---

# B-39 — Phase 2: a Compose Multiplatform desktop UI on the engine's StateFlow

A placeholder so that phase 1 is built with phase 2's needs visible: the UI reads one
`StateFlow` and sends commands through one channel, nothing else
([../research/research-architecture.md](../research/research-architecture.md) D7, Risk 4). Compose Multiplatform 1.12.0 is pinned in the shared catalog.

- **The decision and its reason.** Deferred until [B-19](B-19-end-to-end-download-acceptance.md)
  proves the engine on a real swarm; a UI on an engine that has met only fakes is a UI for fakes.
- The shape: a new `:ui` module, the `compose.desktop.application` plugin, `jpackage` from it
  ([B-29](B-29-jlink-runtime-image.md) becomes its input), the same factory as the CLI.
- Not covered: everything; this is a placeholder.

- AC: not applicable until phase 2 starts; the item is reopened with real acceptance criteria
  then.
- Anchors: `settings.gradle.kts` (the `:ui` include arrives here).
