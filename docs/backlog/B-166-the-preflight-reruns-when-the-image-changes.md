---
id: B-166
title: "The packaged preflight runs again whenever the image it checks changes"
status: open
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
