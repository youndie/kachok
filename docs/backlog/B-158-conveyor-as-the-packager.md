---
id: B-158
title: "Decide whether Conveyor replaces jpackage as the desktop packager"
status: open
priority: P3
size: M
stage: phase-2-ui
---

# B-158 — Conveyor as the packager

Every update is a whole installer — 57 MB for the `.deb` — and the packaging is built by hand
around `jpackage`'s limits: a matrix of three runners because it cannot cross-compile, a commit
count in the version so an `.msi` upgrades at all, a fixed `upgradeUuid`, a repacked `.deb` for the
`%f` its `.desktop` template leaves out (B-84), `perUserInstall`, and a Windows shortcut the
installer still does not create (issue #27). Conveyor (Hydraulic) is the packager Compose Desktop
projects reach for instead: one machine builds all three platforms, and updates download a delta.

- **The decision and its reason.** Evaluate before adopting, on a branch, against what the current
  packaging already does — because each of those workarounds is a behaviour a user relies on, and a
  packager that drops one of them is a regression no build fails on.
- What the evaluation answers, each from Conveyor's documentation *and* an installed build, not
  from either alone: the licence for an open-source project; `.torrent` association on all three;
  the Windows format (an MSIX-based install, as far as is known, which is a different filesystem
  model from today's per-user `.msi`) and what that does to a user upgrading **from** the `.msi`
  already installed; the desktop shortcut; signing and notarisation on macOS; whether jar mtimes
  survive installation (B-157).
- Rejected: keeping `jpackage` and adding an updater of our own. It is the part Conveyor exists for,
  and writing it would be a second product.
- Not covered: the size of what is installed — Conveyor packages the same runtime and jars, so
  B-155 and B-156 stand either way.

- AC: a written decision in this item, adopt or not, with the installed-build checks above as
  evidence; if adopted, a follow-up item names the migration path for existing `.msi` installs.
- Anchors: `ui/build.gradle.kts`, `.github/workflows/distributions.yml`.
