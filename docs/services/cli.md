---
id: cli
title: cli (headless JVM application)
type: service
module: cli
tech_stack: [Kotlin 2.4 JVM, JDK 25, Gradle application plugin]
owner: unassigned
depends_on:
  - engine
publishes:
  - "a jlink run-time image + jars + launcher + AOT cache (phase 1 distribution; not built yet)"
---

# cli

## 1. Responsibility

The phase-1 surface of kachok: a headless command-line client. It parses arguments, builds the
JVM implementations of the engine's I/O interfaces, starts a session, renders the session's
`StateFlow` as progress output, and shuts the session down cleanly on `SIGINT`. It is also where
the JVM flags the research settled on are pinned, so that `./gradlew :cli:run` runs the same VM
a distribution will.

It deliberately does **not** contain protocol logic, piece selection or storage code — all of that
is [engine](engine.md). A second surface (the phase-2 Compose UI) must be able to replace this
module without touching the engine.

## 2. API contracts

No network API. The contract is the command line, documented in the feature document
`feature-cli` once it exists (it is `draft` on the phase-1 branch; `main` has the skeleton below).
Today:

```
kachok <anything>   → prints "kachok: no commands yet (skeleton)" to stderr, exit code 2
```

## 2a. Code anchors

| File | What is there |
|---|---|
| `cli/build.gradle.kts` | `application` main class, `applicationDefaultJvmArgs` — the flags of research D6 |
| `cli/src/main/kotlin/ru/workinprogress/kachok/cli/Main.kt` | the entry point; usage exit code `2` |
| `cli/src/test/kotlin/ru/workinprogress/kachok/cli/MainTest.kt` | proves the test task runs for this module |

## 3. How it is built

`main` will be short on purpose: parse, construct, run, render. The construction step is the one
place in phase 1 that names concrete JVM classes — `BufferPool`, the virtual-thread transport,
`FileChannel` storage, the `MessageDigest` hasher, the `java.net.http` tracker client — and hands
them to the engine as interfaces. There is no DI container; a hand-written factory is the entire
wiring and is the thing phase 2 duplicates for the UI.

Progress rendering reads the engine's `StateFlow` and prints on a timer; it never observes
per-message events, which is what conflation is for.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [engine](engine.md) | everything |
| Library | `kotlinx-coroutines-core` | `runBlocking` at the top, the render timer |
| JDK | `java.base`, `java.net.http`, `jdk.jfr`, `java.management` | the run-time image module set of research §1.3 |

## 5. Infrastructure and deploy

* **Artefact (target, research D10):** a directory with a `jlink`ed run-time image (measured at
  32 MB for the module set above), the application jars, a launcher script fixing the JVM flags,
  and an AOT cache produced by a training run through that launcher.
  [B-28](../backlog/B-28-aot-cache-in-the-distribution.md),
  [B-29](../backlog/B-29-jlink-runtime-image.md).
* **Today:** `./gradlew :cli:installDist` produces the ordinary Gradle application layout with the
  full JDK expected on the machine. No image, no cache, no installer.
* **CI:** `.github/workflows/ci.yml` builds and tests on `ubuntu-latest`; the documentation gate is
  `.github/workflows/check.yaml`.

## 6. Local setup

```bash
./gradlew :cli:run --args="download example.torrent"
```

Runs the skeleton with the JVM flags from `cli/build.gradle.kts`
(`-XX:+UseCompactObjectHeaders -Xmx256m`). JDK 25 is resolved by the toolchain (foojay resolver in
`settings.gradle.kts`), so a machine without it downloads one.

## 7. Configuration

None yet. The plan: everything the engine's `SessionConfig` exposes is a command-line flag with the
same name, there is no configuration file in phase 1, and nothing is read from the environment.
The listening port defaults to BEP 3's 6881–6889 probe.

## 8. Quirks

* **The skeleton's `main` always exits with code 2**, so `./gradlew :cli:run` reports a failed
  task. That is the usage exit code doing its job before there is a usage; it changes with
  [B-18](../backlog/B-18-cli-download-command.md).
* **The JVM flags live in the build file, not in a script.** `applicationDefaultJvmArgs` is read by
  `run` and by `installDist`'s start scripts alike, so there is one place to change a flag and no
  way to measure a VM the distribution would not ship. The AOT cache flag is *not* there yet: the
  cache does not exist, and pointing `-XX:AOTCache` at a missing file is a warning on every start.
* **Windows is not tested** (research Risk 6). Nothing here is Unix-specific by intent, and
  nothing here has been run on Windows.
