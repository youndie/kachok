---
id: B-66
title: "The filter field, which is a box with the word Filter in it"
status: open
priority: P1
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-62]
---

# B-66 — The filter field

The toolbar draws a 220 dp outlined box with a magnifying glass and the word *Filter*. It takes no
input and has no callback: `FilterField(text: String)` renders a `String` the state hands it.

- **The decision this needs.** What it filters on. The name is obvious; the design's own list also
  makes *state* worth filtering — "show me what is stalled" is the question a person with sixteen
  torrents actually has, and it is not a substring of anything.
- Rejected in advance: filtering the `TorrentSet`. The list is a view; a filter that removed a
  torrent from the set would stop downloading it.
- Not covered: saved filters, and whether an empty result says so.

- AC: typing narrows the list to matching rows and clearing it restores them; the status bar keeps
  counting every torrent, not the visible ones.
- Anchors: `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/Toolbar.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`.
