---
id: B-155
title: "The desktop distribution ships its classes through ProGuard, and the preflight checks that image"
status: done
priority: P2
size: M
stage: phase-2-ui
---

# B-155 — The desktop distribution ships shrunk classes

The installed Linux app image is 145 MB, and 30 MB of it is the 59 jars in `lib/app`, every class of
every dependency whether the window reaches it or not. Measured on 2026-10-30 at `ca74b97`, on the
Linux box (x64, the runtime from Ubuntu's `openjdk-25` 25.0.4.1): `lib/runtime` 86 MB (`modules`
55 MB, `libjvm.so` 28 MB), `libskiko-linux-x64.so` 29 MB, jars 30 MB; the `.deb` is 57 MB.

The Compose plugin already carries the shrinker: `createReleaseDistributable` runs ProGuard 7.8.0
with Compose's own rules, and the build does not use it. Run once by hand that day, it **failed** on
`WideArgumentsKt` (B-116): ProGuard does not know that `MethodHandle.invokeExact` is
signature-polymorphic, so the FFM downcalls returning `MemorySegment` read as "can't find referenced
method". With one extra rule, `-dontwarn java.lang.invoke.MethodHandle`, it finished in a minute
with no warnings, and the jars other than Skiko's went from 30 MB to about 13 MB (68.7 → ~27 MB
inflated). **Nothing ran the result**, so this is a size and not yet a working build.

- **The decision and its reason.** Ship the release variant: `buildTypes.release.proguard` with a
  rules file in `ui/`, the `-dontwarn` above its first line, obfuscation left off as Compose
  defaults it so a stack trace from a user still names the code. This is the shrinker the plugin
  integrates, which means its task graph, its inputs and its update path are Compose's to keep.
- **The preflight moves with it, or the item is worse than not doing it.** `checkDistributable`
  runs `binaries/main/app`, and `distributions.yml` packages with
  `packageDistributionForCurrentOS`. Switching only the packaging would check one image and ship
  another — B-78 again, with a class ProGuard removed in place of a module jlink left out. So
  `checkDistributable`, the launcher path, `patchDesktopEntry` (which hooks `packageDeb` and reads
  `binaries/main/deb`) and the workflow all move to the `Release` tasks in the same change.
- Rejected: R8 in class-file mode. It shrinks Kotlin somewhat better, but the Compose plugin does
  not integrate it, and in zavarnik's optimizer phase R8 did not run on the coroutines/Ktor stack at
  all (member rebinding to an `invokespecial` on a non-direct superinterface).
- Rejected: dropping dependencies by hand (`material` beside `material3` is the obvious one). It is
  a few megabytes, and a shrinker does the same thing inside every jar.
- Not covered: the runtime (B-156), Skiko's 29 MB, which is Skia and is the same with any shrinker.
- Risk to look at, not to assume: reflection inside libraries — kotlinx.serialization in `:wire`,
  the MCP server in `:control`, `ServiceLoader` for the Swing main dispatcher. The ProGuard run
  above was clean of warnings, which says nothing about what is loaded by name.

- AC: the `.dmg`, `.msi` and `.deb` from `distributions.yml` are the release variant;
  `:ui:checkDistributable` runs that image's launcher; a real download through the installed
  release build completes on each platform, and the MCP server answers on it. The size before and
  after per platform is in research §1.3e.
- Anchors: `ui/build.gradle.kts`, `.github/workflows/distributions.yml`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/WideArguments.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/Preflight.kt`,
  `docs/research/research-architecture.md`.

**Done.** The installers are the release variant, and every task that runs or packages the image
moved with it: `checkDistributable`, the launcher path, `patchDesktopEntry` (now on
`packageReleaseDeb`) and `distributions.yml`. The Linux image is 145 → 128 MB on the build machine;
on the runners the shipped images are 134 / 122 / 115 MB (Linux / macOS / Windows), research §1.3g.

Two things the item did not plan for:

- **ProGuard had no JDK on CI.** Compose hands it `<java.home>/jmods`, and Temurin 25 ships none
  (JEP 493), so the first three-platform run failed on `java.lang.Object`. `:ui:extractJdkClasses`
  takes the classes out of `lib/modules` with `jimage` when the directory is absent.
- **The preflight cannot see a window, so a new check does.** `:ui:checkDownload` runs
  `DistributionDownloadTest`: the packaged launcher with a home of its own, its MCP server asked over
  the single-instance lock to download a megabyte from `LocalSwarm`, the bytes compared. It is in
  `distributions.yml` on all three runners (`xvfb-run` on Linux), not on `check`, which has no
  display.

- AC: release variant shipped — **met**, run 41099212492. `checkDistributable` on that image —
  **met**. A real download through the packaged release build on each platform, MCP answering —
  **met** by `checkDownload` on the three runners; it is the app image the installer is built from,
  not an *installed* copy, which no runner does. Sizes per platform — **met**, research §1.3g, with
  the before-and-after measured on Linux only.
