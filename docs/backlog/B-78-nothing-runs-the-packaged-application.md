---
id: B-78
title: "Nothing runs the packaged application"
status: done
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
- Anchors: [`ui/build.gradle.kts`](../../ui/build.gradle.kts),
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/Preflight.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/Preflight.kt),
  [`engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/tracker/HttpTrackerClient.kt`](../../engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/tracker/HttpTrackerClient.kt).

## Done

**There is no `java` in the image to run a check with.** `jlink` strips the native commands, so
`runtime/` has no `bin/` at all — the only executable in the distribution is the application's own
launcher. That turned out to be the right entry point rather than a limitation: going in through it
means the runtime, the classpath and the JVM flags under test are the ones a person downloads, not a
copy of them assembled by the check.

`kachok --preflight <report>` runs five checks and exits, before anything opens a window:

| Check | What it would have caught |
|---|---|
| an announce through `HttpTrackerClient` against a loopback socket | the `NoClassDefFoundError` this item was filed for, at the class it was thrown from |
| an ECDH agreement on secp256r1 | research §1.3e's measurement, kept: the module `jdk.crypto.ec` was added on a guess and removed after measuring, and this fails if the curve ever does leave `java.base` |
| a datagram round trip on loopback | the UDP tracker path, which no HTTP check touches |
| `sun.misc.Unsafe` loads | `jdk.unsupported` |
| SHA-1 of the empty string equals its known value | the piece hash, against a vector rather than against itself |

Each one **executes** its path. A check that reads the boot layer for a module name would pass for
every image the build can produce and fail for none of them: `jlink` links what is named, so the
names are present by construction and it is the *unnamed* module the code reaches for that breaks.

`:ui:checkDistributable` runs the launcher and `check` depends on it, so `./gradlew build` fails
instead of the user. Verified by removing `java.net.http` from `modules(...)` and rebuilding:

```
FAIL  java.net.http, through the client that broke — java.lang.NoClassDefFoundError: java/net/http/HttpClient
the packaged runtime cannot do: java.net.http, through the client that broke
> Process 'command '…/kachok.app/Contents/MacOS/kachok'' finished with non-zero exit value 1
```

Green on macOS 27, Ubuntu 24.04 and Windows 11 (build 26200) on 2026-09-06. One expectation was
wrong on the way: the Windows launcher is a GUI subsystem executable and its standard output was
expected to go nowhere, and it reaches the caller like any other. The report file stays because it
is the task's declared output — that is what lets Gradle skip the check when nothing changed.

**Automated:** `:ui:checkDistributable`, which `:ui:check` depends on.
