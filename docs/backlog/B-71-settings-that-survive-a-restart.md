---
id: B-71
title: "Settings that survive a restart"
status: done
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

## The decision, taken

**A properties file in the platform's own config directory.** `~/Library/Application Support/kachok`
on macOS, `%APPDATA%\kachok` on Windows, `$XDG_CONFIG_HOME/kachok` or `~/.config/kachok` elsewhere.
`java.util.Properties` needs no parser, no dependency and no explaining to whoever opens it — the
resume records are bencode because a resume record is a BitTorrent thing, and a settings file is
not.

**Written to a neighbour and moved into place**, which is the engine's own rule for the resume
record: a file half-written by a process that was killed reads as nonsense on the next start, and
the move is the one operation the filesystem will not do halfway.

**Half a second of quiet before each write.** `LaunchedEffect` cancels the previous one when the
value changes, so typing `1200` into a rate limit is one write and not four.

**Recovered field by field, not all-or-nothing.** A file from an older build is missing keys rather
than wrong about them, and losing every setting because one is absent is the strictest possible
answer to the mildest possible problem.

**The bound port is not written down.** It is what the listener actually got, not what anybody asked
for; a client that lost 6881 to something else once would otherwise ask for 6882 for the rest of its
life.

## What the live check found

**A restored setting reached the screen and not the engine.** After a restart the settings screen's
DHT toggle was on — correctly, out of the file — and the status bar three lines below said *DHT
off*. `dhtWanted` only carries a *toggle*, and a preference read from a file was never toggled. The
set is now built with the stored answer, and the same window now says *DHT 8 nodes*. This is the
second time in one session that persistence or restoration produced two halves of a window
disagreeing; the first was the filter's empty state.

- AC: a changed setting is still changed after a restart; a corrupt or unreadable file is ignored
  with the defaults, the way a corrupt resume record is.
  **Automated:** `ui/src/desktopTest/.../session/StoredPreferencesTest.kt` — the round trip, the
  absent file, a file full of nonsense recovered per field, a file missing keys, the port that must
  not be saved, and the path that must not be beside the downloads. Checked by hand: 77 typed into
  *Connections to keep up* and the DHT switched on, the window closed and reopened, both back — and
  the status bar joining the DHT rather than merely claiming to.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/SettingsFrom.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/resume/`.
