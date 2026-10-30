---
id: B-169
title: "A settings number field shows its whole value: \"no limit\" is not cut to \"no lim\""
status: done
priority: P3
size: XS
stage: phase-2-ui
---

# B-169 — A number field shows its whole value

*Downloads at once* shows `no lim` in its field when the value is unset, in every kit
(`ui/src/desktopTest/snapshots/settings_screen.png`): the field is sized for a number, and the
placeholder that stands for "no value" is wider than the field. *Upload limit* beside it is wide
enough and shows `no limit`.

- **The decision and its reason.** Size the field for the widest text it will show, the placeholder
  included, rather than for its widest number — the placeholder is what a person sees first.
- Rejected: a shorter placeholder. "No limit" is what the setting means; `—` would read as missing.

- AC: the settings golden shows `no limit` whole in every field that can be unset.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`.

## Done — 2026-10-31

`Setting.absentAs` carries the words a field says when its value is absent (`no limit` for the two
rate limits and *Downloads at once*), and the number field is as wide as the design's width or as
those words measured in the field's own text style plus its padding and the caret, whichever is
wider. The width does not depend on whether the value is absent *now*, so clearing a field does not
move the row.

**Verified in the goldens.** The five settings goldens changed (`settings_screen`, `-violet`,
`-orange`, `-oldge`, `-crystal`), each at *Downloads at once* only: the field is a few pixels wider
and shows `no limit` whole where it showed `no lim`. The rate-limit fields, already wide enough,
did not move. `:ui:check` on the Linux box: 333 tests, 0 failures; `make check` on macOS green.

