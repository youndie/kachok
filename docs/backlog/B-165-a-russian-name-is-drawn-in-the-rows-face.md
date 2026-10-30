---
id: B-165
title: "A Russian torrent name is drawn in the row's own face under the Material kit"
status: wip
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
