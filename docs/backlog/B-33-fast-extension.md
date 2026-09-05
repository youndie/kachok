---
id: B-33
title: "Fast extension (BEP 6): reject, have all/none, allowed fast"
status: open
priority: P2
size: M
stage: m8-extensions
epic: feature-download
blocked_by: [B-06]
---

# B-33 — Fast extension (BEP 6): reject, have all/none, allowed fast

`reserved[7] |= 0x04`. Its value for this engine is `reject`: a request that will not be served is
answered instead of silently dropped, which makes the picker's bookkeeping exact instead of
timeout-based.

- **The decision and its reason.** Implement the full set (ids `0x0D`–`0x11`) but use `reject`
  and `have all`/`have none` first; `suggest` and `allowed fast` are honoured when received and not
  sent in phase 1.
- Rejected: skipping it. Every major client has it, and without `reject` a choke means guessing
  which requests died.
- Not covered: nothing else.

- AC: with the bit set on both sides, a choke produces one `reject` per outstanding request; a
  seed's first message is `have all` instead of a full bitfield.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/wire/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/peer/`.
