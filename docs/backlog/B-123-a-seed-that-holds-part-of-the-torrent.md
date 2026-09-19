---
id: B-123
title: "Every seed on the stand holds everything, so nothing on it can be rare"
status: done
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

## What building it found

**Every seed handed out the same peer id.** `-SEED01-000000000000`, twenty bytes, in every instance
— invisible for as long as a stand had one seed, and the first stand with five stalled at seven
pieces of eight with **one peer connected**. That is not a defect in the client: it is
[B-111](B-111-two-connections-to-the-same-peer.md) working exactly as written, hanging up on four
connections that all claimed to be the same peer. The seed's id is now its port, which is what makes
it unique.

It is worth naming because of what it would have done if the tests had gone the other way round: a
measurement of five peers against one, taken on a stand where four of the five are dropped at the
handshake, would have produced a number, a plausible one, and an entirely false one.

The rate is asserted as a **floor and never a ceiling** — the seed sleeps for a block's worth of
time after sending it, so nothing on the stand can make a loaded machine faster and there is no
upper bound to go flaky on. The floor counts `blocks - 1` gaps, because the last sleep happens
behind the reader.

- AC: a stand can be built in which piece *p* is held by one peer of five, the real client finishes
  the download, and *p* comes from the one peer that had it; a download against a rate-limited seed
  cannot beat that rate. **Met.**
  **Automated:** `swarm/src/test/.../SeedingPeerTest.kt` — `aSeedAnnouncesOnlyThePiecesItHolds`,
  `aSeedHangsUpOnARequestForAPieceItDoesNotHold`, `aSeedStillServesWhatItHolds`,
  `aRateLimitedSeedCannotBeatItsOwnRate`, `aSeedWithoutARateHasNoRate`;
  `swarm/src/test/.../ARareSwarmTest.kt` — `aPieceOnlyOnePeerHasArrivesFromThatPeer`.
- The `:swarm` module gained a test source set with this item, and that is the point rather than a
  side effect: every suite in the repository goes green on whatever this seed does, so a harness
  that lied would turn each of them into a green run over a question nobody asked.
- Anchors: `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/SeedingPeer.kt`,
  `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/LocalSwarm.kt`,
  `swarm/src/test/kotlin/io/github/youndie/kachok/swarm/SeedingPeerTest.kt`,
  `swarm/src/test/kotlin/io/github/youndie/kachok/swarm/ARareSwarmTest.kt`.
