---
id: B-29
title: "A jlinked run-time image, the jars, and a launcher script"
status: done
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
- Anchors: `cli/build.gradle.kts`, `scripts/verify_runtime_image.sh`,
  `cli/src/test/kotlin/io/github/youndie/kachok/cli/SwarmHost.kt`.

**Done.** `./gradlew :cli:runtimeImage` writes `cli/build/kachok`: the `jlink`ed runtime, the jars,
a launcher, and an `image.properties` naming the module set and the flags — read by the verification
script, so it cannot check an image the build would not produce. 35 MB on macOS/aarch64.

The AC's Docker run is `scripts/verify_runtime_image.sh`: it links the same module set for
`linux/amd64` in a container, serves a torrent from this machine, and downloads it in
`debian:stable-slim` — refusing to proceed if that container turns out to have a `java`. Eight
megabytes, 32 pieces, `sha256sum -c` agreed. It is a script and not a CI job because CI has never
run ([B-02](B-02-ci-runs-build-and-docs-gates.md)).

**The module set in the research was wrong in both directions**, and only measuring found it
(§1.3b). `jdeps` names `java.instrument` and `jdk.unsupported`, which §1.3 did not have — both
reached from code paths that do not run at start-up, so the image without them starts, prints its
usage, parses a torrent and reports an unreachable tracker, passing every check short of the one
that matters. `java.management` was in the list and is referenced by nothing: dropped, 688 KB.
`jdk.jfr` is referenced by nothing either and is kept on purpose — with `-XX:StartFlightRecording`
an image without it does not warn and does not degrade, it refuses to start the VM.

Beside the item: `SwarmHost`, a one-torrent swarm servable to another machine. The tests build
their swarm in the client's own process, which a container cannot join. Its tracker answers with
BEP 3's *non-compact* peer list, because compact peers are four bytes of IPv4 and what a container
needs is the name `host.docker.internal`.
