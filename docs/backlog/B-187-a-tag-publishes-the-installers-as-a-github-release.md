---
id: B-187
title: "A tag publishes the three installers as a GitHub Release, and the README leads to it"
status: wip
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-185]
---

# B-187 — A tag publishes the installers as a GitHub Release

There is nowhere a stranger can download kachok from. `gh api repos/youndie/kachok/releases`
returns an empty list and the repository has no tags (both read 2026-11-09). The installers exist —
the last `distributions` run (`41514499257`, at `00ae2dc`, 2026-11-02) made a `.dmg`, an `.msi` and
a `.deb` — but only as workflow artifacts: `distributions.yml:7` runs on `workflow_dispatch` alone,
and `retention-days` (`distributions.yml:9`, at most 30) deletes them; that run's expire on
2026-12-02. An artifact is found through the Actions tab of one run, and downloading one needs a
signed-in GitHub account (GitHub's documentation on workflow artifacts; not tried here). The
README, meanwhile, tells a reader to build the installer from source (`README.md:32-33`:
`./gradlew :ui:packageReleaseDistributionForCurrentOS`), which needs a JDK 25 and the platform the
installer is for.

- **The decision and its reason.** `distributions.yml` also runs on `push` of a tag `v*`, and a
  last job — after the `desktop` matrix — downloads the three installers and creates the release
  with `gh release create "$GITHUB_REF_NAME" … --generate-notes`. The workflow already builds each
  installer on its own platform and checks it (`checkDistributable`, `checkDownload`); a second
  workflow would be a second copy of that. `permissions: contents: write` goes on that one job;
  the workflow's `contents: read` (`distributions.yml:14-15`) stays for the rest.
- **The tag names the version, and the job refuses one that does not.** The package version is
  `0.1.<commits>` on Windows and Linux and `1.0.<commits>` on macOS (B-82, B-90;
  `ui/build.gradle.kts:160` and `:240`). Hypothesis for the shape: the tag is `v0.1.<commits>` and
  the release job fails when the `.msi`'s and the `.deb`'s file names do not carry that number —
  otherwise a tag pushed on the wrong commit publishes a release whose name and files disagree.
  The `.dmg`'s `1.0.<commits>` is said in the release notes rather than hidden.
- **Pushing a tag is publishing.** The loop builds and checks the workflow on a branch; the first
  tag, and every one after it, is the owner's. Blocked by [B-185](B-185-the-readme-says-mit-and-the-repository-has-no-licence.md):
  binaries offered to strangers should come with a licence.
- **The README leads with it.** A short *Install* section before *Three ways in*: the
  `releases/latest` link, which file is for which system, and what an unsigned installer looks like
  on first start ([B-189](B-189-the-readme-says-what-an-unsigned-installer-looks-like.md)).
  Building from source stays, below it.
- Rejected: attaching the files to the release by hand after a dispatch. It works once, and it is
  how a release ends up with two of three files or files from another commit.
- Not covered: the headless tarball (it can be a fourth file; the always-on box it is for is
  [B-87](B-87-a-server-with-a-web-face.md)), an updater, winget / Homebrew / apt repositories,
  signing ([B-190](B-190-sign-the-installers-or-ship-them-unsigned.md)), an Intel `.dmg`.

- AC: a tag `v0.1.<n>` pushed by the owner produces a GitHub Release with exactly the `.msi`, the
  `.dmg` and the `.deb` built from the tagged commit, and `releases/latest` resolves to it.
- AC: a tag whose number is not the package version fails the release job and creates no release.
- AC: the README's first instruction for the window is the release link; `make check` green.
- Anchors: `.github/workflows/distributions.yml`, `README.md`, `ui/build.gradle.kts`.

## Iteration 1 — 2026-11-09

Built on the branch; **not exercised end to end, because that needs a tag and tags are the
owner's.**

- `distributions.yml` runs on `push` of a tag `v*` as well as on dispatch; artifacts on a tag run
  keep 30 days (`inputs.retention-days || '30'` — on a tag there are no inputs). A last job,
  `release`, `needs: [desktop]`, with `contents: write` on that job alone, downloads this run's
  `kachok-*-<sha>` artifacts and runs `gh release create <tag> … --verify-tag --notes-file …
  --generate-notes` with the three installers.
- `scripts/release_assets.sh <tag> <dir>` decides the files and refuses a tag that does not name
  them. The names it expects were read off the last `distributions` run
  ([42683975110](https://github.com/youndie/kachok/actions/runs/42683975110), `944fcb8`, whose
  `git rev-list --count` is 246): `kachok-0.1.246.msi`, `kachok_0.1.246_amd64.deb`,
  `kachok-1.0.246.dmg`. Tried on empty files with those names: `v0.1.246` → the three paths, exit
  0; `v0.1.247` → all three named as disagreeing, exit 1; `v1.0.246` → refused as not a release
  tag; a second `.msi` beside the first → refused; an empty directory → refused. The publish step
  itself was run under `bash -eo pipefail` on the build machine with the same fixtures: a good tag
  reaches the publish line with 3 files, a wrong one stops before it.
- **A defect caught before it shipped:** the first draft read the list through
  `mapfile < <(release_assets.sh …)`, which discards the script's exit status — a refused tag would
  have published a release with no files. It reads a file now, and the step's comment says why.
- The README leads with an *Install* section linking `releases/latest`; until the first tag that
  link has nothing to show. `docs/services/ui.md` says how the tag is made and who pushes it.
- **Left for the owner's first tag:** AC 1 (the release has exactly the three files, and
  `releases/latest` resolves to it) and AC 2 (a tag whose number is not the package version fails
  `release` and creates no release) — the second is the fixture run above, not yet a real tag. The
  item stays `wip` until a real tag has done both.
