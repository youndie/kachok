---
id: B-192
title: "A window taking over from a yielding holder can come up with no single-instance lock"
status: done
priority: P2
size: S
stage: phase-2-ui
---

# B-192 — A window taking a yielded lock renames over an open file

`SingleInstanceTest.aHolderThatYieldsGivesTheWindowTheLock` failed once on the Windows CI job on
2026-11-22 (run 45044544439, PR #159, which did not touch `SingleInstance`) with *the window did
not get the lock*, and passed on the rerun. The test took 64 ms, so the window never got near its
30-second wait for the holder. Its stderr in the uploaded `windows-reports` names the failure:
`kachok: no single-instance lock: …\instance.new -> …\instance`. That is `bind` giving up on
the first `AccessDeniedException` from `Files.move` in `writeLock`.

- **The cause: on Windows a file that any other handle has open cannot be renamed over.**
  Measured on Windows 11 with JDK 25.0.1 (a `jshell` probe, 2026-11-22): with a plain
  `FileChannel.open(READ)` on the target — which shares deletion, as every Java reader does —
  `Files.move(…, REPLACE_EXISTING, ATOMIC_MOVE)` throws `AccessDeniedException: src -> target`,
  the exact message from CI. A *delete* under the same open handle succeeds and takes the name
  away at once, and a connect to a closed loopback port is refused in 0–2 ms. So the window can
  get from "the port does not answer" to its rename well inside the holder's own `close()`.
- **The race was in `close()`.** It closed the server socket first, then read the file back to
  check the secret, then deleted it. A window polling in `claimForWindow` sees the port refuse and
  goes straight to `bind`, and its rename lands while the holder still has `instance` open in
  `readLock`. The rename fails and the window comes up without a lock (so the next double-click
  starts a second client, [B-60](B-60-two-torrents-one-path.md) between processes). If the
  holder's delete lands *after* the window's rename instead, the window holds the lock and its
  file is gone, which ends the same way. Production hits this path whenever a person opens the
  window while a headless `kachok mcp` holds the lock ([B-136](B-136-one-engine-that-remembers.md)).
- **Reproduced before the fix:** the yield scenario looped 2000 times in one JVM with 48 threads
  burning all 20 cores failed in 35 runs and, on a second pass, 1, every failure with the CI's
  stderr line. The same loop on an idle machine failed 0 of 2000, which is why the job is green
  almost always. The new `aReaderHoldingTheLockFileDoesNotLockTheNextClientOut` failed on the
  unchanged code on the first run.
- **The decision, in two parts.** `close()` deletes the file *before* it closes the port, so
  everything the holder does to the file is over by the time anyone can see it gone. `writeLock`
  retries a rename refused with `AccessDeniedException` every 10 ms for up to a second, because
  the holder is not the only reader: another launch in `handOverTo`, an agent in `attach`, or a
  scanner opening a file that was just written blocks the rename in the same way. That second is
  an assumption about how long a reader holds the file, not a measurement of any scanner; a
  refusal that outlasts it ends as before, in a client with no lock.
- Rejected: a longer `YIELD_WAIT_MILLIS`, or a pause in `claimForWindow` once the port stops
  answering. The wait never ran out, and a pause is a guess at how long somebody else's handle is
  open. Also rejected: making the test wait. Production takes the same path.
- Not covered: a path handed to a client between `opened.close()` and the end of `close()` is
  still lost, as it was before this change. The window is now as long as one file read and delete
  instead of one socket close.

- AC: a headless holder that yields gives the window the lock, and a later launch is handed to the
  window rather than becoming a second client. **Automated:**
  `SingleInstanceTest.aHolderThatYieldsGivesTheWindowTheLock`, which now also asserts the later
  launch. After the fix, the loaded 2000-run loop failed 0 times.
- AC: a reader holding the lock file while a client takes it over does not stop that client
  getting the lock. **Automated:** `SingleInstanceTest.aReaderHoldingTheLockFileDoesNotLockTheNextClientOut`
  (it bites only on Windows; on Linux the rename succeeds at once).
- Anchors: `control/src/main/kotlin/io/github/youndie/kachok/control/SingleInstance.kt`,
  `control/src/test/kotlin/io/github/youndie/kachok/control/SingleInstanceTest.kt`.
