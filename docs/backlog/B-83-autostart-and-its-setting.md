---
id: B-83
title: "Starting with the operating system, and the setting that says so"
status: done
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
- Anchors: [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/Autostart.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/Autostart.kt),
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/SettingsFrom.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/SettingsFrom.kt),
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt).

## Done

**"Started" means the window, minimised.** Of the three, starting it in front of whatever somebody
was doing is the intrusive one, and starting only the engine needs somewhere for the window to come
back from — a tray, which is this item's own not-covered half. Minimised is both what a person who
asks for this wants and something the client can already do: the entry passes `--autostart` and
`main` opens the window minimised when it sees it. Before that flag existed the same argument would
have been read as a torrent path, so `main` now separates flags from paths.

**What the entry points at is `jpackage.app-path`**, which the packaged launcher sets and nothing
else does — so it is also the answer to "is this an installed build". Without that check a
`:ui:run` would write an entry naming a `java` in a Gradle cache, and the person would find out at
their next login, which is the hardest possible place to connect the failure back to the checkbox
that caused it. That the property is set at all is checked by
[B-78](B-78-nothing-runs-the-packaged-application.md)'s preflight, inside the packaged artifact,
because nothing that runs from Gradle can tell whether the launcher still sets it.

| | Written | Read back |
|---|---|---|
| macOS | `~/Library/LaunchAgents/ru.workinprogress.kachok.plist`, `RunAtLoad` | the file is there |
| Windows | `reg add HKCU\…\Run /v kachok /d "<launcher>" --autostart /f` | `reg query` exits 0 |
| Linux | `$XDG_CONFIG_HOME/autostart/kachok.desktop` | the file is there |

**The system is the authority, not the settings file.** The checkbox reads the entry when the window
opens rather than the `autostart=` line, because somebody can remove a launch agent or a Run key
without this client — and a checkbox that reported the file would then be wrong in the one direction
that matters, claiming the client starts with the computer when it does not. A refusal puts the
toggle back and says why *on the row*, for the same reason.

`HKCU` and never `HKLM`: this is one person asking for their client to start, and the machine-wide
key needs the administrator and would start it for everybody who logs in.

**A deliberate deviation from the design.** The settings screen has four sections — `DOWNLOADS`,
`NETWORK`, `LIMITS`, `PRIVACY` — and this adds a fifth, `STARTUP`, between the last two. The item
asks for the checkbox and the design predates it; hiding an application-wide switch inside
`DOWNLOADS`, beside *start torrents when added*, would have been the cheaper edit and the more
confusing screen. The golden is 760 tall rather than 640 for the same reason: at the design's height
the picture stopped one section short, and a golden that cannot see the last row cannot notice it
changing.

Not covered, as filed: a tray icon and closing to it. Also not covered: rebooting to prove it — the
acceptance criterion's middle clause is a machine restart, and what is asserted instead is the file
the system reads and its exact contents.

**Automated:** `ui/src/desktopTest/.../session/AutostartTest.kt` — thirteen cases across the three
platforms, with the launcher, the home directory and `reg.exe` all as parameters, so the suite
writes into a temporary directory and never starts this client on the next login of whoever ran it ·
`ui/src/desktopTest/.../settings/SettingsScreenTest.kt`, which asserts the row asks for the change
and that a refusal replaces the note on that row ·
`ui/src/desktopTest/.../settings/SettingsSheet.kt`, the golden.
