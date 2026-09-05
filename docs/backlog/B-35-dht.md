---
id: B-35
title: "Mainline DHT (BEP 5)"
status: done
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
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/dht/Dht.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/dht/RoutingTable.kt`,
  `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/DatagramKrpcTransport.kt`.

**Done.** KRPC on the bencode this project already had, a routing table bucketed by common prefix
with this node's own id, Kademlia's iterative `get_peers`, and `announce_peer` with the token the
lookup collected. The session looks the torrent up and re-announces every fifteen minutes: a lookup
is a snapshot of a network that changes and an announce is forgotten after a day, so doing both
once at start-up would leave the client unfindable an hour later.

The lookup is tested against a **chain**, not a mesh: four fake nodes each knowing only the next
one, so a lookup that asked its starting nodes and stopped comes back empty. A network where every
node knows the answer would pass with no algorithm at all.

**One socket for the whole DHT**, which the item did not say and `implied_port` requires: BEP 5
tells a node to remember the port a query arrived from, so a socket per query would announce a port
nothing listens on. That makes the transport a multiplexer keyed by transaction id, and its test
answers three questions in the reverse order they were asked.

**`NodeId` is deliberately not a value class.** Every other twenty-byte identifier here is one, but
a value class around a `ByteArray` inherits the array's equality — identity — and the routing table
keys maps by node id.

**The DHT is off unless `--dht` asks for it**, which is a deviation from what mainstream clients
do and is recorded rather than assumed. Joining means contacting three public bootstrap routers and
announcing this machine's address to strangers; nothing in phase 1 needs that, because every
torrent this client can open names a tracker — and a default of "on" would also mean every run of
the test suite doing it. [B-36](B-36-ut-metadata-and-magnets.md) brings magnets, which is the
torrent that needs it, and is where the default should be reconsidered.
