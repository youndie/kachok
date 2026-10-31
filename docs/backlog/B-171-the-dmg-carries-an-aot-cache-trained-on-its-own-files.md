---
id: B-171
title: "The .dmg carries an AOT cache trained on its own files"
status: done
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

**Done, 2026-10-31.** `packageReleaseDmg` is finalised by `attachReleaseDmg` (a writable copy of the
image, enlarged — the VM could not write the cache into one sized to its contents),
`recordDmgAotConfiguration` (the bundle's launcher, `DistributionDownloadTest`, record mode) and
`addAotCacheToDmg` (assemble, name it in `Contents/app/kachok.cfg`, sign the bundle again ad hoc,
`codesign --verify --deep --strict`, compress over the original, attach read-only, copy the bundle
out with `ditto`, ask its `--preflight` with `-Xlog:aot`); `detachReleaseDmg` takes the images down
whatever happened. Both this and the `.deb` refuse a package that already carries a cache.

What the two questions above turned out to be: the ad-hoc signature does break when the cache is
added, and is signed again; `ditto` keeps the jars' times, and the copy maps the cache. One thing
nobody asked: **`jbr-api` is sealed**, and on the macOS runner the cache failed with a sealing
violation; the seal is now removed from the shrunk jar (research §1.3h).

Measured on the mac, ten interleaved pairs: **1010 → 517 ms** to an engine on the lock; installed
133 → 198 MB; `.dmg` 66.9 → 85.1 MB. On the runner (run 41186420766): a 58 MB cache, the `.dmg`
57 → 75 MB, and the bundle copied out of it logs `Using AOT-linked classes: true` with no `aot`
error. Positive control: a jar touched after the cache was assembled fails `addAotCacheToDmg` with
`timestamp has changed`.

- AC — **met**: a `.dmg` from `distributions.yml` whose app, copied out of the mounted image, maps
  its cache; time and size in research §1.3h.
