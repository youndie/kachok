---
id: B-123
title: "Every seed on the stand holds everything, so nothing on it can be rare"
status: open
priority: P2
size: S
stage: m7-measure
blocked_by: []
---

# B-123 — A seed that holds part of the torrent, at a stated rate

`SeedingPeer` takes `content` and serves all of it. Every stand this repository has — `LocalSwarm`,
`SwarmHost`, the CLI and UI end-to-end tests — is therefore one peer holding the whole torrent, and
on such a stand **no piece is rarer than any other**. [B-65](B-65-sequential-download.md) closed on
exactly this and said so rather than publishing a number:

> A swarm cost needs a swarm. The local stand is one seeder that has everything, where no piece is
> rarer than any other and rarest-first and sequential make the same requests in a different order —
> so a figure taken from it would be a number that measured nothing, which is worse than an admitted
> gap.

The same gap silences [B-121](B-121-sequential-does-not-serve-a-player.md) — the cost of asking for
the ends first is a swarm cost — and every future question about the picker: rarest-first against
in-order, the raised pool of [B-106](B-106-per-file-priority.md), endgame, `allowed fast`.

- **The decision and its reason.** `SeedingPeer` gains two things and no more: **which pieces it
  holds** (a `Bitfield`, all of them by default, announced as its `bitfield` and enforced on every
  `request`) and **a rate** in bytes a second. Rarity is what makes a picker's choice matter at all,
  and a rate is what makes a timing on loopback about the client rather than about the loopback: at
  full speed the stand measures the kernel and the disk, and every variant wins by the same amount.
- Enforced and not only announced: a seed that advertises a subset and serves anything asked of it
  would let a picker cheat the very rule under test. A `request` for a piece it does not hold is the
  connection closed, which is what BEP 3 says a peer may do.
- The alternative that was rejected: several whole seeds with different rate limits. That varies
  *speed*, not *rarity*, and the picker's rules are about which peer has what.
- Not covered: choking strategy, a seed that lies, a seed that goes away mid-piece. Each is its own
  item and none is needed to make a piece rare.

- AC: a stand can be built in which piece *p* is held by one peer of five, and a picker asked for
  work on it asks for *p* first; a download against a seed limited to N bytes a second takes the
  time that rate implies, within the noise of the machine it ran on.
- Anchors: `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/SeedingPeer.kt`,
  `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/LocalSwarm.kt`.
