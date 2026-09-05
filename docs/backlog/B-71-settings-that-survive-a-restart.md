---
id: B-71
title: "Settings that survive a restart"
status: open
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-62]
---

# B-71 — Settings that survive a restart

`Preferences` is held in the composition and lost on exit. A person who moves the download folder,
turns the DHT on and sets a rate limit finds all three back at their defaults next time.

- **The decision this needs.** Where the file goes and what is in it. The platform answer differs —
  `~/Library/Application Support`, `%APPDATA%`, `$XDG_CONFIG_HOME` — and the engine has an opinion
  about none of them; the resume records live beside the data instead, deliberately.
- Rejected in advance: putting it beside the download directory. The download directory is one of
  the settings, and a settings file that moves when you change a setting is one you lose.
- Not covered: the list of torrents, which is a different file and the harder half of "the client
  comes back where it was".

- AC: a changed setting is still changed after a restart; a corrupt or unreadable file is ignored
  with the defaults, the way a corrupt resume record is.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/session/SettingsFrom.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/resume/`.
