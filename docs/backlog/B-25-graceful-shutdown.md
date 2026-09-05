---
id: B-25
title: "SIGINT: stop announces, close peers, flush, write resume, exit"
status: done
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

- AC **met 2026-09-05** (`ShutdownTest`, a real subprocess and a real signal): `SIGINT` during a download leaves a resume file whose pieces are all hashed and on disk,
  and the fake tracker receives `stopped`.
- Anchors: `cli/src/main/kotlin/ru/workinprogress/kachok/cli/Main.kt`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`.

**Closed 2026-09-05.** The sequence itself came with the session; this item is the signal handler
and the proof that it survives being triggered the way a user triggers it.

* **The test spawns the client in its own JVM and sends it `SIGINT`.** A shutdown sequence is worth
  nothing if it does not survive its actual trigger, and nothing in-process can send a real signal
  to itself and mean it. It then checks that every piece the record claims is on the disk and hashes
  correctly — the record's whole promise, verified rather than assumed.
* **The wait is bounded.** A peer that will not close or a tracker that will not answer must not be
  able to hold the process open; after ten seconds it leaves anyway and says so, with exit code 1.
* **`removeShutdownHook` refusing is the expected answer**, not a failure: once the hook is what
  asked the session to stop, there is nothing to remove.

**Not implemented:** a second `SIGINT` exiting immediately. Doing it properly needs `sun.misc.Signal`,
which is internal API, and the bounded wait already guarantees the process ends. Said here rather
than left as an implied feature.
