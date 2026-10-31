---
id: B-173
title: "The window ships its classes as compiled and no installer carries an AOT cache"
status: done
priority: P1
size: S
stage: phase-2-ui
---

# B-173 — The window ships unshrunk, and without an AOT cache

[B-155](B-155-the-desktop-distribution-ships-shrunk-classes.md) put the window's classes through
ProGuard, with Compose's optimisation on;
[B-157](B-157-the-desktop-distribution-ships-an-aot-cache.md) and
[B-171](B-171-the-dmg-carries-an-aot-cache-trained-on-its-own-files.md) trained an AOT cache on
those classes for the `.deb` and the `.dmg`. Both were experiments in size and start-up, and the
first installed release on Windows (`kachok-0.1.222`, the `.msi`) showed what they cost:

- **The optimiser wrote bytecode the VM refused.** Changing the colour setting killed the window:
  `java.lang.VerifyError: Inconsistent stackmap frames at branch target 1440` in
  `SeededDesignKt.seededDesign` (`startup-error.txt`). `checkDistributable` and `checkDownload` had
  passed on that image — neither draws the seeded scheme — so a check that runs what ships is not a
  check of every method ProGuard rewrote. Reproduced on Windows with a configuration directory of
  its own and `colourSeed=#E91E63`: the shrunk image throws it at once, the unshrunk one opens.
- **Not this: the download that stood still.** The same installed image stopped every torrent a
  couple of minutes into a download and was suspected first. It is the direct-memory ceiling, not
  the optimiser: 8 959 pooled blocks (140 MiB) were on loan when it stopped, under a
  `MaxDirectMemorySize` of 192 MiB, and it happened only once the limit was raised to 100 Mbit/s.
  That is an engine item of its own.

- **The decision and its reason.** The release variant stays and `proguard { isEnabled = false }`:
  the `Release` task names are what `checkDistributable`, `checkDownload` and `distributions.yml`
  run, so the image the checks see is still the one the installers carry. Half the jars was not worth
  classes nobody had compiled. The AOT tasks go with it, at the owner's word: the caches were
  trained on the shrunk classes, and the `.msi`'s could not have worked at all
  ([B-172](B-172-the-msi-carries-an-aot-cache-trained-on-its-own-files.md), dropped). Packaging no
  longer needs a display. The CLI's AOT cache is a separate thing and stays.
- Removed: `:ui:extractJdkClasses`, `:ui:jdkLibraryRules`, `ui/proguard-rules.pro`, the `.deb` and
  `.dmg` AOT tasks, the `jbr-api` unsealing (needed only by the `.dmg`'s cache), and the two
  "maps its AOT cache" steps of `distributions.yml`. Research §1.3g and §1.3h are amended at the
  top, their measurements kept.

- AC: the release image's jars are the compiled ones, and no installer names an AOT cache in
  `kachok.cfg`. **Met** for the Windows image built here: no `proguard` step in the build, no
  `kachok.aot` and no `AOTCache=` in `ui/build/compose/binaries/main-release/app/kachok/app/`.
  `distributions.yml` on the three runners says the same of the others.
- AC: the window survives a seeded colour scheme. **Met** on the Windows build, as above.
- Anchors: `ui/build.gradle.kts`, `.github/workflows/distributions.yml`.
