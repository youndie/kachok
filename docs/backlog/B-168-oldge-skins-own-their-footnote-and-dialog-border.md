---
id: B-168
title: "The oldge skins draw the footnote band and the dialog border from their own tokens"
status: done
priority: P3
size: S
stage: phase-2-ui
---

# B-168 — The oldge skins' footnote band and dialog border

B-162 kept Toxic's two literals for these roles (`#111716`, `#303836`, inherited from the Material
design because no Toxic token produces those greys) and derived Media's and Crystal's by placing them
where the literals sit between two tokens. Measured, they come out almost equal to each skin's
`surface` and `line`, so the settings footnote band is told apart only by its hairline.

**The question for the owner.** Should these two roles come from oldge-ui's own vocabulary — for
example the footnote on `surface2` and the dialog border on `edge` — which changes Toxic's goldens,
or stay as they are? It is a design call, not a defect: nothing is unreadable.

- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/oldge/OldgeKit.kt`.

## Done — 2026-10-30

**The owner's answer (2026-10-30): from oldge-ui's own tokens, Toxic's goldens re-recorded.** Every
skin now draws the settings footnote band on its `surface2` and a dialog's border on its `edge`; the
two Material-design greys, and the lightness fractions B-162 derived the other skins from, are gone
from `OldgeKit`. `OldgeSkinsTest.theFootnoteAndTheDialogBorderAreTheSkinsOwn` holds both for every
skin; with the footnote put back on `surface` it fails (1 of the class's 4 tests), restored.

**Goldens.** Five changed, all of them oldge and all of them where these two roles are drawn:
`add_dialog-oldge`, `remove_dialog-oldge`, `remove_dialog-crystal`, `settings_screen-oldge`,
`settings_screen-crystal` — looked at: the footnote band now reads as a raised strip rather than
only by its hairline, and a dialog's frame is the skin's green edge on Toxic and on Crystal. The
Material, seeded and main-window goldens did not move.

**Verified.** `:ui:desktopTest` on the Linux box: 325 tests, 0 failures (B-162's two tests of the
derivation replaced by one). `make check` on macOS green, goldens included.

