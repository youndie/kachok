---
id: B-42
title: "Is ScopedValue used anywhere, or dropped?"
status: dropped
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

**Dropped 2026-09-05, answered by [B-07](B-07-virtual-thread-peer-transport.md).** The question
assumed a reader loop that never suspends. It does suspend — on taking a pool buffer, which is the
download path's back-pressure — so a `ScopedValue` binding would not survive it there any more than
it survives one anywhere else. There is no non-suspending loop in the engine to put a scoped value
in, the session context travels as a `CoroutineContext` element, and this item has nothing left to
build. Kept rather than deleted because "use ScopedValue, it is final in 25" is a suggestion that
will be made again.
