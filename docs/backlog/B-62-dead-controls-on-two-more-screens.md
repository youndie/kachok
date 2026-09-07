---
id: B-62
title: "Controls on two more screens that reported nothing, and the guard that missed them"
status: done
priority: P1
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-56]
---

# B-62 — Controls on two more screens that reported nothing

[B-56](B-56-dead-toolbar-controls.md) fixed the toolbar and claimed to have fixed the *class*. It
had not. The settings screen contained **no interactive element at all** — every field, both
toggles and *Browse…* were drawings — and the add dialog's *Browse…* was a bordered box. Found by a
person with the application open for about a minute, on Windows, at the first thing they tried.

The same shape appeared three more times while fixing it, each one layer further out:

1. `SettingsScreen` had no callback at all.
2. `MainWindow` took `onSetting` and **never passed it on** — an unused parameter, which is not a
   warning, so `-Werror` could not see it either. `SettingsScreenTest` passed the whole time: the
   screen did report its press, and nothing asked whether anybody upstream was listening.
3. The add dialog's *Browse…* was given `clickable` in an edit that silently did not match the file
   and was not checked.

## What was done

- **Everything that can act, acts.** *Browse…* in both places opens a directory chooser; both
  toggles and the four numbers take an edit.
- **Everything that cannot says so on its own row.** The listening port is bound when the process
  starts and the DHT's socket and routing table are built with the session set, so both are drawn
  as text with *Not changeable here — …* under the label rather than as fields that swallow typing.
- **A setting reaches the next torrent**, through `Preferences.runtimeOptions()`. Nothing reaches a
  *running* one; that is the screen's own footnote and still `planned`.
- **The default directory is now real.** `~/Downloads` is resolved and `main` starts there, because
  a default printed on a row that nothing uses is a lie printed on every row.
- **`WiringTest` is the guard the class actually needed**: every callback `MainWindow` takes is
  exercised *through* `MainWindow` — the toolbar, a column head, a row, a details tab, the settings
  screen's changes and the dialog's *Browse…*. A screen's own test cannot see a dropped parameter;
  this can.

## Not verified

The Windows directory chooser. `chooseDirectory` is two implementations — `apple.awt.fileDialogForDirectories`
on macOS, `JFileChooser` in `DIRECTORIES_ONLY` everywhere else, because neither picks a folder on
both — and the mac half was driven by hand. The Swing half cannot be: the only Windows machine here
is reachable over ssh, which has no desktop, and a `HeadlessException` is all it can report.

- AC: no control on any screen is drawn as interactive and connected to nothing; every callback the
  window exposes is proved to arrive.
  **Automated:** `ui/src/desktopTest/.../main/WiringTest.kt` and
  `ui/src/desktopTest/.../settings/SettingsScreenTest.kt`.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/ChooseDirectory.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`.
