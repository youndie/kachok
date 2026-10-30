---
id: B-157
title: "The desktop distribution ships an AOT cache trained through its own launcher, and proves it maps"
status: wip
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
