---
id: B-128
title: "Rarest-first breaks ties by index, which keeps every client of a swarm in lock step"
status: open
priority: P1
size: S
stage: m9-swarm
blocked_by: []
---

# B-128 — Ties among equally rare pieces go to the lowest index

`PiecePicker.pick` compares with `availableFrom < rarest` — strictly less — so among pieces of equal
rarity the lowest index wins. On a fresh swarm around one seed **every** piece nobody holds yet has
availability 1, so every client resolves every tie the same way and asks for the same piece next.
Four clients that always want the same piece stay in lock step, and a swarm in lock step has nothing
to trade: each of them can only wait for the seed.

Measured ([research §1.2c6](../research/research-architecture.md), four clients, one seed whose
uplink is the bottleneck), against an experimental build differing in that line alone:

| torrent | tie-break | in the swarm | gave / took |
|---|---|---|---|
| 10 MiB | lowest index | 39.3, 39.2 s | 1.9 % |
| 10 MiB | random among the rarest | **13.1, 12.6 s** | **68.8 %, 68.6 %** |
| 30 MiB | lowest index | 119.2, 119.2 s | 0.6 % |
| 30 MiB | random among the rarest | **34.2, 33.4 s** | **71.7 %, 72.2 %** |

The swarm finishes three times faster and the seed is asked for one copy instead of four.

- **The decision and its reason.** Break the tie **at random among the equally rare**, by the
  reservoir sample the picker already uses for the first piece of a torrent — keep the *n*-th
  candidate with probability 1/*n* and every candidate is equally likely, in one pass and without
  allocating. Rarest-first stays rarest-first; what changes is which of several equally good answers
  it gives, and that it no longer gives the same one to everybody.
- **Why it is not a micro-optimisation.** The picker's own documentation already says why the first
  piece is drawn rather than chosen — *"every client starting at piece 0 makes piece 0 the only
  piece anyone has"* — and this is that same argument applied to every subsequent tie. The reason it
  was not seen is that a stand with one client cannot show it: lock step needs somebody to be in
  step with.
- The alternative that was rejected: leaving it and shortening the choke interval instead. The
  choker was the suspect before the measurement and is innocent — trading here is not slow to start,
  it happens once and stops, and no choke interval changes which piece four clients want next.
- Not covered: the order *within* a started piece, and the raised pool of
  [B-106](B-106-per-file-priority.md), where the same tie-break question arises and the same answer
  probably applies. Worth a look once this one is in.

- AC: on the `swarm-order` stand the four clients give each other more than half of what they take,
  and the `picker-order` comparison of [B-125](B-125-a-measurement-that-is-a-pair.md) is re-run to
  show what the change costs a single downloader — if anything; the picker's unit tests still pin
  rarest-first, strict priority and endgame, and one of them pins the new rule by failing when the
  tie-break goes back to the lowest index.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt`,
  `engine/src/commonTest/kotlin/io/github/youndie/kachok/engine/picker/PiecePickerTest.kt`,
  `docs/research/research-architecture.md`.
