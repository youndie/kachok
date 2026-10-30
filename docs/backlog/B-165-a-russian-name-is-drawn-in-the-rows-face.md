---
id: B-165
title: "A Russian torrent name is drawn in the row's own face under the Material kit"
status: done
priority: P2
size: S
stage: phase-2-ui
---

# B-165 — A Russian name in the row's own face

Found while building B-160, by reading each bundled font's character table: **Archivo, the face of
every label, row name and button, has no Cyrillic at all** (0 of the 256 code points; JetBrains
Mono has 98, Source Serif 4 has 150). Under the Material kit a Russian torrent name is therefore
drawn in whatever fallback face the operating system supplies — a different face from the rows
around it, and a different one on each OS. The oldge kit already solves this for its own fonts by
swapping Archivo for DejaVu Sans Condensed; the default kit, which every user has, does not.

- **The decision and its reason.** Give the Material kit's Archivo a Cyrillic companion that is
  bundled, not borrowed from the OS — so a golden of a Russian name means the same picture on every
  machine — chosen by metrics close to Archivo's (x-height, width), and joined per script run, not
  by substituting the whole name.
- Rejected: replacing Archivo. It is the design's face for Latin, which is most of what the window
  shows.
- Not covered: the oldge kit (done in B-160), Source Serif and JetBrains Mono (they have Cyrillic).

- AC: a Material-kit golden with a Russian torrent name shows it in the bundled companion face, the
  same on macOS and in the Linux render; the existing goldens without Cyrillic are unchanged.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/Type.kt`.

## Done — 2026-10-30

**Onest 2.001 is Archivo's Cyrillic.** Five SIL OFL 1.1 families with the whole Russian alphabet,
Ё included, were taken from `google/fonts` and measured with fontTools at weight 400 (each variable
font instantiated at `wght=400`, Archivo also at `wdth=100`), in units of the em; stems are the
width of `l` (of `I` where `l` has a tail or `I` serifs) at the three weights the kit uses, and the
width is the advance of `Master i Margarita (1994).mkv` in each face's own Latin, which says how
wide its letters are drawn against Archivo's:

| Face | x-height | cap height | stem 400 / 500 / 600 | Latin name width | Cyrillic code points | `wght` | Variable TTF |
|---|---|---|---|---|---|---|---|
| **Archivo 2.001** (the row's face) | 0.526 | 0.686 | 0.088 / 0.104 / 0.122 | 13.24 em | 0 | 100–900 | 659 KB |
| **Onest 2.001** | **0.527** | 0.707 | **0.085 / 0.103 / 0.122** | 13.71 em (+3.5 %) | 162 | 100–900 | 193 KB |
| Inter 4.001 | 0.546 | 0.728 | 0.088 / 0.107 / 0.127 | 14.19 em (+7.2 %) | 248 | 100–900, `opsz` | 877 KB |
| Golos Text 2.004 | 0.530 | 0.700 | `I` 0.094 / 0.120 / 0.149 | 14.34 em (+8.3 %) | 170 | 400–900 | 184 KB |
| Manrope 4.505 | 0.540 | 0.720 | 0.073 / 0.089 / 0.105 | 13.73 em (+3.7 %) | 104 | 200–800 | 165 KB |
| IBM Plex Sans 3.201 | 0.516 | 0.698 | `l` 0.156 (tailed; `I` serifed) | 13.57 em (+2.5 %) | 192 | 100–700, `wdth` | 537 KB |

Onest has Archivo's x-height to a thousandth of the em and its stems at all three weights, so a
Russian word is as tall and as dark as the Latin beside it; the 3.5 % of width is the closest of the
faces that also match the first two. Plex is a little narrower but a humanist face with a serifed
`I` and a Reserved Font Name ("Plex") a cut-down copy could not keep; Manrope is a step lighter;
Golos Text and Inter are wider and, Golos, heavier at 600.

- **Source**: `https://raw.githubusercontent.com/google/fonts/4c1db3aec83c67dd223dd82a68c039cab30917a9/ofl/onest/Onest%5Bwght%5D.ttf`
  (`google/fonts` commit `4c1db3a`, "Onest: Version 2.001 added"; upstream `simpals/onest` release
  2.001), sha256 `966c5c29b4755da84b6854d5c21dd4eaa2420225d0e9874de602de176d4a9f31`, a TrueType
  variable font with one axis, `wght` 100–900.
- **Licence**: SIL Open Font License 1.1, no Reserved Font Name. The repository ships no licence
  files beside its fonts; the copyright (name ID 0), the licence notice and its URL (13, 14) are kept
  in the subset's name table, which is how the OFL lets the notice travel inside the binary.
- **What is bundled**: `ui/src/desktopMain/resources/fonts/OnestCyrillic.ttf`, **42 696 bytes** —
  the Cyrillic block (U+0400–U+04FF, 162 code points) and the combining marks (U+0300–U+036F), still
  variable. It has no Latin, no digits and no space. Its vertical metrics are Archivo's (878 / −210 /
  0, and the `win` pair), copied because a line's height is the tallest of the faces on it and
  Onest's own (970 / −305) would stand a dialog line with Russian in it apart from its neighbours.
  `scripts/subset_cyrillic_font.py` makes the file from the source above, refuses another source,
  and writes the same bytes on every run.
- **`ArchivoCyrillic`** in `Type.kt` is the family, at Archivo's three weights.

**Joined per run** (`ui/theme/material/CyrillicRuns.kt`). The Material kit's `Text`, both
overloads, asks `withCyrillicCompanion` for the text in the resolved style's family; when the face
is Archivo and the text has Cyrillic, it gets the text back with a `SpanStyle(fontFamily =
ArchivoCyrillic)` over each run, existing spans kept. **A run is Cyrillic letters only**, with the
combining marks that follow them; spaces, digits and punctuation — between two Russian words too —
stay Archivo's, because the companion has none of them (a space handed to it would be drawn by the
system's fallback) and Archivo's word space is the one the Latin names have. So
`Мастер и Маргарита (1994).mkv` is three companion runs, `Мастер`, `и`, `Маргарита`, and
`(1994).mkv` is the same Archivo as the next row's extension. Which face a character is in is the
last span naming a family over it, or the style's: a JetBrains Mono span inside an Archivo text keeps
its Cyrillic, an Archivo span inside a mono text is joined. A text with no Cyrillic takes the String
path it always took. Only the Material kit changed.

**Goldens.** Two added, both Material: `main_window-cyrillic.png` (the design's window with
`Мастер и Маргарита (1994).mkv` as the third row, between `e4f2c1a9d3b7…6f08` and
`Sintel-2010-1080p-DCP.tar`) and `remove_dialog-cyrillic.png` (both answers of the remove dialog
about that name). `viddikRecord` on the mac changed none of the 24 existing goldens — the five oldge
goldens with the same name included. Looked at: the Russian words stand on Archivo's baseline at its
x-height and its weight, and read as one name with `(1994).mkv`; they are a little wider than
Archivo's Latin would be (`М` and `и` most), the 3.5 % above, which is visible beside a Latin row
only by looking for it. Against `remove_dialog.png` the only pixels that differ are the two name
lines (rows 132–146 and 440–454); the size line under each name is identical, so the Cyrillic run
did not change the line's height.

**The companion is what draws it**, not a fallback: the same sheet rendered on the mac with the
companion switched off draws other glyph shapes and ends 3 px earlier on the dialog's name line.

**macOS and Linux.** The goldens are verified on macOS only (`viddik { verifyOnCheck }`), and this
did not change that. The two new sheets were rendered once on the Linux build machine — `viddikRecord`
in a copy of the tree outside the mutagen replica, nothing from it committed — and the name is
drawn in the same face there: the same glyph shapes and the same ink extent on the dialog's name
line (x 15–249 in both), the pixels differing only by each rasteriser's antialiasing, as every other
golden does between the two machines.

**Tests.** `CyrillicRunsTest` (6): a Latin name unchanged; the Russian name's words, and only its
words, in the companion; a stress mark with its letter; JetBrains Mono, Source Serif and no family
untouched; a mono span keeps its Cyrillic and every span is kept; the bundled file has all 66 Russian
letters and no Latin letter, digit, space or bracket. Mutation-checked on the Linux box: one span over
the whole name failed the mixed-name test; spaces joining a run plus the face of a span ignored failed
it and the span test (6 run, 2 failed); restored, all pass. `:ui:check` on the Linux box: 333 tests,
0 failures.
