---
id: B-179
title: "A long default value squeezes the settings label into a column of letters"
status: open
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
