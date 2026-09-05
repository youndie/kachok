---
id: B-41
title: "Phase 3: Android and iOS targets on the engine"
status: open
priority: P3
size: XL
stage: phase-3-mobile
blocked_by: [B-39]
---

# B-41 — Phase 3: Android and iOS targets on the engine

Placeholder. The engine's common code is written so that each platform adds four implementations
— transport, storage, hasher, tracker client — behind the interfaces of research D7, and the
platform primitives underneath as `actual`s.

- **The decision and its reason.** Targets are declared when their implementations exist, never
  before ([../research/research-architecture.md](../research/research-architecture.md) D7). Android's transport can be the same JDK NIO code on API 26+; iOS needs
  `NWConnection` or POSIX sockets through cinterop — a research task of its own.
- Not covered: everything; this is a placeholder.

- AC: not applicable until phase 3.
- Anchors: `engine/build.gradle.kts`.
