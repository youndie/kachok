---
id: B-156
title: "The desktop runtime is linked compressed, as the CLI's already is"
status: wip
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
