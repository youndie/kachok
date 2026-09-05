---
id: B-35
title: "Mainline DHT (BEP 5)"
status: open
priority: P2
size: L
stage: m8-extensions
epic: feature-download
---

# B-35 — Mainline DHT (BEP 5)

Trackerless torrents and magnets need it; it is also the largest single piece of protocol work
after the wire itself.

- **The decision and its reason.** A routing table of 160-bit ids, `ping`/`find_node`/
  `get_peers`/`announce_peer` over KRPC (bencode over UDP, so the codec is reused), bootstrap
  nodes from configuration; one virtual thread on the datagram socket. Off for private torrents.
- Rejected: doing this before [B-32](B-32-udp-tracker.md). UDP trackers are a week; DHT is a month.
- Not covered: BEP 42 security extension, BEP 43 read-only nodes.

- AC: against a local DHT fake network, `get_peers` for a known hash returns the announced peer;
  the routing table evicts unresponsive nodes.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/dht/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/`.
