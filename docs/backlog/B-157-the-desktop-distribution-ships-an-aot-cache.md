---
id: B-157
title: "The desktop distribution ships an AOT cache trained through its own launcher, and proves it maps"
status: done
priority: P2
size: M
stage: phase-2-ui
blocked_by: [B-155]
---

# B-157 — The desktop distribution ships an AOT cache

The CLI ships a Leyden AOT cache (B-28): it halves the start, costs 27.9 MB, and the build fails
unless the VM says `Opened AOT cache`. The window ships none, and no CDS archive either — `jlink`
does not carry the JDK's default one into the image. The window is the surface a person starts by
hand, so it is where start-up is seen.

**This is the opposite trade to B-155 and B-156**, and the two are planned together for that
reason: the CLI's cache nearly doubled its distribution, and a Compose cache is likely larger than
the CLI's, because more classes load before the first frame. The size it adds is recorded next to
the size the others take away.

- **The decision and its reason.** Train through the packaged launcher, as B-28 does. A cache is
  refused unless the class path, the jars and the VM flags match the ones it was recorded with, and
  the refusal is a warning line and a normal start — so the only training worth having is the one
  that runs exactly what a person runs, on the release image (hence `blocked_by: B-155`: a cache
  trained before ProGuard is a cache for jars that do not ship).
- **zavarnik is the portfolio's mechanism and does not fit this shape yet.** It trains
  `installDist` and Jib images through their start scripts; a `jpackage` app image has a native
  launcher, its class path and flags in `lib/app/kachok.cfg`, and no script to put the
  "only if the file is there" guard in. Its research also settles what this item has to respect:
  `-XX:AOTCache` and `-XX:AOTCacheOutput` together are a VM error, so training needs a `.cfg`
  without the first; the cache is written on exit or SIGTERM and not on SIGKILL. Whether the
  support goes into zavarnik or stays in `ui/build.gradle.kts` is decided here; the first is the
  default, because a second hand-made copy of B-28 is how the two drift.
- **What survives installation is a hypothesis per platform.** HotSpot checks each jar's mtime and
  size. A `.deb` and a `.dmg` keep mtimes as far as is known; whether an `.msi` does is not known,
  and B-158 may change the packager altogether. Each platform is checked by starting the *installed*
  application with `-Xlog:aot`, not the app image the build left behind.
- Training needs a window doing work: a scripted session against `LocalSwarm` — open a torrent,
  download it, quit — and on Linux CI a virtual display.
- Not covered: refreshing the cache when the JDK changes — the build makes a new one, as for the CLI.

- AC: on each platform, the installed application's log says `Opened AOT cache`; time to the first
  frame with and without it, and the size it adds, are in research §1.3e.
- Anchors: `ui/build.gradle.kts`, `.github/workflows/distributions.yml`,
  `docs/research/research-architecture.md`.

## Iteration 1 — 2026-10-30

Measured, research §1.3h: on the Linux box a cache trained through the packaged window takes the
time to a working engine from **1169 to 413 ms** (medians of ten interleaved pairs), and is
**62.6 MB**. Two things were found that change what this item costs:

- **The image cannot assemble its own cache in one step.** `-XX:AOTCacheOutput` writes the
  configuration and then needs a `java` to assemble the cache, and the image has none
  (`--strip-native-commands`). The two-step form through the image's own launcher works.
- **The installers do not carry the image the cache was trained on.** `packageRelease*` copies the
  jars again, with new modification times, and the cache is refused by the installed copy —
  measured on the `.deb`, with `timestamp has changed` and `Unable to map shared spaces`. So
  "train on `createReleaseDistributable`, ship in the installer" is not available at all; the cache
  has to be trained on each installer's own files after packaging and put back into it.

Also found: the VM prints `Opened AOT cache` *before* it refuses a cache, so the CLI's check for that
line (B-28) cannot see a refusal — [B-163](B-163-an-aot-cache-is-opened-before-it-is-refused.md).

What is on the branch and kept: `:ui:checkDownload` passes `-Pkachok.jvmOptions` through to the
window (how the training above was run) and waits long enough for a VM to write its cache on the
way out; `scripts/aot_startup.py` is the measurement.

## Question — for the owner

The cache is two thirds of the window's start and a quarter of its installed size again (63 MB on
top of B-155's 115–134). The choices:

1. **Ship it, per format.** After each `packageRelease*`, unpack the installer, train and assemble
   the cache against its files, add the cache and `-XX:AOTCache=$APPDIR/…` to its `.cfg`, repack.
   The `.deb` repack exists already (`patchDesktopEntry`); the `.dmg` needs `hdiutil`; the `.msi`
   needs either a repack of the MSI or packaging it with `jpackage --app-image`, and jar times
   normalised to an even second for the cabinet. Each verified by starting the *installed* client.
   Size L, three platforms.
2. **Build it on the user's machine at first start.** The window records a configuration on its
   first run and assembles the cache by starting its own launcher in create mode; later starts use
   it. Nothing extra to download, the first start stays slow, and the cache has to live in the
   install directory, which on Linux (`/opt`) the user cannot write.
3. **Not now.** Keep the numbers; reopen when start-up is the complaint. The CLI keeps its cache.

## Decision — 2026-10-30, the owner

**Option 1, and dropped if it does not hold.** The cache is trained on each installer's own files
after packaging and put back into it, one format at a time, the `.deb` first.

## Iteration 2 — 2026-10-30: the `.deb`

**Done for the `.deb`; the other two formats are their own items** —
[B-171](B-171-the-dmg-carries-an-aot-cache-trained-on-its-own-files.md) for the `.dmg`,
[B-172](B-172-the-msi-carries-an-aot-cache-trained-on-its-own-files.md) for the `.msi` — because
each repacks differently and this one proved the mechanism without needing the other two.

`packageReleaseDeb` is now finalised by three tasks: `unpackReleaseDeb` (`dpkg-deb -R`, which keeps
the times the package carries), `recordDebAotConfiguration` (`DistributionDownloadTest` through the
unpacked launcher with `-XX:AOTMode=record`) and `addAotCacheToDeb` (the cache assembled by that
launcher in create mode, `-XX:AOTCache=$APPDIR/kachok.aot` added to `kachok.cfg`, the package
rebuilt in place, then unpacked once more elsewhere and its `--preflight` run with `-Xlog:aot`).
The build fails unless that log says `Using AOT-linked classes: true` with no `aot` error — the
B-163 marker, not `Opened AOT cache`. `distributions.yml` packages under `xvfb-run` and then installs
the package with `dpkg -i` and asks `/opt/kachok/bin/kachok` the same question.
`-Pkachok.aot=false` packages without the cache.

Measured on the Linux box (research §1.3h): installed copies, ten interleaved pairs, launch to an
engine on the lock — **936 → 388 ms** median; installed **130 → 191 MB**; the `.deb` **44.9 →
57.2 MB**. Positive control: a jar touched after the cache was assembled fails `addAotCacheToDeb`
with `timestamp has changed`.

On the runner (`distributions.yml` run 41133211472, Temurin 25.0.5): a 60 MB cache, the `.deb`
57 MB, and `/opt/kachok/bin/kachok` installed by `dpkg -i` logs `Using AOT-linked classes: true`.
Installing it there first failed in jpackage's own `postinst`: `xdg-desktop-menu` refuses with
"No writable system menu directory found" unless `/etc/xdg/menus`, `/usr/share/desktop-directories`
and `/usr/share/applications` exist, which a desktop has and a headless runner had not; the workflow
creates them before `dpkg -i`. A guess at one of the three did not fix it — the script was read.

- AC for the `.deb` — **met**: the installed client maps its cache (`dpkg -i` on the runner), the
  times and the sizes are in research §1.3h. macOS and Windows — B-171 and B-172.

- **Reversed by [B-173](B-173-the-window-ships-unshrunk-and-without-an-aot-cache.md)** (2026-10-31): the cache is gone again.
