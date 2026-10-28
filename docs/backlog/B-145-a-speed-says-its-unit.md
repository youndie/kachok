---
id: B-145
title: "A speed says its unit: scaled B/s, KiB/s, MiB/s instead of a bare number of KiB/s"
status: done
priority: P3
size: S
stage: phase-2-ui
blocked_by: []
---

# B-145 — A speed says its unit

Asked for by the owner: in the list the speed is unreadable. `DOWN KIB/S` and `UP KIB/S` printed
`Figures.rate`, a whole number of KiB/s grouped in threes — `5 812` for 5.7 MiB/s, `0` for a peer
sending 900 bytes a second — with the unit only in a narrow upper-case head. That is what the mockup
drew, and read in use it was the one figure nobody could take in at a glance. Reported as
[youndie/kachok#88](https://github.com/youndie/kachok/issues/88).

- **The decision and its reason.** `Figures.speed` is a byte size per second by the rule every size
  in the window already follows, three significant figures and the unit chosen per value:
  `5.70 MiB/s`, `48.2 KiB/s`, `900 B/s`, `0 B/s`. Bytes and not bits, as qBittorrent and Transmission
  show it; a tariff's Mbps is a different question nobody asked this window.
- **The same string everywhere a rate is drawn**: the list's two columns, now `DOWN SPEED` and
  `UP SPEED`, the status bar, the tray tooltip (which reads the status bar's strings), the details
  panel's *Speed down / up* and the Peers tab's `SPEED` column.
- **Widths.** `48.2 KiB/s` is ten figures of JetBrains Mono at 12 sp, so the list's columns are 78 dp
  where the design has 70, and the Peers tab's is 70 where it had 42. The narrow layout drops whole
  columns before the name goes under its minimum and needed nothing else.
- **Sorting is untouched**: it sorts the rates, never the strings.
- **Muted zero** compares against `Figures.NO_SPEED`, which is `speed(0)`, rather than a literal `"0"`
  that would have silently stopped matching.
- **The goldens change and were not re-recorded here**: every row's figures differ. They are verified
  on the mac only (`:ui:viddikVerify`).

- AC: every rate in the window prints its unit, scaled; sorting by speed still sorts by number. **Met.**
  **Automated:** `ui/src/desktopTest/.../session/FiguresTest.kt` — `aSpeedIsASizePerSecondWithItsUnit`;
  `SessionRowTest`, `WindowFromTest`, `DetailsFromTest`, `PeersTabTest`, `RowColorsTest` on the new
  strings; `SortingTest` unchanged.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/session/Figures.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/main/ColumnHeader.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/list/TorrentRow.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/details/DetailsPanel.kt`.
