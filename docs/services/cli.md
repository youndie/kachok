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

No network API. The contract is the command line and the exit codes:

```
kachok download <file.torrent | magnet:?xt=urn:btih:…> [--dir <path>] [--port <n>]
                [--peers <n>] [--pipeline <n>] [--seed] [--up <KiB/s>] [--down <KiB/s>] [--dht]
```

| Exit | Meaning |
|---|---|
| `0` | the download completed and every piece verified |
| `1` | it failed; the reason is the last line on stderr |
| `2` | the command line does not parse; usage follows on stderr |

## 2a. Code anchors

| File | What is there |
|---|---|
| `cli/build.gradle.kts` | `application` main class, the JVM flags of research D6 and §1.2d, the module set of §1.3b, and the `runtimeImage`, `collectorBench` and `swarmHost` tasks |
| `scripts/verify_runtime_image.sh` | the image downloading a torrent in a container with no JDK |
| `cli/src/test/kotlin/ru/workinprogress/kachok/cli/SwarmHost.kt` | the swarm that script points the container at |
| `cli/src/main/kotlin/ru/workinprogress/kachok/cli/Main.kt` | the entry point and the exit codes; takes its streams so a test can read them |
| `.../cli/Arguments.kt` | the hand-written parser and the usage text |
| `.../cli/Download.kt` | what makes this a command: the rendering, the exit codes, the shutdown hook. The wiring itself is the engine's `runtime/TorrentRuntime.kt`, which the desktop window builds too |
| `cli/src/test/kotlin/ru/workinprogress/kachok/cli/ShutdownTest.kt` | a real subprocess, a real `SIGINT`, and the record it leaves behind |
| `cli/src/test/kotlin/ru/workinprogress/kachok/cli/DownloadTest.kt` | the end-to-end download against a local tracker and a real seeding peer |
| `swarm/src/main/kotlin/ru/workinprogress/kachok/swarm/` | that tracker and that peer, in the module both surfaces test against |
| `cli/src/test/kotlin/ru/workinprogress/kachok/cli/CollectorBench.kt` | the collector comparison of research §1.2d, and the only way to redo it |

## 3. How it is built

`main` is short on purpose: parse, construct, run, render. The construction step is the one
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
| JDK | `java.base`, `java.instrument`, `java.net.http`, `jdk.unsupported`, `jdk.jfr` | the run-time image module set, measured in research §1.3b |

## 5. Infrastructure and deploy

* **Artefact (research D10):** `./gradlew :cli:runtimeImage` writes `cli/build/kachok` — the
  `jlink`ed run-time image, the jars, a launcher fixing the JVM flags, and an `image.properties`
  naming the module set and those flags. 35 MB on macOS/aarch64, 47 MB for `linux/amd64`.
  `./gradlew :cli:aotCache` adds `kachok.aot`, trained on a real download and proved to be opened
  — 27.9 MB, which takes the distribution to 63 MB and start-up from 103 ms to 51 ms
  (research §1.2a2). `./scripts/verify_runtime_image.sh` does all of it in a `debian:stable-slim`
  container with no `java` in it (research §1.3b).
* **Also:** `./gradlew :cli:installDist` produces the ordinary Gradle application layout, which
  expects a JDK on the machine. No installer in phase 1, and none needed for a headless CLI.
* **CI:** `.github/workflows/ci.yml` builds and tests on `ubuntu-latest`; the documentation gate is
  `.github/workflows/check.yaml`.

## 6. Local setup

```bash
./gradlew :cli:run --args="download example.torrent"
```

Runs with the JVM flags from `cli/build.gradle.kts`
(`-XX:+UseG1GC -XX:+UseCompactObjectHeaders -Xmx128m`), each of them measured in research §1.2d.
JDK 25 is resolved by the toolchain (foojay resolver in `settings.gradle.kts`), so a machine
without it downloads one.

```bash
./gradlew :cli:collectorBench
```

Re-runs that measurement: the same 1 GB local-swarm download under G1 and ZGC, with and without
compact headers, at three heap sizes. It takes a few minutes and it measures the machine as much as
the code, which is why nothing in `build` calls it.

## 7. Configuration

Flags only. There is no configuration file in phase 1 and nothing is read from the environment.
`--peers` and `--pipeline` are the engine's `SessionConfig` fields under the same names; `--up`
and `--down` are its rate limits, given in kibibytes a second here and stored as bytes a second
there, with no limit — not a limit of zero — as the default; `--port`
defaults to the first of BEP 3's 6881–6889 (the probe itself arrives with
[B-09](../backlog/B-09-incoming-connections.md), which is what will need the range).

## 8. Quirks

* **A cache the VM refuses is not an error.** It is a warning line and a completely normal, slower
  start, so `:cli:aotCache` fails the build unless `-Xlog:aot=info` says `Opened AOT cache`.
  "It ran" proves nothing here.
* **`KACHOK_JVM_OPTS` is how anything reaches the VM through the launcher rather than around it.**
  The training run and the smoke run both use it; measuring or training a VM the launcher would not
  run is the thing this whole layout exists to prevent.
* **The class path in the launcher is an explicit sorted list, not `lib/*`.** An AOT cache is
  refused unless the class path matches the one it was trained on, and a wildcard's expansion order
  is the JVM's business rather than a promise (JEP 483).
* **The JVM flags and the module set live in `cli/build.gradle.kts` and nowhere else.** `run`,
  `installDist`'s start scripts, the run-time image's launcher and the verification script all
  read them from there — the script through the generated `image.properties`, so it cannot drift
  into checking an image the build would not produce.
* **A magnet link is fetched before anything is opened.** It names a torrent and carries none of
  it, so `Download` runs a `MetadataFetcher` first and only then knows what files to create. A
  magnet with no trackers has nowhere to look unless `--dht` is on.
* **A second `SIGINT` is not special-cased.** Doing it properly needs internal API; the ten-second
  bound on the clean stop already guarantees the process ends.

* **A signal is a request to stop, not a reason to lose progress.** The handler asks the session
  to stop the way the command does — tracker, peers, flush, record — and waits for it, bounded.
* **A download gives up only when there is nothing to wait for**: every tracker refused *and* no
  peer arrived from anywhere else. Deliberately not "no progress for a while" — a slow swarm is not
  a failed download, and a client that gives up on one is worse than a client that waits.
* **`-XX:+UseG1GC` is redundant today and is there anyway.** G1 is this JDK's default; the flag
  exists because an AOT cache built under one collector is refused under another without an error
  anyone sees (research §1.2), so the collector this cache is built with may not be inherited.
* **`-XX:AOTCache` is asked for, not passed unconditionally.** The launcher adds it only when the
  file is beside it, because pointing it at a missing file is a warning on every start — and
  `:cli:runtimeImage` alone, without `:cli:aotCache`, is a working distribution.
* **Windows is not tested** (research Risk 6). Nothing here is Unix-specific by intent, and
  nothing here has been run on Windows.
