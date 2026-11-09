---
id: B-186
title: "The .msi makes no desktop shortcut, and very likely no Start menu entry either"
status: open
priority: P1
size: S
stage: phase-2-ui
epic: feature-ui
---

# B-186 — The `.msi` makes no desktop or Start menu shortcut

The client's only outside user reported it on 2026-09-18 in
[#27](https://github.com/youndie/kachok/issues/27): after a successful install on Windows 11 there
is no shortcut on the desktop, and they asked for an option in the installer to create one. The
issue was closed on 2026-10-30 at 19:13:44 by the merge of #109 (`8a95792`, 19:13:43), which changed
only `backlog.md`, the B-158 item and the research — the installer did not change, and research
§1.3j still says "#27, still missing" (`docs/research/research-architecture.md:896` at `1a521f3`).

**Read from the build, not guessed.** `windows {}` in `ui/build.gradle.kts:242-255` sets the icon,
the file association, `upgradeUuid`, `menuGroup = "kachok"`, `perUserInstall` and `dirChooser` —
and neither `shortcut` nor `menu`. In `compose-gradle-plugin-1.12.1.jar` (the Compose Multiplatform
version `gradle/libs.versions.toml:32` names), `WindowsPlatformSettings` has both as plain
`Boolean`s and its constructor sets only `dirChooser = true`, so both are `false`; the jpackage
flags its `AbstractJPackageTask` knows are `--win-console`, `--win-dir-chooser`, `--win-menu`,
`--win-menu-group`, `--win-per-user-install`, `--win-shortcut`, `--win-upgrade-uuid`.

- **Hypothesis: there is no Start menu entry either.** jpackage documents `--win-menu-group` as the
  group for the entry `--win-menu` adds; without `--win-menu` the group names a folder nothing is
  put in. Not observed yet. It is checked the way [B-90](B-90-the-msi-did-not-upgrade.md) read the
  upgrade table — out of the `.msi`'s database, with no installation: the `Shortcut` table of the
  `.msi` from the latest `distributions` run (`41514499257`, `00ae2dc`), before the change and
  after it.
- **The decision and its reason.** `shortcut = true` and `menu = true` in `windows {}`. A per-user
  install puts the desktop shortcut on the user's own desktop, and an `.msi` that upgrades an
  installed one (B-90) installs the new components, so the person who filed #27 gets the shortcut
  from the next package over the one they have — that last part is a hypothesis until the
  upgrade below is done.
- Rejected: an option in the installer, which is what #27 asked for. jpackage has
  `--win-shortcut-prompt`, but Compose 1.12.1 does not pass it (the flag list above) and has no
  free-form argument for jpackage; a hand-written WiX override in `--resource-dir` is a packaging
  fork to keep up with every jpackage. A shortcut that is always made and can be deleted is what
  most installers do; the prompt can come back if Compose grows the flag.
- Not covered: the `.deb` (its `.desktop` entry is already the menu entry, B-84) and the `.dmg`
  (macOS has no installer-made shortcuts). Signing (B-190).

- AC: the `Shortcut` table of the new `.msi` has a desktop row and a Start menu row; what the old
  one had is written here.
- AC (**by hand, on a Windows 11 host** — no runner shows a desktop to look at): installing the
  new `.msi` over an installed earlier one leaves a *kachok* shortcut on the desktop and a *kachok*
  entry in the Start menu, both starting the window; uninstalling removes both.
- AC: research §1.3j's "still missing" is amended at that row, and the release that carries the
  fix is what the owner answers #27 with ([B-187](B-187-a-tag-publishes-the-installers-as-a-github-release.md)).
  The answer in the issue is the owner's; this item writes nothing there.
- Anchors: `ui/build.gradle.kts`, `docs/research/research-architecture.md`,
  `docs/services/ui.md`.
