---
id: B-171
title: "The .dmg carries an AOT cache trained on its own files"
status: open
priority: P2
size: M
stage: phase-2-ui
blocked_by: [B-157]
---

# B-171 — The `.dmg` carries an AOT cache

[B-157](B-157-the-desktop-distribution-ships-an-aot-cache.md) put an AOT cache into the `.deb` by
training it on the package's own files after packaging (research §1.3h): 936 → 388 ms to a working
engine on an installed copy. The `.dmg` still ships without one.

- **The decision and its reason.** The same shape as `addAotCacheToDeb`: after
  `packageReleaseDmg`, attach the image read-write (`hdiutil convert -format UDRW`, `hdiutil
  attach`), record through the bundle's launcher (`Contents/MacOS/kachok`) with
  `DistributionDownloadTest`, assemble in create mode, name the cache in `Contents/app/kachok.cfg`,
  convert back to a compressed image, then copy the bundle out of the final image the way a person
  drags it to `/Applications` and check `Using AOT-linked classes: true` there.
- **To find out first, not to assume:** whether the bundle's code signature (ad hoc, from jpackage)
  breaks when a file is added to it, and whether a copy out of the `.dmg` keeps the jars' times —
  the cache is refused if it does not.
- Not covered: the `.msi` (B-172).

- AC: a `.dmg` from `distributions.yml` whose app, copied out of the mounted image, maps its cache;
  time and size on the macOS runner or the mac in research §1.3h.
- Anchors: `ui/build.gradle.kts`, `.github/workflows/distributions.yml`.
