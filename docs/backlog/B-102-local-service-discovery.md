---
id: B-102
title: "Local service discovery (BEP 14): the peers on the same network are never found"
status: open
priority: P3
size: S
stage: m9-swarm
epic: feature-download
blocked_by: []
---

# B-102 — Local service discovery (BEP 14): the peers on the same network are never found

This client has three peer sources — the tracker, the DHT and `ut_pex` — and no fourth. BEP 14's
local service discovery, a multicast announce on the local segment, is named once in the
repository, in the comment beside `offeredExtensions` explaining which sources BEP 27 switches off
for a private torrent, and is implemented nowhere. Two copies of this client on one network find
each other only if a tracker or the DHT happens to tell one about the other's public address, and
then talk to each other through the router that both of them are behind.

The item is small and its payoff is narrow but sharp: on a segment where a peer exists at all, it
is the nearest peer in the swarm by a long way, and the only source that costs one multicast packet
every few minutes instead of a conversation with strangers. It is also the peer source with the
best privacy story — nothing leaves the local segment — which makes it the one that can be on by
default without the argument [B-99](B-99-the-dht-is-off-and-its-reason-for-being-off-expired.md)
is having.

- **The decision and its reason.** Announce and listen on `239.192.152.143:6771` and
  `[ff15::efc0:988f]:6771`, at the specification's interval, carrying the info hash and the port
  `PeerListener` actually bound — the same port the tracker is told, for the same reason: a client
  announcing a port nobody listens on is worse than one that says nothing. Both address families,
  because the listener already binds the wildcard on a dual stack and a v4-only announce would
  undo that.
- Rejected: doing this per torrent. One socket for the process and one announce carrying every
  running torrent's hash, sitting with the listener and the DHT in `TorrentSet` — the same rule
  those two follow, and for the same reason.
- Rejected: acting on an announce for an info hash this client does not have. It is a peer telling
  the segment what it is downloading; there is nothing to do with that but ignore it.
- Not covered: the switch. Off for a private torrent is BEP 27 and is not a setting; whether it is
  on by default for the rest is decided with the answer to
  [B-99](B-99-the-dht-is-off-and-its-reason-for-being-off-expired.md) so that the two peer sources
  do not get two unrelated answers on one settings screen.
- Not covered: rate limiting the multicast. The specification's interval is already conservative
  and there is no observed case of a segment this client flooded.

- AC: two instances on one network, both given the same torrent with no tracker and the DHT off,
  connect to each other within the announce interval, and the connection's address is the peer's
  local one. An announce for an unknown info hash changes nothing.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/PeerListener.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`.
