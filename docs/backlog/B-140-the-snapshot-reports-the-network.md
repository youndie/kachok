---
id: B-140
title: "The snapshot reports the DHT from its own table and says whether the port is mapped"
status: done
priority: P3
size: S
stage: phase-3-server
blocked_by: []
---

# B-140 — The snapshot reports the network

A diagnosis of a slow download read two things off `kachok://snapshot`, and both were wrong.
Reported as [youndie/kachok#77](https://github.com/youndie/kachok/issues/77).

- **`dhtNodes: 0` while the DHT worked.** The snapshot took the count from the *first torrent's*
  state, and a session publishes it only from its DHT loop, only while it is not paused. The first
  torrent in that list was paused, so the snapshot read 0 for as long as it stayed so, while UDP to
  two of five bootstrap nodes answered from the same machine. With no torrent at all, it read 0
  too. The window's status bar had the same lookup.
- **"No mapping" when the snapshot simply did not say.** `TorrentSet` knows whether the router
  forwards the listen port and why not (B-103), and the window shows it, but neither the wire
  `Snapshot` nor any MCP tool carried it. An absent field was read as an absent mapping.

- **The decision and its reason.** `dhtNodes` is the set's routing table size
  (`dhtForLookups?.table?.size`), in the snapshot and in the window alike: the table is the fact,
  and a torrent's copy of it is a report that can go stale. The wire `Snapshot` gains `mappedPort`
  and `portMapping` (the external port, and the sentence: "mapped to 6881" or "not mapped: …").
  `network_status` puts the listen port, the mapping, the DHT and how many peers the running
  torrents hold in one MCP call, which is what to read before blaming a swarm for being slow.
- Not covered: telling a mapping on a router that is itself behind another NAT from a mapping that
  reaches the internet. The router on the machine this was found on has a private WAN address; a
  check of `GetExternalIPAddress` against the private ranges would say so, and is its own item.

- AC: with no torrent, or the first one paused, `dhtNodes` equals the routing table's size; the
  snapshot and `network_status` report the mapping and its reason. **Met.**
  **Automated:** `control/src/test/.../NetworkSnapshotTest.kt` —
  `dhtNodesIsTheRoutingTablesSizeWithNoTorrentToReportIt`, `aClientWithoutTheDhtSaysSoAndReportsItsMapping`;
  `control/src/test/.../mcp/McpServerTest.kt` — `theNetworkStatusSaysWhatDecidesReach`.
- Anchors: `control/src/main/kotlin/io/github/youndie/kachok/control/Snapshot.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`,
  `wire/src/commonMain/kotlin/io/github/youndie/kachok/wire/Protocol.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/ClientModel.kt`.
