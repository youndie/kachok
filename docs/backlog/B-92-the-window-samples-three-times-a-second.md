---
id: B-92
title: "The window samples three times a second, off the thread that draws it"
status: done
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-92 — The window samples three times a second, off the thread that draws it

Two reports from a real Windows machine on 2026-09-07: clicking a row in the list takes a moment
before the panel follows, and the interface should refresh at 300 ms rather than 1 000.

## The click, which is not what it looked like

`Client` was measured before answering. With the composition's clock **frozen** — so that a delay
could not be mistaken for a test that simply waits — a click on a second row moves the details panel
within **two frames**. So the composition is not waiting for the sample, and
[B-64](B-64-a-click-waited-for-the-tick.md)'s fix still holds: what a person decides is read in
composition and only what the engine says comes from the tick.

**What was found instead is the cost of the tick itself, on the thread that handles the click.** The
sampling loop lives in a `LaunchedEffect`, which runs on the UI thread, and every tick it read a
state flow per torrent and walked *every file of every torrent* — `runtime.paths` twice per torrent,
once for the add dialog's collision map and once for the file paths, each call building a fresh list
of strings that is thrown away. At one second that is invisible. It is also exactly the shape of
thing that makes a click land late when it arrives while the sampler is running, and tripling the
rate would have tripled it.

So: the snapshot is built on the engine's dispatcher and only the assignment happens back on the
composition. `paths` is asked for once per torrent instead of twice.

## The rate

300 ms. The second was chosen against the *rates* — "redrawing faster shows noise" — and that
reasoning does not survive being looked at: `downBytesPerSecond` is the engine's own figure over the
engine's own window, so sampling it more often reads the same smoothed number more often rather than
a jumpier one. What the second did cost was everything that is *not* a rate — a piece count, a peer
count, a percentage — sitting up to a second stale on a screen somebody is watching.

## And a defect the rate made worse

The list was `itemsIndexed` with **no key**, so a lazy list identified a row by its position: sorting
by a column head handed every row's state to a different torrent. It is keyed by name now. Three
samples a second is three times as many chances for a reordering sample to do it, which is how a
latent bug becomes a visible one.

- AC: a click moves the selection without waiting for a sample, measured with the clock stopped; the
  window's own thread does not walk the file list of every torrent on every tick.
- Anchors: [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt),
  [`ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/MainWindow.kt`](../../ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/main/MainWindow.kt).

**Automated:** `ui/src/desktopTest/.../session/SelectionTest.kt` — the panel follows a click within
two frames of a stopped clock, which is the claim a running window cannot be asked about. Not
automated: that the perceived delay is gone, which is a person watching a screen; what is measured
here is the work removed from the thread that draws it.
