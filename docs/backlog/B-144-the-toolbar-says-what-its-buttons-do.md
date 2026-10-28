---
id: B-144
title: "The toolbar's icon buttons say what they do on hover, and Force re-announce is one of them"
status: done
priority: P3
size: S
stage: phase-2-ui
blocked_by: []
---

# B-144 — The toolbar says what its buttons do

Asked for by the owner: the icon buttons at the top of the window are a glyph and nothing else, and
a person cannot tell what one does without pressing it. Each `ToolbarAction` already carried its
`label` and, when greyed, its `disabledBecause` — but only in the semantics tree, where a screen
reader and a test find them. Reported as [youndie/kachok#87](https://github.com/youndie/kachok/issues/87).

- **The decision and its reason.** `Hint` is an `expect` composable; the desktop's `actual` is
  `TooltipArea`, after 500 ms, below the pointer so it does not cover the glyph it names, in the
  inverse surface so it reads against the bar. It shows `ToolbarAction.hint`: the label, and for a
  greyed control `label — reason`, so the reason a button is off is finally visible to the person
  it is for.
- **Force re-announce on the bar**, beside Force re-check: `ToolbarCommand.Reannounce`, which the
  window sends as the same `TorrentCommand.Kind.Announce` the Trackers tab's *Re-announce* sends.
  Greyed with a reason for nothing selected, a paused torrent (it has told its trackers `stopped`)
  and a magnet (there is no torrent to announce yet). The glyph is the Trackers tab's, `campaign`,
  already in the subset font.
- **The goldens change and were not re-recorded here.** The bar has a ninth control, so the main
  window's goldens differ from the recorded ones; they are recorded and verified on the mac
  (`:ui:viddikVerify` runs there only), which this change was not made on.

- AC: a hovered icon button shows its name, a greyed one its reason; Force re-announce reaches the
  window for a running torrent and is greyed with a reason otherwise. **Met.**
  **Automated:** `ui/src/desktopTest/.../main/ToolbarHintTest.kt`;
  `ui/src/desktopTest/.../main/ToolbarStateTest.kt` — `reAnnounceIsOfferedToARunningTorrentOnly`;
  `WiringTest#everyEnabledToolbarControlLeavesTheWindow` (every command, Reannounce included).
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/main/Toolbar.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/main/Hint.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/main/Hint.desktop.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`.
