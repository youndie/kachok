---
id: B-09
title: "Accept incoming peers on the BEP 3 port range"
status: done
priority: P1
size: S
stage: m2-wire
epic: feature-seeding
blocked_by: [B-07]
---

# B-09 — Accept incoming peers on the BEP 3 port range

Without a listener the client can only download from peers it dials, and half the swarm dials in.

- **The decision and its reason.** Bind 6881, then 6882 … 6889, give up after 6889 with an error
  naming the range (BEP 3); the bound port goes into the tracker announce. Accept in a loop on its
  own virtual thread; each accepted socket becomes a peer through the same transport as
  [B-07](B-07-virtual-thread-peer-transport.md). Which torrent an incoming peer belongs to is
  decided by the info hash in its handshake.
- Rejected: UPnP / NAT-PMP port mapping. Useful, separate, and a dependency.
- Not covered: a cap on incoming connections per torrent — it is a `SessionConfig` field with a
  default set in [B-17](B-17-session-orchestrator.md).

- AC **met 2026-09-05** (`PeerListenerTest`, 5 tests): with the first port occupied by the test, the listener binds 6882 and the announce carries 6882; an
  incoming handshake for an unknown info hash is closed.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/`.

**Closed 2026-09-05.** Three decisions the item did not spell out:

* **A full range is an error, not an ephemeral port.** Falling back to whatever the operating
  system offers would announce a port to the tracker and listen on another — a client that believes
  it is reachable and is not, which is worse than one that says it cannot listen.
* **An accepted peer and a dialled one differ only in who spoke first.** `SocketPeerConnection
  .accept` reads their handshake before writing ours and refuses a wrong info hash *before*
  admitting to having the torrent; from there both paths run the same `serve` coroutine, so the
  incoming case adds no state machine of its own.
* **One connection per peer.** A peer we are already talking to that also dials us is closed on
  arrival; two connections to the same peer would be two entries in the picker's availability and
  two claims on the same blocks.

The listener belongs to the caller, not the session: whether to accept at all is a policy question,
and the session's door for a new peer is the same command channel a tracker's peers come through.
