---
id: B-172
title: "The .msi carries an AOT cache trained on its own files"
status: open
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
