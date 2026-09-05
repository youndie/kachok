---
id: design-tokens
title: The phase 2 design, as facts an implementation can be checked against
type: reference
---

# The phase 2 design

The design itself is `kachok Phase 2 Desktop.dc.html` beside this file — a document that renders
itself with React, imported from
`https://claude.ai/design/p/ba3e12d4-4b43-48aa-8d38-bc0e9f08f2ad`. It is the source; everything
below is read out of it so that the Compose implementation has something to be *checked* against
rather than remembered from.

`./scripts/capture_design.sh` renders it and cuts the seven screens into `screens/`. Those PNGs are
derived — regenerate them, never edit them — and they are what a viddik golden is compared with.

## 1. Colour roles (dark)

Read out of the document's own swatch row, which names each one.

| Role | Hex |
|---|---|
| `primary` | `#4FD9C2` |
| `primaryContainer` | `#005046` |
| `secondary` | `#B0CCC6` |
| `tertiary` | `#AEC6E8` |
| **`warning`** | `#F3C46B` |
| `error` | `#FFB4AB` |
| `surface` | `#0F1513` |
| `onSurface` | `#DDE4E1` |

**`warning` is the one role Material 3 does not ship**, and the design says so in a footnote: it
exists for *checking* and *stopping* only. Those are the two states that are neither healthy nor
broken — a re-hash in progress and a clean stop under way — and colouring them `error` would say
something false while colouring them `primary` would hide them.

Supporting values the document uses throughout, by frequency rather than by a named swatch:
`#889390` (labels, column heads), `#BEC9C6` (body, and the middle figure level of §5), `#151C1A`
(raised surface, and the hairline between two list rows), `#2A3331` and `#3A4442` (the hairlines
that replace elevation), `#E5A9A1` (a figure inside an error row), `#4A3608` (warning container),
`#4A2A27` / `#FFDAD6` (error container and its text), `#1F1614` (the error row's tint), `#3A2320`
(its progress track), `#4A5654` (a stopping row's bar).

**There are three text levels for figures, not two.** `onSurface` for the number the state is
about, `#BEC9C6` for a number that is merely true, `onSurfaceVariant` for a zero or an absent
value. Material 3 has no role for the middle one, and dropping it collapses a downloading row's
"4 312 down, 812 up" into two numbers of equal weight — the opposite of what the columns say.
Verified by reading the `color:` of all 28 row cells in the document.

## 2. Type

| Family | Used for |
|---|---|
| Source Serif 4 | headlines, dialog titles, the empty state |
| Archivo | every label, button and column head |
| JetBrains Mono | **every number, hash and verbatim error** |

The third row is the load-bearing one: the design puts every figure in a monospaced face with
tabular figures, so a row does not reflow when a speed changes at 1 Hz.

## 3. The calibration

Three global decisions, applied through the theme rather than per component:

* **shapes at 4 dp** — everything, including the dialog, which is 6 dp instead of M3's 28;
* **no elevation** — replaced by 1 dp `outlineVariant` hairlines. The one exception is
  `DropdownMenu`, allowed level 2;
* **every vertical dimension cut to a desktop number** — 28 dp rows, 48 dp toolbar instead of 64,
  32 dp tabs, 24 dp status bar against `BottomAppBar`'s 80 dp minimum, which is an Android number.

A component not in the inventory below still comes out right, because the calibration is the theme.

## 4. What is custom, and why

| Element | Material 3 | Drawing | Why |
|---|---|---|---|
| Torrent list | `LazyColumn` + `ListItem` | **custom** | `ListItem` cannot do nine fixed columns; a `Row` of `Box` cells with fixed widths |
| Progress cell | `LinearProgressIndicator` | **custom** | 4 dp track, no M3-Expressive wave, no stop indicator: it redraws at 1 Hz |
| Peers · out cell | `Text` | **custom** | One `AnnotatedString`, three spans, tabular figures; the colour carries the state |
| Details panel | `Surface` + `VerticalDivider` | **custom** | Draggable splitter, 280–520 dp; a `ModalBottomSheet` under 800 dp |
| Degraded banner | `Snackbar` | **custom** | Docked and permanent, not floating and not dismissible on a timer |
| Drop overlay | — | **custom** | 1 dp dashed primary inset 8 dp over a `primaryContainer` scrim at 22 % |
| Status bar | `BottomAppBar` | **custom** | 24 dp |

Everything else is stock M3 with a calibration: `TopAppBar`, `FilledTonalButton`, `IconButton`,
`OutlinedTextField`, `SecondaryTabRow`, `AlertDialog`, `ListItem` + `Switch`, `DropdownMenu`, and
Material Symbols Rounded at weight 400, fill 0, optical size 20 — **fifteen glyphs in the whole
app**.

## 5. The seven row states

Each is a glyph *and* a colour, never colour alone, so the state column survives a monochrome
screenshot and a colour-blind reader.

| State | What it is allowed to say |
|---|---|
| Metadata | a magnet before `name` and `totalLength` exist: indeterminate bar, no size, no ratio, no ETA; the name column falls back to the info hash |
| Checking | `verifiedPieces / verifyingOf` — its own progress, in the warning role, so it is never mistaken for downloading |
| Downloading | peers connected, at least one unchoked, requests in flight |
| Seeding | `isComplete`; zero outstanding is correct here, ETA is ∞, and down is blank |
| Paused | **planned** — the engine has `Command.Stop` but no paused state; the row keeps its position |
| Stopping | up to ten seconds of announce, close, flush, record: numbers freeze while they and the actions grey out |
| Error | `sessionError` is non-null; only this one tints the whole row |

And what colour each cell of each state is drawn in — transcribed cell by cell from the row markup
of the main window, not inferred. `on` is `onSurface`, `fig` is the middle level `#BEC9C6`, `var`
is `onSurfaceVariant`; `warn` is `#F3C46B`, `pri` is `#4FD9C2`, `err` is `#FFB4AB` and `err-fig` is
`#E5A9A1`.

| State | glyph | name | size | bar | track | % | down | up | peers | ratio | eta | label |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Metadata | var | fig | var | pri @ 55 % | `#2A3331` | var | var | var | on | var | var | fig |
| Checking | warn | on | fig | warn | `#2A3331` | warn | var | var | var | fig | var | warn |
| Downloading | pri | on | fig | pri | `#2A3331` | on | on | fig | on | fig | fig | on |
| Seeding | pri | on | fig | pri | `#2A3331` | fig | var | on | on | fig | var | pri |
| Paused | var | var | var | var | `#2A3331` | var | var | var | var | var | var | var |
| Stopping | warn | on | fig | `#4A5654` | `#2A3331` | fig | fig | var | fig | fig | var | warn |
| Error | err | err | err-fig | err | `#3A2320` | err | err-fig | err-fig | err | err-fig | err-fig | err |

Three rules generate almost all of it, and the exceptions are the interesting part:

* A figure is `on` when it is what the state is about *and* non-zero, `fig` when it is merely true,
  `var` when it is zero or absent. A seeding row's `∞` is dimmed with the dashes rather than ranked
  with the numbers that change.
* **Paused drops the whole row** and **stopping demotes every headline to `fig`** — its numbers are
  the last ones the session saw, and a frozen figure that still looks live is a lie for up to ten
  seconds.
* An error row has its own two tones rather than the three: `err` for what says what happened —
  including a peers cell of `0/0 · 0`, because there a zero is the symptom rather than an absence —
  and `err-fig` for everything else.

## 6. What the design draws that the engine does not have yet

Marked `planned` in the design itself, and each needs an engine change before the screen is honest:
speed (down/up) and ETA, a per-peer list, a per-file list with selection, a per-tracker list, and
`paused` as a state distinct from stopped. The screens are built with the fields that exist and
these left visibly marked, rather than with invented numbers.
