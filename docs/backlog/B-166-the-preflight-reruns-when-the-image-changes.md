---
id: B-166
title: "The packaged preflight runs again whenever the image it checks changes"
status: done
priority: P2
size: S
stage: phase-2-ui
---

# B-166 — The preflight reruns when the image changes

Found while building B-161: `:ui:checkDistributable` declares only the launcher file as its input,
so after ProGuard rebuilt the release image with different jars the task stayed up to date and the
preflight did not run — the check that exists because a trimmed image once shipped broken (B-78)
can report green for an image it never ran. It was forced with `--rerun` to see the image pass.

- **The decision and its reason.** Declare the whole app image directory the launcher runs
  (`binaries/main-release/app`) as the task's input, so any change to the jars, the runtime or the
  configuration file makes it run again.
- Rejected: `outputs.upToDateWhen { false }`. It reruns on every build, including the ones where
  nothing in the image changed.

- AC: changing one class in `:engine`, then `:ui:checkDistributable`, runs the preflight again
  (Gradle reports it executed, not up to date); a second run with no change is up to date.
- Anchors: `ui/build.gradle.kts`.

## Done — 2026-10-30

`:ui:checkDistributable` declares the release app image directory
(`build/compose/binaries/main-release/app`) as its input instead of the launcher alone — the same
input `DistributionDownloadTest` already declared.

**Verified on the Linux box, against a change that survives ProGuard.** A first probe added an
unused class to `:engine` and the task stayed up to date — correctly: ProGuard strips an unused
class, so the shipped image was byte-identical. The probe that counts changes a reachable string
(`MagnetLink`'s "not a magnet link" message):

| Declaration | Reachable string changed | Reverted | No change |
|---|---|---|---|
| launcher only (before) | `proguardReleaseJars` ran, `checkDistributable` **UP-TO-DATE** | — | — |
| image directory (now) | `checkDistributable` **ran** | ran, 6 of 6 checks ok | UP-TO-DATE |

The first row is the defect reproduced, so the second is a fix and not a coincidence.

