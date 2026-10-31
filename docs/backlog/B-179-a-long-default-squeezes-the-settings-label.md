---
id: B-179
title: "A long default value squeezes the settings label into a column of letters"
status: done
priority: P2
size: XS
stage: phase-2-ui
---

# B-179 — A long default squeezes the settings label

Seen in the live app on 2026-10-31, in both kits. With a long download directory, *Save to* in the
settings is drawn as a column — «Sa / ve / to» — one or two letters to a line.

The row is a weighted label column beside an unweighted control row (`SettingRow` in
`settings/Settings.kt`). The control row starts with `default ${setting.default}`, which for *Save to*
is the whole default path and is neither cut nor capped. The unweighted row is measured first and
takes what it needs, and the label gets what is left. The run used a long home directory under
`/private/tmp/…`, but a home path of ordinary length on a narrow window does the same.

- **The decision and its reason.** The label keeps its own width, and the default gives way:
  - a path default is drawn as a `PathText`, cut in the middle with the end kept, under a width cap;
  - the full path stays in its hint.

  The label is what says what the row is. The default only qualifies it.
- AC: with a default path of 120 characters at the narrowest window, *Save to* is on one line and
  the default ends in the path's last segment; a test asserts the label's width.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`.

## Done — 2026-10-31

**What exists.** A row whose setting is a folder draws its default as a `PathText` with the words
`default ` in front, under a cap of 160 dp, inside a `Hint` that carries the whole path. Every other
default is the plain text it was. `PathText` has a `prefix` that is never cut: the prefix and the
path stay one string, so a default that fits is drawn exactly as before.

The cap is set from the narrowest window, and `DEFAULT_PATH_CAP` gives the reason in its comment. At
600 dp the folder field and *Browse…* take 289 dp in Material and 292 in oldge-ui, which leaves about
245 dp for the label and the default. With 160 for the default, the label keeps 82 dp or more.
*Save to* needs 43 dp in Material's face and 50 in oldge's. 160 dp is 25 characters of the default's
mono, so the design's `default ~/Downloads` still fits whole.

**Goldens.** `viddikRecord` on the mac changed the five settings sheets and added two. Outside the
*Save to* default, every changed sheet is byte-identical. The changed pixels sit in one box per
sheet, 240 × 14 px, on that line.

- `settings_screen`, `-violet`, `-orange` (Material) and `-oldge`, `-crystal` (oldge-ui) now read
  `default …oundie/Downloads` where they read `default /Users/youndie/Downloads`. The sheets
  resolve the default on the recording machine, so it is the mac's home and not the design's
  `~/Downloads`. At 32 characters, about 202 dp, it is wider than the cap. A cap wide enough to keep
  it would leave the label 43 dp at the narrowest window, and oldge's *Save to* does not fit in 43.
  No other row moved.
- New: `settings_screen-long-default` and `-long-default-oldge`. They are 720 × 504, the smallest
  window, with a 120-character default. In both, *Save to* is on one line and the default reads
  `default …e-else/kachok-in` beside the field. The oldge folder field shows the *value* from its
  front (`/home/a-person-wi`), which is outside this item; see below.

**Verified.** `:ui:check` on the Linux box: 354 tests in 56 classes, no failures. `LongDefaultTest`
is five of them. At the 600 dp artboard, in both kits, they check four things:
- *Save to* is one line;
- its width is at least its one-line width;
- the default ends in the path's last segment and is cut;
- hovering the default shows the whole path.

The cases are the 120-character default and a 29-character one, `/private/tmp/kachok/Downloads`.
Mutations, each run after the commit and then reverted:
- the cap dropped: four fail;
- the old unbounded `Text` put back: the same four fail.

In both, Material on the 29-character path reports *Save to* broken into 5 lines, which is the live
defect. On the 120-character path the label is pushed off the row entirely, in both kits. The hint
test fails too, but only on its precondition (see below).

**Left out.** The oldge kit's `FolderField` draws the current directory with `OldgeTextField`, which
cuts it at the end. A long *value* therefore shows its front, `/home/a-person-wi`, and loses the
directory's name. The Material field uses `PathText` and keeps the end. This item's change is to the
default only, so the value is left as it is; it is a separate item if wanted. A `PathText` given no
width at all draws its whole path, clipped. It did that before this change, and only the mutations
reach it: they leave the Material folder field no width, so the whole path is on screen before any
hover.

