---
id: B-112
title: "A peer interested for a few seconds is never unchoked: the choke pass is the only place an unchoke happens"
status: open
priority: P3
size: S
stage: m9-swarm
epic: feature-download
---

# B-112 — A peer interested for a few seconds is never unchoked: the choke pass is the only place an unchoke happens

A hypothesis from one observation, filed as one. In [B-110](B-110-this-client-never-uploads-a-block.md)'s
fourth public run, two of the four leechers connected in the first thirty seconds said
`interested` while this client held seventeen pieces — and no byte was served before both were
gone. The choker ([B-21](B-21-choking-algorithm.md)) runs its pass every ten seconds, as BEP 3
describes, and that pass is the only place a peer is unchoked; a peer whose interest lasts less
than one interval, or that arrives just after one, is never told it may ask. In a swarm where the
reachable leechers are few and churn fast, that interval is most of their stay.

Whether this is what happened is not known — the two might have been choked by the pass and left
for their own reasons, or found the pieces elsewhere in the same seconds. What is known is that
with the same code a leecher that *stays* is served in full within seconds (qBittorrent, twice).

- **The decision this needs.** Whether an unchoke slot can be given outside the pass — when a peer
  becomes interested and a slot is free — with the ten-second pass still deciding who *keeps*
  one. libtorrent does something like it; BEP 3 does not forbid it. The cost is a peer that gets a
  slot for a few seconds and is choked at the next pass, which is churn a swarm already has.
- **First, the measurement.** A run that logs, per peer, the time from `interested` to the first
  block served or to disconnect, so the hypothesis is either a number or nothing.
- Rejected in advance: shortening the pass. Ten seconds is what every client's rate estimate is
  built on; a faster pass ranks by noise.
- Not covered: the optimistic unchoke's own rotation, which is thirty seconds and is a different
  decision.

- AC: the per-peer measurement exists and is run on the same public torrent; if it shows interest
  routinely outliving nothing, the item is dropped with the numbers; if it shows the gap, a slot
  is offered on interest and the measurement is re-run with `uploaded` beside it.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/choke/Choker.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`.
