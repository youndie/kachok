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

- **The decision and its reason.** Both jobs on `ubuntu-latest`, because the repository is meant
  to be public and public repositories get hosted runners; the build job uses the portfolio's
  `setup-kotlin` composite action so the JDK pair and the Gradle cache are the same as in the
  other repositories.
- Rejected: one workflow for both. The documentation gate takes seconds and should be red on its
  own line when it is red; a Gradle failure must not hide a broken link and vice versa.
- Not covered: a Windows job (research Risk 6), the scheduled anchors job's first green run (it
  runs on a Monday; check it after the first one).

- AC: a push to `main` shows both workflows green in the Actions tab, and a deliberately broken
  frontmatter link on a branch turns `check` red on that branch's pull request.
- Anchors: `.github/workflows/ci.yml`, `.github/workflows/check.yaml`, `Makefile`.
