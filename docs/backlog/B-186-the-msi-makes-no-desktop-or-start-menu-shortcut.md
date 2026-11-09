---
id: B-186
title: "The .msi makes no desktop shortcut, and very likely no Start menu entry either"
status: done
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

## Findings

**Read out of the package, with nothing installed** — `scripts/check_msi_shortcuts.ps1`, a step of
the Windows job in `distributions.yml` since this item, which opens the `.msi` read-only through the
WindowsInstaller COM object and resolves each shortcut's directory up the Directory table.

Before — the configuration on `main` (`shortcut` and `menu` unset), run
[42682676977](https://github.com/youndie/kachok/actions/runs/42682676977) at `cc5379d`,
`kachok-0.1.245.msi`:

```
shortcutd18a9d87…  in dirb0f449c0… < ProgramMenuFolder < TARGETDIR  name 'kachok'
Missing from the package: a desktop shortcut (DesktopFolder).
```

- **The hypothesis was wrong: there was a Start menu entry.** One row, in the `menuGroup` folder
  under `ProgramMenuFolder`. So `menuGroup` alone makes the entry with this jpackage, and the
  title's "very likely no Start menu entry either" is refuted; the item keeps its title because its
  file name is its id. `menu = true` stays in the change, so the entry is asked for rather than
  a side effect of the group.
- **What #27 reported is confirmed:** no row in `DesktopFolder`.
- The check was shown to fail on the old package before the fix landed — the run above is red at
  that step. The first attempt
  ([42681116206](https://github.com/youndie/kachok/actions/runs/42681116206)) was red for the wrong
  reason, a defect in the script (`DISP_E_TYPEMISMATCH` reading a field), and is not the control.

After — `shortcut = true`, `menu = true`, run
[42683975110](https://github.com/youndie/kachok/actions/runs/42683975110) at `944fcb8`,
`kachok-0.1.246.msi`, the step green:

```
shortcutd18a9d87…  in dirb0f449c0… < ProgramMenuFolder < TARGETDIR  name 'kachok'
shortcutd33ec403…  in DesktopFolder < TARGETDIR                     name 'kachok'
A desktop shortcut and a Start menu entry: both present.
```

## Iteration 1 — 2026-11-09

- AC 1 (the Shortcut table has a desktop row and a Start menu row; the old one written here): met,
  above, and kept by the `distributions.yml` step.
- AC 3 (research §1.3j amended at its row): done. The release that carries the fix is
  [B-187](B-187-a-tag-publishes-the-installers-as-a-github-release.md)'s, and the answer in #27 is
  the owner's.
- **AC 2 is not checked by hand** — installing the new `.msi` over an installed earlier one on a
  Windows 11 host, seeing *kachok* on the desktop and in the Start menu, both starting the window,
  and uninstalling to see both go. No runner shows a desktop, and the loop has no Windows host. The
  upgrade picking up the new shortcut component is still the hypothesis the decision named. So the
  item stays `wip`: what is left is that one check by a person on Windows, with the `.msi` from the
  next `distributions` run on `main`.

## Iteration 2 — 2026-11-09, on the owner's Windows 11 machine

AC 2, by hand, with the `.msi` of the first release —
[`v0.1.248`](https://github.com/youndie/kachok/releases/tag/v0.1.248) (`9433fdd`), fetched with
`gh release download` — installed over `kachok 0.1.238`, read through `WScript.Shell` and the
process table rather than looked at:

- **Before:** `kachok.lnk` in the Start menu's `kachok` folder, none on the desktop
  (`[Environment]::GetFolderPath('Desktop')` is `C:\Users\<user>\Desktop`).
- **After the upgrade** (`msiexec /i … /qn`, exit 0, the log says *Product Version: 0.1.248*): a
  desktop `kachok.lnk` and the Start menu one, both to `%LOCALAPPDATA%\kachok\kachok.exe`, working
  directory beside it. So the upgrade does install the new shortcut component — the hypothesis the
  decision named holds.
- **Each starts the window:** the desktop shortcut, then — after the reinstall below — the Start
  menu one, opened the way Explorer opens them (`ShellExecute` on the `.lnk`). Both times a
  `kachok.exe` titled *kachok*, and the MCP server attached to it with all twenty torrents.
- **Uninstalling removes both** (`msiexec /x`, exit 0): no desktop `kachok.lnk`, no `kachok`
  folder in the Start menu, no `kachok.exe`; the torrents and settings in `%APPDATA%\kachok` stay,
  as a per-user uninstall should leave them.

All three ACs met. The answer in #27 is the owner's, with the release above.
