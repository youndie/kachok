---
id: B-66
title: "The filter field, which is a box with the word Filter in it"
status: done
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

## The decision, taken

**The name or the state, one rule, no syntax.** A person with sixteen torrents asks two things of a
list — *where is the Debian one* and *what is stalled* — and the second is not a substring of any
name. Matching the state's own label answers it without a second control: `paus` finds the paused
ones because that is what their rows say.

Rejected as well as the set: matching the size, rate and ratio cells. They are numbers wearing
units, and `1` would match `1.20 KiB`, `24 988` and `0.14` at once — a filter that matches
everything is a filter nobody trusts.

## What the live check found

**An empty result said the client was empty.** With `ubuntu` typed the window drew the design's
*Nothing downloading — drop a `.torrent` anywhere in this window* while its own status bar three
lines below said *1 torrent, 1 seeding*. Two halves of one window disagreeing is exactly what
`MainWindowState` is written to prevent, and no test would have found it: each half was right on its
own. A filtered empty list now has its own state — what was typed, how many rows are hidden, and a
button to clear it.

`TorrentRowModel` became a data class in passing: which row is selected is decided *after* the list
is built and filtered, so `copy(selected = …)` replaces threading an index through three
`mapIndexed` calls that have to agree with each other.

- AC: typing narrows the list to matching rows and clearing it restores them; the status bar keeps
  counting every torrent, not the visible ones.
  **Automated:** `ui/src/desktopTest/.../session/FilteringTest.kt`,
  `MainWindowTest.anEmptyFilterResultSaysSoRatherThanSayingTheClientIsEmpty` and
  `anEmptyClientIsStillTheDesignsEmptyState`, `WiringTest.theFilterFieldLeavesTheWindow`. Checked by
  hand: `seed` kept a row whose name does not contain it, `ubuntu` emptied the table while the
  status bar went on counting, and *Clear the filter* brought it back.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/main/Toolbar.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`.
