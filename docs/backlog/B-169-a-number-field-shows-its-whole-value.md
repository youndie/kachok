---
id: B-169
title: "A settings number field shows its whole value: \"no limit\" is not cut to \"no lim\""
status: wip
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
