---
id: B-168
title: "The oldge skins draw the footnote band and the dialog border from their own tokens"
status: question
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
