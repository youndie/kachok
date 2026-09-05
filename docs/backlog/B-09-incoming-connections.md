---
id: B-09
title: "Accept incoming peers on the BEP 3 port range"
status: open
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

- AC: with 6881 occupied by the test, the listener binds 6882 and the announce carries 6882; an
  incoming handshake for an unknown info hash is closed.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/`.
