---
id: B-142
title: "The DHT answers the queries other nodes send it"
status: done
priority: P2
size: S
stage: phase-3-server
blocked_by: []
---

# B-142 — The DHT answers

`DatagramKrpcTransport` counted the KRPC queries other nodes sent it and answered none of them —
`ping`, `find_node`, `get_peers`, `announce_peer` alike — while its own KDoc said "a DHT that only
asks is not a DHT node". BEP 5 drops a node that does not answer, so every node that met this one
in a lookup pinged it, heard nothing, and evicted it. Found after B-141; reported as
[youndie/kachok#82](https://github.com/youndie/kachok/issues/82).

- **The decision and its reason.** `Responder` (common code) answers the four queries: `ping` with
  the id, `find_node` with the eight closest nodes in the table, `get_peers` with a token, the
  stored peers as `values` and the closest nodes as `nodes` (both, as libtorrent sends them), and
  `announce_peer` by storing the peer when the token is one this node gave that address.
  `implied_port` is honoured. A query without a 20-byte id is error 203, an unknown method 204.
  The transport calls `Dht.answer` from its one reader and sends the reply to the address the query
  came from; `TorrentSet` wires it when it joins the DHT.
- **Tokens are a hash, not a table.** SHA-1 of the asker's address and a secret rotated every five
  minutes, truncated to eight bytes; the current and the previous secret are accepted, which is
  BEP 5's ten minutes. Nothing is stored per asker.
- **What a stranger can make this node hold is bounded.** 1000 info hashes, 100 peers each, 30
  minutes each, oldest out first; an answer carries the newest 50, which fit one datagram.
- **A query does not put its sender in the table.** BEP 5 calls a node good when it has answered
  *us*; a sender that never has is not one, and adding it would let anyone fill the buckets.

- Not covered: the DHT socket's port is ephemeral and not mapped on the router (B-103 maps the TCP
  listen port only), so unsolicited queries from the internet mostly cannot arrive. Measured with
  `./gradlew :engine:dhtProbe`: three queries arrived and were answered in a two-minute run, from
  nodes this one had just asked. Mapping the DHT's UDP port is its own item.

- AC: the four queries are answered as BEP 5 says, tokens are checked per address and expire after
  two rotations, stored peers expire and are capped, a query over the real socket is answered.
  **Met.**
  **Automated:** `engine/src/commonTest/.../dht/ResponderTest.kt` (9 tests);
  `engine/src/jvmTest/.../io/DatagramKrpcTransportTest.kt` — `aQueryFromAnotherNodeIsAnswered`.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/dht/Responder.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/dht/Dht.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/DatagramKrpcTransport.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`.
