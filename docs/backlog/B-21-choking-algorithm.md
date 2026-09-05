---
id: B-21
title: "The ten-second choker with optimistic unchoke"
status: done
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

- AC **met 2026-09-05** (`ChokerTest` 8 tests, `RateMeterTest` 5, `SessionTest` ×2): with eight interested fake peers and known rates, the pass unchokes the top four plus one
  optimistic; the optimistic changes at 30 s and not before; a seed ranks by upload rate.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/choke/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`.

**Closed 2026-09-05.** The algorithm is BEP 3's, as a pure function of the peer table — it knows no
clock, because when a pass happens is the one timer's business and that is also what makes every
rule testable without waiting.

Three things worth keeping:

* **The ranking test asserts an invariant, not a winner.** The optimistic peer is chosen at random,
  so a test naming which peer should be unchoked is really testing the random seed — the first
  version of it did exactly that and failed. What must hold is that no choked peer beats an
  unchoked one on the metric in force, and that holds whoever the wildcard picks.
* **Only differences are sent.** A `choke` to a peer that is already choked says nothing, and fifty
  of them every ten seconds is a client that talks more than it listens.
* **Interest unchokes nobody by itself.** It changes what the next pass will decide. The
  placeholder from [B-20](B-20-upload-read-path.md) — unchoke on the peer's word — is gone, and the
  test that covered it now asserts the opposite.

**Not implemented, and it is a real difference from the reference:** BEP 3 says "new connections
are three times as likely to start as the current optimistic unchoke as anywhere else in the
rotation". The rotation here is uniform. The weighting exists to give a new peer a chance to prove
itself before the rate ranking can see it, and without it a fresh connection waits longer than the
specification intends.

`RateMeter` is the choker's input: a rolling window of one-second buckets, because the question is
what a peer is doing *now*. A running total would keep a peer that was fast an hour ago ahead of
one that is fast today.
