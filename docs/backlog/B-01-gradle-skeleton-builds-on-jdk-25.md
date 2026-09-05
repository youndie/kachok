---
id: B-01
title: "The Gradle skeleton builds, lints and tests on JDK 25"
status: done
priority: P0
size: S
stage: m0-skeleton
---

# B-01 — The Gradle skeleton builds, lints and tests on JDK 25

Before any engine code, the build itself has to be a verified fact rather than an assumption:
Kotlin 2.4.10 on JDK 25 with the portfolio's shared conventions, one multiplatform module with a
single `jvm()` target, one JVM application module, and the compiler flags the research settled on.

- **The decision and its reason.** Two modules from the start (`:engine`, `:cli`), the conventions
  from `ru.workinprogress.sborka` 0.2.0.29, `jvmDefault = NO_COMPATIBILITY`, the assertion flags
  behind `-Pkachok.release` — [../research/research-architecture.md](../research/research-architecture.md) D7, D9, D11. Verifying the flags in a real build is what
  separates "the compiler reference does not list `-Xno-param-assertions`" from "the compiler
  refuses it".
- Rejected: a single module with the CLI inside the engine. It compiles faster and it makes the
  phase-2 UI a second `main` in a library, which is how a library grows a UI dependency.
- Not covered: the run-time image, the AOT cache, CI — [B-02](B-02-ci-runs-build-and-docs-gates.md),
  [B-29](B-29-jlink-runtime-image.md).

- AC: `./gradlew build` and `./gradlew build -Pkachok.release` are green; the test reports show
  every declared test executed (the `sborka.test` guard). **Met 2026-09-05**: 3 tests, 0 failures,
  both builds.
- Anchors: `settings.gradle.kts`, `build.gradle.kts`, `engine/build.gradle.kts`, `cli/build.gradle.kts`,
  `gradle/libs.versions.toml`.
