---
id: B-143
title: "The DHT listens on the listener's port, and the router is asked to forward it for UDP"
status: done
priority: P2
size: S
stage: phase-3-server
blocked_by: []
---

# B-143 — The DHT's port is the listener's, and forwarded

Once the DHT answered queries (B-142), few could reach it. `TorrentSet.useDht` opened its socket on
an ephemeral UDP port, and the port mapping (B-103, B-139) forwarded the TCP listen port only, so
behind a NAT a query from a node this client had not just written to had nowhere to go: the probe
saw three in a two-minute run, all from nodes it had just asked. The port also changed at every
start, so any node that kept this one lost it at the next restart. Reported as
[youndie/kachok#84](https://github.com/youndie/kachok/issues/84).

- **The decision and its reason.** The DHT's socket binds the listener's port, as libtorrent and
  Transmission do — one number for a person to forward by hand, the same at every start. When the
  UDP port is taken it falls back to an ephemeral one and says so through `onBindFailure`.
- **The router is asked for UDP too.** `PortMapper.map(port, tcp = false)` was already NAT-PMP's;
  UPnP's `AddPortMapping`, `GetSpecificPortMappingEntry` and `DeletePortMapping` now take the
  protocol. The DHT's mapping is renewed like the listener's, released when the DHT is turned off
  (off the caller's thread) and when the set closes. `TorrentSet.dhtPortMapping` says what the
  router answered; `network_status` prints it.
- **Which protocol made a mapping is remembered per mapping.** One `usedUpnp` flag for both would
  let a UDP mapping that fell back to UPnP send the TCP one's release over NAT-PMP, a packet the
  router has no record for. `map` and `release` are synchronized: the two mappings are renewed
  from two coroutines and one discovery serves both.
- **`init` moved below the mapper.** Joining the DHT now uses the listen port and the mapper, so
  the `init` that joins it at construction has to run after both are initialized.

- Not covered: a router that is itself behind another NAT, which is this machine's case (B-140);
  a forwarded port there forwards from a private address.

- AC: with the DHT on, its port is the listener's; a taken UDP port falls back and is reported;
  the UDP mapping asks UPnP for UDP; with the DHT off there is no DHT mapping. **Met.**
  **Automated:** `engine/src/jvmTest/.../runtime/DhtPortTest.kt` — `theDhtListensOnTheListenersPort`,
  `aTakenUdpPortFallsBackAndSaysSo`, `withTheDhtOffThereIsNothingToForward`;
  `engine/src/jvmTest/.../nat/UpnpMapperTest.kt` — `aUdpMappingAsksForUdp`.
  **Manual:** `network_status` prints `DHT udp port 6881: mapped to 6881`, and the router's
  `GetSpecificPortMappingEntry` for UDP 6881 names this machine.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/nat/PortMapper.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/nat/UpnpMapper.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/nat/Upnp.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`.
