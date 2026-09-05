---
id: B-34
title: "Peer exchange (BEP 11, ut_pex)"
status: open
priority: P2
size: M
stage: m8-extensions
epic: feature-download
blocked_by: [B-10]
---

# B-34 — Peer exchange (BEP 11, ut_pex)

Peers that tell each other about peers — the cheapest peer source and, for private trackers
(BEP 27), one that must be switched off.

- **The decision and its reason.** Send `ut_pex` every 60 s with added/dropped lists in compact
  form; honour `private = 1` in the metainfo by not advertising the extension at all.
- Rejected: nothing to reject; the extension is small.
- Not covered: IPv6 `added6` until [B-38](B-38-ipv6.md).

- AC: two fake peers connected to the client learn of each other within a minute; a private
  torrent never sends `ut_pex`.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/wire/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`.
