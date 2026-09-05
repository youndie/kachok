---
id: B-25
title: "SIGINT: stop announces, close peers, flush, write resume, exit"
status: open
priority: P1
size: S
stage: m6-resume
epic: feature-resume
blocked_by: [B-17]
---

# B-25 — SIGINT: stop announces, close peers, flush, write resume, exit

The order matters: the tracker must hear `stopped`, files must be `force()`d, the resume file
must be written last so that it never describes a state the disk does not have.

- **The decision and its reason.** A shutdown hook cancels the session scope and awaits it with
  a bounded timeout; the session's `close` runs the sequence above; the CLI's exit code reflects
  whether the timeout was hit.
- Rejected: writing the resume file first "to be safe". It would be the unsafe order.
- Not covered: shutdown on `SIGTERM` from a service manager — same hook, untested in phase 1.

- AC: `SIGINT` during a download leaves a resume file whose pieces are all hashed and on disk,
  and the fake tracker receives `stopped`.
- Anchors: `cli/src/main/kotlin/ru/workinprogress/kachok/cli/Main.kt`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`.
