---
id: B-154
title: "Each transferring torrent gets an equal share of the client's dials in flight"
status: done
priority: P2
size: S
stage: m3-storage
epic: feature-download
blocked_by: [B-152]
---

# B-154 — Each torrent gets its share of the dials

Seen with B-152 live: three downloads, the client's cap of 20 dials in flight full all the time, and
one of the three with no peers at all out of 335 it knew, while another held 26. The cap was taken by
whichever session's tick asked first, and most of a public swarm's addresses never answer, so a dial
holds its slot for the whole connect timeout: the first torrent to ask kept the slots and the others
waited behind dials that were never going to answer.

- **The decision and its reason.** The set divides `maxDialsInFlight` among the torrents that are
  transferring, as B-152 divides the connections, at least one each, and sends it with the
  connections in `Command.Share(maxPeers, dials)`. A session dials no more than its share minus its
  dials already in flight; the client's own cap stays underneath, as the bound across everything.
  Equal shares can leave a slot unused when one torrent has nothing left to dial — a fair split was
  the point, and a slot idle for a tick costs less than a torrent with no peers.

- AC: a torrent keeps no more dials in flight than its share, however many addresses it knows.
  **Met.** **Automated:** `engine/src/commonTest/.../session/SessionTest.kt` —
  `aShareOfTheDialsIsAllATorrentKeepsInFlight` (dialled 6 of 6 without the share, measured).
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`.
