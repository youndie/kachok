---
id: B-156
title: "The desktop runtime is linked compressed, as the CLI's already is"
status: dropped
priority: P3
size: S
stage: phase-2-ui
---

# B-156 — The desktop runtime is compressed

The CLI's image is linked with `--compress zip-6` (`cli/build.gradle.kts`, research §1.3); the
desktop one is not, and its `lib/runtime/lib/modules` is 55 MB of the 145 MB installed. Measured on
2026-10-30 on the Linux box with the same seven modules and Compose's own flags
(`compose/tmp/createRuntimeImage.args.txt`): `--compress=zip-6` takes `modules` from 55 to 25 MB,
the runtime from 87 to 58 MB; `zip-9` gives the same 25 MB. The installer barely moves — `.deb`,
`.msi` and `.dmg` are compressed already — so this is disk after installation, not download.

**Compose has the switch and does not expose it.** `AbstractJLinkTask` has a `compressionLevel`
property and passes it to `jlink` when set, but the property and its `RuntimeCompressionLevel` type
are `internal` (read out of `compose-gradle-plugin-1.12.1.jar`); `nativeDistributions` has no
setting for it, and nothing in the plugin sets it.

- **The decision and its reason.** Measure the start-up cost first, then decide. Compressed
  resources are inflated as classes load, and the desktop image has no CDS archive to hide that;
  the CLI took the trade without a number for it. The start is measured to the first frame, with and
  without, the way §1.2a2 measures the CLI's.
- The way in, if it is worth having, is a question this item answers: setting the internal property
  reflectively on the `createRuntimeImage` task (one line, and it breaks silently on a plugin
  update — so the image size is asserted afterwards), or linking the runtime ourselves and pointing
  Compose at it. A plugin issue asking for the setting is worth filing either way.
- Rejected: `strip` on `libjvm.so` and Skiko (24 and 26 MB from 28 and 29). The Ubuntu JDK's
  `libjvm` is not stripped, but CI links from whatever `setup-kotlin` installs, and three platforms
  strip three different ways for 7 MB.
- Not covered: the classes (B-155).

- AC: research §1.3e has the image size and the time to the first frame with and without
  compression, per platform it was measured on, and the item records which one ships and why.
- Anchors: `ui/build.gradle.kts`, `docs/research/research-architecture.md`.

## Iteration 1 — 2026-10-30

Measured, research §1.3i. `--compress 2` (`zip`), set through the jlink task's internal
`compressionLevel` by reflection, takes the JDK's classes from 57 to 26 MB. At start: fifteen
interleaved pairs on the Linux box, medians 1145 ms uncompressed and 1047 ms compressed, the paired
median −64 ms inside a spread of ±500 ms — **no cost this machine can see**. Built and checked on all
three runners (`checkDistributable`, `checkDownload`, the installers; run 41108125361).

What the item did not expect: **the installers grow.** Installed 134 → 105 / 122 → 93 /
115 → 86 MB (Linux / macOS / Windows); installers `.deb` 44 → 56, `.dmg` 57 → 63, `.msi` 49 → 56 MB.
Every installer compresses its payload already, and does it better than `jlink`'s `zip`; classes
compressed inside `modules` leave it nothing.

The change is commit `f7c5924` on `feat/b-156-compressed-runtime`, with the guard that fails the
build when Compose stops passing `--compress` (checked by removing the setting: the build failed
with the guard's message). It is reverted on the branch, so `main` keeps the uncompressed runtime
until this is decided.

## Question — for the owner

Which size is the one to make smaller?

1. **The disk: ship it.** Take `f7c5924` as it is: 29 MB less installed on every platform, 6–12 MB
   more in every download, updates included.
2. **The download: do not.** Keep the runtime as it is and close this item; the number stays in
   research §1.3i so the question is not reopened without a new reason.

## Decision — 2026-10-30, the owner

**The download matters more: the runtime stays uncompressed.** Option 2 above. The numbers stay in
research §1.3i, so the question is not reopened without a new reason — a change in how the
installers compress, say, or the installed size becoming the complaint. Commit `f7c5924` on
`feat/b-156-compressed-runtime` is the change, guard included, if it ever is.
