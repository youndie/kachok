---
id: B-29
title: "A jlinked run-time image, the jars, and a launcher script"
status: open
priority: P2
size: S/M
stage: m7-measure
epic: feature-cli
blocked_by: [B-18]
---

# B-29 — A jlinked run-time image, the jars, and a launcher script

Research §1.3 measured the image at 32 MB for `java.base, java.net.http, jdk.jfr, java.management`.
This item makes the Gradle build produce it — [../research/research-architecture.md](../research/research-architecture.md) D10.

- **The decision and its reason.** A Gradle task calling the toolchain's `jlink` with that module
  set and `--strip-debug --no-man-pages --no-header-files --compress zip-6`, the `installDist`
  jars beside it, and a launcher script that passes the flags of D6 and the cache of
  [B-28](B-28-aot-cache-in-the-distribution.md). No `jpackage`: the application is not modular
  and a headless CLI needs no installer.
- Rejected: the `org.beryx.jlink` plugin. It wants a modular application; making Kotlin's
  automatic modules into named ones is work with no user-visible result.
- Not covered: signing, notarisation, installers — phase 2 with Compose's `jpackage`.

- AC: `./gradlew :cli:runtimeImage` (name to be decided in the item) produces a directory whose
  `bin/kachok` downloads the fixture torrent on a machine with **no** JDK installed (a Docker
  `debian:slim` run in CI); the image size is recorded in the research.
- Anchors: `cli/build.gradle.kts`.
