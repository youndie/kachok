---
id: B-158
title: "Decide whether Conveyor replaces jpackage as the desktop packager"
status: dropped
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

## Iteration 1 — 2026-10-30

Read, not installed: research §1.3j has each behaviour the packaging relies on against what
Conveyor's and Microsoft's documentation say, with the address of each. An installed build — the
other half of this item's evidence — needs Conveyor downloaded and its licence accepted, which the
loop does not do on the owner's behalf. What the reading settles already:

- **No licence, no free tier.** Conveyor is free for OSI-licensed projects; this repository is
  public with no licence at all.
- **On Windows the package is MSIX, and MSIX virtualises two things the client depends on.**
  Writes under `HKCU` go to a private per-app copy, so the `Run` value autostart writes (B-83) is not
  the one Windows reads; new files under `AppData\Roaming` go to a private per-package location, so
  a headless `kachok mcp` outside the package would not find the window's lock in
  `%APPDATA%\kachok` (B-117, B-136). Both are fixable — MSIX's startup task, a directory excluded
  from virtualisation — and both are client changes with a Windows re-test.
- **It takes the runtime out of Compose's hands**, so B-155's ProGuard output has to be fed to it
  by hand, B-156's flag moves to `app.jvm.jlink-flags`, and B-157's cache is not available.
- **Migration from the `.msi` people already have is not documented**, and a desktop shortcut
  (#27) is not either.

## Question — for the owner

Conveyor buys three things the current packaging does not have: one machine for three platforms,
delta updates, and an updater. To take them:

1. **Choose a licence** (an OSI-approved one, for the free tier) — nothing else can start before it.
2. **Then allow a trial on a branch**: download Conveyor, accept its terms, build the three packages,
   install them, and check the rows of §1.3j that are documents today — the association, the
   shortcut, the upgrade from an installed `.msi`, autostart and `kachok mcp` beside a packaged
   window on Windows. Size L, and the two virtualisation fixes are their own items.
3. **Or keep jpackage** and close this item; §1.3j stays as the reason, and the shortcut from #27
   is fixed in the current `.msi` (`shortcut = true` in `windows {}`).

## Decision — 2026-10-30, the owner

**Deferred: too many compromises for what it buys.** The packaging stays on jpackage. Dropped rather
than left `open`, so a loop does not pick it up again; it is a deferral, not a refusal, and the
reason to reopen it is a new one — updates becoming the complaint, or a licence chosen for the
repository for its own sake — with research §1.3j as the starting point rather than a fresh read.
