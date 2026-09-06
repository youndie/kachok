---
id: B-78
title: "Nothing runs the packaged application"
status: open
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-78 — Nothing runs the packaged application

The Windows build died on its first announce with
`NoClassDefFoundError: java/net/http/HttpClient`. The cause was one missing line — `jlink` cuts the
runtime down to the modules `nativeDistributions` names, and `java.net.http` was not among them —
but the reason it reached somebody's machine is that **the artifact is never executed by anything**.

`:ui:run` uses the full JDK. Every test uses the full JDK. `createDistributable` produces a trimmed
runtime that is the only place the defect can exist, and the pipeline's last step is to zip it.

- **The decision this needs.** What "it starts" can be asserted without a display. The window needs
  one, so the check cannot be the window; what the failure was actually about is the *runtime image*
  — whether the modules the code reaches for are in it. `<image>/runtime/bin/java --list-modules` is
  one answer and a weak one: it proves a module is present, not that the application can use it.
  A stronger one is a headless entry point in the packaged image that opens an `HttpClient`, a TLS
  socket and a `DatagramChannel`, and exits.
- Rejected in advance: trusting `:ui:suggestRuntimeModules`. It is `jdeps`, so it sees static
  references and nothing else — it did not name `jdk.crypto.ec`, which every `https://` tracker
  needs, because JCA providers are loaded by name.
- Rejected in advance: `includeAllModules = true`. It makes the defect impossible and the
  distribution 40 MB larger, which is most of what the trimmed runtime is for.
- Not covered: that the *window* opens. That needs a display, and the goldens are what stand in for
  it.

- AC: a change that makes the application reach for a module the runtime does not carry fails the
  build rather than the user; the check runs against the image `createDistributable` produced, on
  the platform it was produced for.
- Anchors: `ui/build.gradle.kts`,
  `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/tracker/HttpTrackerClient.kt`.
