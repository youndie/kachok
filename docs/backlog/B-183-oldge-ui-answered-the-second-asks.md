---
id: B-183
title: "oldge-ui answered the second asks: take 0.1.1.37 and drop what the kit still drew"
status: in-progress
priority: P2
size: M
stage: phase-2-ui
---

# B-183 — oldge-ui answered the second asks

After [B-182](B-182-oldge-ui-answered-the-asks.md) the oldge kit still drew six things from outside
the library. They were filed as youndie/oldge-ui#45–#50, and all six are closed. Every one is in
`0.1.1.37`, built from youndie/oldge-ui@618cef8. This item takes the release and drops each
workaround:

- **`OldgeDensity.Pointer` reaches `OldgeSegmented` and `OldgeTabs`** (#45). The segmented choice
  is 32 dp by itself, level with the settings fields, and the kit no longer sets its height. The
  current details tab is the design's 32 dp strip, and a tab at rest is 28.
- **`OldgeTextField(highlighted)`** (#46). A setting changed from its default is ringed in
  `accent-ink` by the field's own border. The kit no longer draws a second border over the well's
  edge with a copy of the library's corner radius.
- **`OldgeCheckbox` and `OldgeRadioOption` take a `description`** (#47). The dialogs' checkboxes and
  radio buttons are now kit components, `Check` and `Choices`. Under oldge they are the library's own:
  *Delete the downloaded data* has its directory under it, and *Sequential download* has what it
  costs the swarm. Pressing the note toggles the box. *Add paused* is the library's disabled option,
  with the reason under it. The library left the badge out and said why: a disabled option with its
  reason covers "shown, not offered yet". Under Material the drawing is the one the screens had,
  moved into the kit unchanged, badge included.
- **`OldgeText(AnnotatedString)`** (#48). The empty state's invitation, with its monospaced
  `.torrent`, is placed by the library's own `OldgeText`. It used to be placed by `BasicText`.
- **`OldgeProgressWell(well)`** (#49). A selected or failed row's bar is drawn in that row's track
  colour, so it belongs to the row. A plain row's track is the scheme's `divider`, and its well
  stays the skin's.
- **`OldgeTextField(overflow)`** (#50). At rest, a long folder path is cut from the front, as
  `PathText` cuts it, so the folder it names stays in sight. Focused, the whole path shows.

AC:
- the kit names none of the workarounds above;
- Material goldens are byte-identical;
- the oldge goldens are re-recorded and inspected;
- each answer taken back out fails a test.

Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/oldge/OldgeKit.kt`,
`ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/Kit.kt`,
`ui/src/desktopTest/kotlin/io/github/youndie/kachok/ui/theme/OldgeAnswersTest.kt`,
`gradle/libs.versions.toml`.
