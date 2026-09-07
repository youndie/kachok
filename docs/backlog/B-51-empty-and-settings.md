---
id: B-51
title: "The empty state and the settings screen"
status: done
priority: P2
size: S/M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-48]
---

# B-51 — The empty state and the settings screen

- **The decision and its reason.** Settings shows **the measured default beside every field**,
  which is the design's own rule and this project's: the defaults in `SessionConfig` were measured
  (research §1.2c, §1.2d), so a blank field would throw that away — somebody changing the pipeline
  depth should be able to see what it was.
- **`settingsOf` reads `SessionConfig()` for those defaults rather than repeating them.** A default
  written down twice is a default that goes stale the first time a measurement moves one, and the
  test asserts against the config itself so the two cannot drift apart quietly.
- **The DHT toggle is the only setting with a paragraph.** A switch that announces this machine's
  address to strangers earns an explanation next to it, not in a help page.
- **A limit of `no limit` is not a limit of zero.** `SessionConfig` spells it `0`; the field says
  the words, dimmed, because they are an absence rather than a value somebody chose.
- **The empty state replaces the column header and the list, and takes the details panel with it.**
  Nine column heads over nothing is a table that looks broken; a ring, a sentence, a button and the
  two keystrokes is a place to start.
- Rejected: a preferences window per platform. One screen, in the window, where the toolbar's
  toggle can say whether it is open.
- Not covered: applying a change to a running session, which the design marks `planned` and the
  footnote repeats. Nothing on this screen is editable yet; it draws what the engine would use.

## Deviations, and why

- **The empty window keeps the whole toolbar.** The design draws it with only *Add torrent* and
  *Settings*. Keeping all seven and letting them grey is the same choice the design itself makes
  for *Resume* — a toolbar that reshuffles between "no torrents" and "one torrent" is the churn
  that greying exists to avoid.
- **And the whole status bar.** The design's empty one says `idle · DHT off · port 6881 listening`
  and drops the rates and the heap. Those are the *process's* state rather than the list's, and the
  heap number is the one that says the 128 MiB budget still holds whether or not anything is
  downloading.
- **`Save to` is a 210 dp field with a folder and a Browse button**, as the design draws it — the
  one setting whose control is not a number box, and the reason `Setting` has a `folder` flag.

- AC: goldens against `docs/design/screens/empty-state.png` and `settings.png`; every field's
  default label equals the value in `SessionConfig`, read from it rather than typed.
  **Automated:** `ui/src/desktopTest/.../session/SettingsFromTest.kt` — the defaults compared
  against `SessionConfig()` itself, so a measured value that moves moves both sides — plus the
  goldens `settings_screen.png` and `main_empty.png`, and `MainWindowTest` asserting both screens
  are reachable from the window rather than only from a golden.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/settings/`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/SettingsFrom.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/main/EmptyState.kt`.
