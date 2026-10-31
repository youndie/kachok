---
id: B-174
title: "The pieces open across the client fit the direct memory they live in"
status: wip
priority: P1
size: M
stage: m3-storage
epic: feature-download
blocked_by: [B-152]
---

# B-174 — The block memory is one budget for the client

Seen in the window on 2026-10-31 (`kachok-0.1.222`, `MaxDirectMemorySize=192m`), minutes after the
client's download limit was raised from 16 to 100 Mbit/s with three films in 8 MiB pieces running:
every torrent at 0 B/s at once, peers connected and requests out, with the limits on or off, until
the window was restarted — and then again two minutes later. Sampled through the MCP server every
20 s: the stop came at 10:52:36 with **8 959 buffers on loan, 140 MiB**, and the count did not move
again. A thread dump had nothing blocked on a disk or a lock.

- **The cause.** Each torrent's pool and window were sized from its *own* peers — 500 peers at a
  pipeline of 64 is 32 500 buffers, 508 MiB, per torrent (`TorrentRuntime.poolCapacity`) — while the
  process had 192 MiB of direct memory for all of them, and a pool kept every buffer it had ever
  allocated. Research §1.2c2 had measured two 4 MiB torrents and called the sum of what they use the
  cost; it is not a bound. *Inferred, not seen:* the next `allocateDirect` had no room, and an open
  piece cannot finish without the block it was for. At 16 Mbit/s the same torrents never opened
  enough pieces to reach the ceiling, which is why it waited for the limit to be raised.
- **The decision and its reason.** Two shared things in the set, split once from
  `-XX:MaxDirectMemorySize` (`SharedBlockBudget.split`): three quarters of it as buffers in **one
  pool** every torrent's pool lends from, so a buffer one torrent is done with is the next one's;
  and of those, all but a block per connection as a **budget for started pieces**, reserved whole
  before a piece begins (`PiecePicker` asks `PieceBudget`) and given back when it closes — verified,
  failed or forgotten. Every open piece then has room for the rest of its blocks, which is the
  property the deadlock lacked. A torrent holding nothing may always begin a piece when there is
  room; beyond that its share is the budget over the torrents holding or recently asking, so the
  first to ask does not keep the rest. Each torrent keeps its own cap, and its own back-pressure,
  as research §1.2c2 wanted. A refused piece is asked for again on the session's tick, because the
  room comes from another torrent's piece closing, which is no event in this one.
- `network_status` says `block memory: X of Y on loan, Z of W reserved by started pieces`.

- AC: the pieces open across the client never need more blocks than there are buffers.
  **Automated:** `engine/src/jvmTest/.../runtime/SharedBlockBudgetTest.kt` —
  `threeTorrentsWideWindowsFitTheMemoryTogether` (the 2026-10-31 shape: three 8 MiB-piece torrents,
  windows of 256 pieces, 192 MiB; it also shows the same three would need more without the budget),
  `aSecondTorrentGetsTheNextPieceThatCloses`, `aTorrentHoldingNothingAlwaysGetsOnePieceWhenThereIsRoom`.
- AC: a piece refused for want of room is asked for when room opens. **Automated:**
  `SessionTest.aPieceTheClientHadNoRoomForIsAskedForWhenThereIsRoom` (fails without the tick:
  `expected: <2> but was: <0>`).
- AC: a buffer goes back to the client's pool and is the next torrent's. **Automated:**
  `BufferPoolTest.aTorrentsPoolLendsFromTheClientsAndHandsItsBuffersBack`,
  `theClientsCapBindsEveryTorrentsPool`.
- AC: the window downloads at 100 Mbit/s for fifteen minutes without standing still. *Open* — run
  on the Windows machine with `kachok-0.1.225`.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/SharedBlockBudget.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PieceBudget.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/BufferPool.kt`.
