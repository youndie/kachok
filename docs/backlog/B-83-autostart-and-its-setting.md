---
id: B-83
title: "Starting with the operating system, and the setting that says so"
status: open
priority: P3
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-81, B-82]
---

# B-83 — Starting with the operating system, and the setting that says so

A torrent client is worth starting with the machine, and this one has no way to be asked to. There
is no such setting and no mechanism behind it.

**None of this is a jpackage feature.** An installer can drop a shortcut in a startup folder, but a
*setting* a person can turn off has to be code the client runs, and each platform keeps the entry
somewhere different:

| | Where the entry goes | Written by |
|---|---|---|
| Windows | `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`, or a shortcut in `shell:startup` | the client, on the setting |
| macOS | `SMAppService.mainApp` on 13+, a `~/Library/LaunchAgents/*.plist` before that | the client |
| Linux | a `.desktop` file in `$XDG_CONFIG_HOME/autostart` | the client |

- **The decision this needs.** What "started" means. Starting the window on login is one answer and
  the intrusive one; starting *minimised*, or starting only the engine with no window, are the two
  a person who wants this actually wants — and the second needs somewhere for the window to come
  back from, which this client has no notion of.
- **And what the entry points at.** An app image lives wherever it was unzipped and an autostart
  entry that names that path breaks the first time the folder moves — which is why this waits on
  [B-82](B-82-an-installer-per-platform.md).
- Rejected in advance: writing the entry from the installer. Then turning the setting off would
  disagree with what is on the disk, and the setting screen would be describing something it does
  not control.
- Not covered: a tray icon, and closing to it rather than exiting. That is the other half of
  "runs in the background" and is its own decision.

**It waits on [B-81](B-81-the-torrent-list-survives-a-restart.md) for a plainer reason:** starting
with the operating system and coming up with an empty list is starting for no reason.

- AC: a checkbox in Settings turns it on and off; turning it on and rebooting starts the client;
  turning it off removes the entry, and the client leaves nothing behind when it is uninstalled.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/settings/Settings.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/StoredPreferences.kt`.
