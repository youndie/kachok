---
id: B-21
title: "The ten-second choker with optimistic unchoke"
status: open
priority: P1
size: M
stage: m5-seeding
epic: feature-seeding
blocked_by: [B-20]
---

# B-21 — The ten-second choker with optimistic unchoke

BEP 3's reference algorithm, from the session timer: every ten seconds unchoke the four interested
peers with the best download rate from us (upload rate, when we are a seed), keep one optimistic
unchoke that rotates every thirty seconds, choke the rest.

- **The decision and its reason.** Exactly the BEP 3 algorithm first; rates are 20-second rolling
  averages kept as primitive counters on the peer. Because it runs from the one timer, it is a
  pure function of the peer table and is tested as one.
- Rejected: a cleverer algorithm. The reference one is what the swarm expects and what makes the
  numbers in [B-19](B-19-end-to-end-download-acceptance.md) comparable to other clients.
- Not covered: anti-snubbing beyond "a peer that sent nothing for 60 s is not counted".

- AC: with eight interested fake peers and known rates, the pass unchokes the top four plus one
  optimistic; the optimistic changes at 30 s and not before; a seed ranks by upload rate.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/choke/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`.
