---
id: B-02
title: "CI runs the build and the documentation gates on every push"
status: open
priority: infra
size: S
stage: m0-skeleton
blocked_by: [B-01]
---

# B-02 — CI runs the build and the documentation gates on every push

Two workflows exist in `.github/workflows/` — `ci.yml` (Gradle build) and `check.yaml` (the
documentation gate, `make check`, plus `docs_check.py --on-main` on the default branch) — and
**neither has run**. A workflow that was written and never executed is the same as no workflow: the
first run is where the runner label, the Python version, the Gradle cache and the `git fetch` for
the backlog collision check all get exercised for the first time.

## A third workflow, added 2026-09-06 and also never run

`distributions.yml`, on `workflow_dispatch` alone: it builds the installer for each platform on
that platform — `jpackage` cannot cross-compile — and the headless run-time image on Linux, and
uploads them as artifacts kept for a month by default.

* **Dispatch and not push.** A run makes about 200 MB on three runners; on every push that is
  storage and minutes spent on artifacts nobody downloads.
* **`if-no-files-found: error` on every upload.** `packageDistributionForCurrentOS` with no format
  configured succeeded in under a second and wrote nothing, which is how
  [B-82](B-82-an-installer-per-platform.md) was filed. Without that line a run that produces no
  installer is a green run.
* **`:ui:checkDistributable` before the packaging**, on the platform that will ship it: the trimmed
  runtime is the only place a missing module can exist, and it shipped once without `java.net.http`
  ([B-78](B-78-nothing-runs-the-packaged-application.md)).
* **The headless image is tarred rather than uploaded as a directory.** `upload-artifact` zips what
  it is given and a zip keeps no executable bit, so `bin/kachok` would arrive unrunnable — verified
  by building the image and reading the tar's mode bits, `-rwxrw-r--`.
* Linux only for that one, and it is a fact rather than a choice: the generated launcher is
  `#!/bin/sh` with a colon-separated class path.

**The step most likely to need a second attempt is the macOS preflight.** It runs the packaged
`.app`'s own launcher, which works on a mac with a session; whether it does on a hosted runner with
no window server is not something this repository can find out before the first dispatch.

- **The decision and its reason.** Both jobs on `ubuntu-latest`, because the repository is meant
  to be public and public repositories get hosted runners; the build job uses the portfolio's
  `setup-kotlin` composite action so the JDK pair and the Gradle cache are the same as in the
  other repositories.
- Rejected: one workflow for both. The documentation gate takes seconds and should be red on its
  own line when it is red; a Gradle failure must not hide a broken link and vice versa.
- Not covered: a Windows job (research Risk 6), the scheduled anchors job's first green run (it
  runs on a Monday; check it after the first one).

- AC: a push to `main` shows both workflows green in the Actions tab; a deliberately broken
  frontmatter link on a branch turns `check` red on that branch's pull request; one
  `workflow_dispatch` of `distributions` produces four artifacts, and each of them is a file
  somebody can install or unpack.
- Anchors: `.github/workflows/ci.yml`, `.github/workflows/check.yaml`,
  `.github/workflows/distributions.yml`, `Makefile`.
