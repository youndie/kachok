---
id: B-188
title: "CI runs the engine's tests on Windows, where the client's only outside user is"
status: done
priority: P2
size: S/M
stage: m0-skeleton
---

# B-188 — CI runs the engine's tests on Windows

Every test this repository runs on a push runs on Linux: `ci.yml:15` is `runs-on: ubuntu-latest`
and there is no other job (at `1a521f3`). Windows is reached only by `distributions.yml`'s
`windows-latest` entry (`distributions.yml:32`), on `workflow_dispatch`, and that job packages and
starts the window — it runs no tests. Meanwhile both defects reported from a real machine came from
Windows: [B-146](B-146-files-are-sparse-on-windows.md) (`SPARSE` never applied, a large torrent
stalled) and [B-116](B-116-a-torrent-whose-name-is-not-ascii-cannot-be-opened-on-windows.md) (a
non-ASCII path arrives as question marks), and the outside user of #27 installed the `.msi`.

- **A Windows test exists and no machine runs it.**
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/storage/FileSetTest.kt:100` —
  `aFreshFileIsSparseOnWindows`, `@EnabledOnOs(OS.WINDOWS)` — passed once, on the owner's machine
  (B-146's AC). On CI it is skipped on every push, so a change that brings the bug back is green.
- **The research said when this would be due.** Risk 6
  (`docs/research/research-architecture.md:1830-1833`): "the CI matrix grows when there is a
  Windows user". There is one. [B-02](B-02-ci-runs-build-and-docs-gates.md) lists "a Windows job"
  under *not covered* — this item is that line, not a duplicate of B-02.
- **The decision and its reason.** A second job in `ci.yml` on `windows-latest`, on the same
  `pull_request` and `push` to `main`, running the tests of the modules without a window —
  `:engine`, `:wire`, `:swarm`, `:control`, `:cli`. Not `:ui`: its goldens are a mac's rasteriser
  (B-184 says so of its own goldens), so a Windows render would be red for a reason that is not a
  defect. A public repository runs on hosted runners, as the Linux job already does.
- **The first run will probably be red, and that is the point.** Hypothesis, not observed: the
  runner's git checks out with `core.autocrlf=true`, the repository has no `.gitattributes`, and
  `.editorconfig:14` says `end_of_line = lf` — so a `check` that includes ktlint may fail on every
  file. Either a `.gitattributes` (`* text=auto eol=lf`) or tests without ktlint on that job; which
  one is decided from the first log, and the reason is written in the workflow. Any test that fails
  on Windows for a real reason is fixed or filed as its own item — not disabled for the OS.
- Not covered: `:ui` on Windows, the packaged window on Windows on every push (that is
  `distributions.yml`), macOS tests.

- AC: a pull request shows a Windows job beside the Linux one, and it is green.
- AC: that job's test report lists `FileSetTest.aFreshFileIsSparseOnWindows` as **run**, not
  skipped; removing the `CREATE_NEW` open in `FileSet.kt` on a branch turns the job red (B-146's
  mutant, now on CI).
- Anchors: `.github/workflows/ci.yml`, `.editorconfig`,
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/storage/FileSetTest.kt`.

## Done — 2026-11-09

A `windows` job in `ci.yml` on `windows-latest`, beside `build`, on the same `pull_request` and
`push` to `main`: `check` for `:engine`, `:wire`, `:swarm`, `:control` and `:cli`, and the test
reports uploaded as `windows-reports` whatever the outcome, so a green run can be read for what it
ran.

- **First run green** ([42686983069](https://github.com/youndie/kachok/actions/runs/42686983069),
  `af4d204`, 4 min 53 s of Gradle): 626 tests, 3 skipped, 0 failures across the five modules.
  `FileSetTest.aFreshFileIsSparseOnWindows` is in the report as run, with no `<skipped/>`.
- **The line-ending hypothesis was wrong.** Every ktlint task of the five modules ran on the runner
  and passed; there was no CRLF failure to decide between `.gitattributes` and tests-only on, so
  the job runs `check` and the repository has no `.gitattributes`. Why it passed — the checkout's
  line endings, or ktlint not judging them — was not established; the workflow's comment names
  `* text=auto eol=lf` as the fix if a ktlint bump changes that.
- **B-146's mutant, on CI**: `CREATE_NEW` → `CREATE` in `FileSet.create` (`3f565c1`) turned the
  job red — `aFreshFileIsSparseOnWindows() FAILED`, "a fresh file is not sparse (attributes
  0x20)", 1 of 533 — while the Linux `build` stayed green
  ([42688839593](https://github.com/youndie/kachok/actions/runs/42688839593)). Reverted in the
  next commit.
- Research Risk 6 and B-02's *not covered* line are amended at their points.
