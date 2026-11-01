---
id: B-182
title: "oldge-ui answered every ask: take 0.1.1.30 and drop the workarounds"
status: wip
priority: P2
size: M
stage: phase-2-ui
---

# B-182 — oldge-ui answered the asks

B-176…B-181 filed eleven gaps in oldge-ui, youndie/oldge-ui#18–#28. They are all closed. Every one
is in `0.1.1.30`, built from youndie/oldge-ui@657aa2c. The oldge kit worked around each one from
outside. This item takes the release and puts each workaround back into the library:

- **Remove** in the remove dialog is `OldgeButtonVariant.Danger` with the `trash` icon (#19). It was
  the chrome *Secondary*, because the design system had no danger variant and its lime *Primary* is
  its "go".
- **`OldgeDensity.Pointer`** (#21). The kit set sizes from outside, first in the chain: a 36 dp icon
  button, a 36 dp search bar, a 26 dp banner button. Under `Pointer` the library draws pointer sizes
  itself: buttons at 28, 32 and 36 dp, the search bar and a text field at 32. The toolbar's controls
  are then 28 dp, the design's own size.
- **English accessible names** through `OldgeStrings` (#18, #28): *Close*, *Clear*, *Show password*,
  *Hide password*, *Progress*, and the rest. Before this, the clear orb was «Очистить».
- **`OldgeTextField(readOnly, showLabel)`** (#25). The folder fields are read-only rather than made
  unfocusable from outside, so a path can be selected and copied. The settings fields hide the label
  the row already shows, and the label is heard once. `wellOnly` goes.
- **`OldgeSegmented(enabled)`** (#26). A choice that cannot be changed is drawn disabled, not only
  deaf to the press.
- **`OldgeDialog(maxWidth)`** (#27). The dialog's 520 or 420 dp is a cap, not a fixed width, so a
  window narrower than that fits the dialog to it.
- **`OldgeIconButton(selected)`** (#20). The details and settings toggles are latched toggles. Before
  this they were *Primary*, which is the screen's one main action.
- **`OldgeProgressWell`** (#22). It replaces the hand-drawn copy of the bar's look. The fill stays in
  the row's colour. The well is now the skin's own, so a selected or failed row's track tint is the
  Material kit's only.
- **`OldgeSkin.id`** (#24). The choice is stored by id. A file written by an earlier build, which
  stores the skin's name, is still read.
- **`OldgeText(onTextLayout)`** (#23). The kit's `Text` falls back to `BasicText` only where a style
  has no line height to place on, not because of the callback.

AC:
- the kit names none of the workarounds above;
- Material goldens are byte-identical;
- the oldge goldens are re-recorded and inspected;
- the tests that pressed «Очистить» press *Clear*.

Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/oldge/OldgeKit.kt`,
`gradle/libs.versions.toml`.
