---
id: B-112
title: "A peer interested for a few seconds is never unchoked: the choke pass is the only place an unchoke happens"
status: dropped
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

## Iteration 1 — 2026-09-18: measured, and dropped with the numbers

**The instrument.** `SessionState.interestOutcomes` counts, for every peer that said `interested`
and then left on its own, what came of it: `served`, `unchoked, never asked`, `left choked inside
one pass` (gone within `chokeInterval` of its interest, so possibly never seen by a pass), `left
choked after a pass` (seen, and passed over). A peer never interested is not counted — it is most
of a swarm and says nothing about the choker — and neither is one this client hung up on at stop,
which was not given the chance to stay. The `download` summary prints the map beside the
disconnect reasons. `SessionTest` drives one peer through each of three buckets with one unchoke
slot and the test scheduler's clock.

**The numbers.** The same public torrent as B-110's runs, 200 s each, `--down 1500 --up 800`,
the build machine behind its NAT:

| run | leechers connected (range) | want ours | interested peers that left | outcome |
|---|---|---|---|---|
| 1 | 2–6 | 0–1 | 1 | `unchoked, never asked` |
| 2 | 1–15 | 0–1 | 0 — the one stayed, and was served (`up 131072`) | — |

Two hundred seconds twice, one departure from an interested peer in total, and it had been
unchoked. Neither "left choked" bucket was hit once. The hypothesis — that interest here
routinely outlives no pass — has nothing under it: the interested peers this client reaches are
one at a time, they stay, and the pass reaches them. What the four B-110 runs saw was the same
thing seen without the instrument: a leecher or two, briefly, and no bytes because nobody wanted
any, not because the choker withheld them.

**Dropped, per the acceptance's first branch.** A slot offered on interest would be a change to
BEP 3's rotation made for a case two runs could not produce. The counter stays: it is cheap, it is
on the summary line, and if a run ever shows the `left choked` buckets filling, this item's
decision section is what to reopen.

- The sample is one peer. That is the finding, not a limitation of the instrument: the reachable
  part of this swarm holds that few leechers who want anything from a client that has 3 % of the
  file. A run from a seed, or from a machine peers can dial ([B-103](B-103-upnp-and-nat-pmp-port-mapping.md)
  on a network that allows it), would be the way to a larger one.
