---
id: B-172
title: "The .msi carries an AOT cache trained on its own files"
status: dropped
priority: P2
size: L
stage: phase-2-ui
blocked_by: [B-157]
---

# B-172 — The `.msi` carries an AOT cache

[B-157](B-157-the-desktop-distribution-ships-an-aot-cache.md) put an AOT cache into the `.deb`
(research §1.3h); the `.msi` still ships without one, and it is the format the client's first
outside user installed (#27).

- **The decision and its reason.** An `.msi` is not unpacked and repacked the way a `.deb` is, so
  the cache is trained on what the installer puts on the disk: install the `.msi` on the runner
  (per-user, `msiexec /i … /qn`), record and assemble through the installed `kachok.exe`, then
  build the `.msi` again with the cache in it — `jpackage --app-image` from the installed tree, or
  the cache added through Compose's resources — and install *that* one to check
  `Using AOT-linked classes: true`.
- **To find out first:** whether Windows Installer keeps the jars' modification times (a cabinet
  stores them at two-second resolution, so the times may need normalising to an even second before
  packaging), and whether a rebuilt `.msi` keeps `upgradeUuid`, the file association and the
  per-user install.
- Not covered: the `.dmg` (B-171).

- AC: an `.msi` from `distributions.yml` that, installed on the runner, maps its cache; time and
  size in research §1.3h.
- Anchors: `ui/build.gradle.kts`, `.github/workflows/distributions.yml`.

## Iteration 1 — 2026-10-31

Measured before building anything (research §1.3h): **an `.msi` installs its files with times that
move with the installing machine's time zone.** One jar, the same package: 09:20:36 UTC installed at
UTC, 00:20:36 UTC installed at Tokyo — the cabinet keeps local time without a zone. The HotSpot
check is exact, so a cache trained on the runner (UTC) maps only on machines that install at UTC;
everywhere else it is a silent slow start and 60 MB of nothing. The approach this item planned —
and B-157 and B-171 used — cannot work for this format, however the package is rebuilt. Nothing
else was changed: the experiment that measured it ran on the branch and was removed.

## Question — for the owner

1. **Not on Windows.** Close this item; the `.msi` ships without a cache and the numbers stay in
   research §1.3h. The `.deb` and the `.dmg` keep theirs.
2. **Make the cache on the user's machine.** The per-user install directory
   (`%LOCALAPPDATA%\kachok`) is writable, which is what Linux's `/opt` was not: the window records a
   configuration during its first session and, on the way out, starts its own launcher in create
   mode; later starts name the cache. The first start stays slow, the 60 MB is written on the user's
   disk instead of downloaded, and it is client code — `-XX:AOTCache` in `kachok.cfg` for a file that
   does not exist yet has to be shown to cost nothing, and a cache made by one JDK has to be dropped
   when an update brings another. Size L, its own item.

## Dropped — 2026-10-31

With [B-173](B-173-the-window-ships-unshrunk-and-without-an-aot-cache.md) no installer carries an
AOT cache, at the owner's word; the measurement above is why this one could not have, whatever the
packaging.
