---
id: B-42
title: "Is ScopedValue used anywhere, or dropped?"
status: question
priority: P3
size: XS
stage: m2-wire
epic: feature-download
blocked_by: [B-07]
---

# B-42 — Is ScopedValue used anywhere, or dropped?

Research D2 and Open question 5: `ScopedValue` bindings do not survive a coroutine's suspension,
so the brief's "ScopedValue for the session context" is restricted to the one non-suspending loop
in the engine — the peer reader — and the hypothesis is that even there local variables suffice.

- **The question.** Answered by [B-07](B-07-virtual-thread-peer-transport.md): if the reader loop
  wants a `ScopedValue`, it is used there with the rule "no `get()` after a suspension point"
  written next to it; if not, this item is dropped and D2 gains the confirmation.
- Not covered: `ScopedValue` in coroutine code, which stays forbidden.

- AC: the research's Open question 5 is closed either way.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/`.
