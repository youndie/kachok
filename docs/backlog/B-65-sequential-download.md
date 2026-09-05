---
id: B-65
title: "Sequential download, which the add dialog offers and the picker does not do"
status: open
priority: P3
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-65 — Sequential download

The add dialog draws the checkbox, with the design's own explanation under it — *ask for pieces in
order rather than rarest first; slower overall, and it makes this client a worse swarm member* — and
the design's `planned` badge beside it. The engine picks rarest-first and has no other order.

- **The decision this needs.** Whether it is a session-wide mode or a window at the head of the
  file. Strict order is the simplest thing to implement and the worst thing for the swarm: every
  peer asks for piece 0 first, nobody has anything rare to trade, and the client that does it
  finishes last. The usual compromise is rarest-first with a sequential window of N pieces ahead of
  what has been played, which needs somebody to say what N is.
- Rejected in advance: making it the default. The picker's cost was measured rarest-first
  (research §1.2c) and every number in that section assumes it.
- Not covered: streaming — telling a player where the readable prefix ends — which is the reason
  anybody wants this and is a second interface on top of it.

- AC: a torrent added with the box ticked requests pieces in order; the box is no longer `planned`;
  the swarm cost of doing it is measured on the local swarm and written into the research beside
  the rarest-first numbers.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/picker/PiecePicker.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/add/AddTorrent.kt`.
