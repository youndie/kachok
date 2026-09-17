---
id: B-103
title: "Port mapping (UPnP IGD, NAT-PMP/PCP): reopening B-09's rejection, because the reason given was a dependency"
status: open
priority: P2
size: M
stage: m9-swarm
epic: feature-download
blocked_by: [B-98]
---

# B-103 — Port mapping (UPnP IGD, NAT-PMP/PCP): reopening B-09's rejection, because the reason given was a dependency

[B-09](B-09-incoming-connections.md) built the listener and rejected port mapping in one line:
*"Rejected: UPnP / NAT-PMP port mapping. Useful, separate, and a dependency."* The first two words
are still true and the third is the part worth re-examining, because it decided the item.

What the rejection costs is written in `PeerListener`'s own header: *"Half a swarm's connections are
incoming, so a client that only dials meets half the peers it could — and is invisible to anyone
behind a tracker that hands out its address."* Behind an ordinary home NAT with nothing forwarded,
the listener binds 6881, announces 6881, and receives nothing, because there is no path from the
public address to it. Every peer this client ever talks to is one it dialled — and a large share of
a public swarm is itself behind a NAT and can only be reached by peers that accept. Two
unconnectable clients never meet. A mainstream client on the same machine maps its port on start-up
and is reachable within seconds, which is a difference the owner sees directly as a peer count.

On the dependency: NAT-PMP and its successor PCP are a short request to the default gateway on UDP
1900-and-neighbours with a fixed binary layout — a few hundred lines with no dependency at all.
UPnP IGD is larger, an SSDP discovery datagram followed by SOAP over HTTP, and the HTTP client it
needs is `java.net.http`, which is already in this project's `jlink` module list and already used
for tracker announces (research §D8). Neither needs a library. What they need is care, which is a
different objection and should be recorded as that one if it is the one that stands.

- **The decision to take.** Map the listener's port on start-up over NAT-PMP/PCP first — it is the
  smaller protocol and the one modern routers answer — falling back to UPnP IGD, renewing the lease
  before it expires and releasing it on shutdown. The lease and the release are the item: a client
  that maps a port and never gives it back leaves an open hole in somebody's router after it exits,
  which is a worse thing to ship than no mapping at all.
- **The port this maps is the port that was bound, not the port that was asked for.** `PeerListener`
  already makes that distinction — *"the port that was free … is the one the tracker must be told
  about"* — and a mapping that disagrees with the announce is the same defect one layer down.
- Rejected: asking the owner to forward a port by hand. It is the correct advice and it is not a
  feature; the client still has to behave for the owner who does not.
- Rejected: mapping silently. A client that reconfigures the router is a client that says so — the
  window's status bar already has a place for whether the port is listening, and whether it is
  mapped belongs beside it.
- Not covered: detecting that the mapping worked. A router that answers "mapped" and does not
  forward is common; proving reachability needs something outside this network to dial back, and
  that is a different item and possibly a service this project does not want to run.
- Not covered: IPv6, where there is no NAT to traverse and the question is a firewall pinhole
  instead (PCP does both; whether to ask for one is separate).

- AC: behind a home NAT with nothing forwarded, the client starts, reports its port as mapped, and
  receives incoming connections it did not dial — counted, not assumed. It exits and the mapping is
  gone from the router. On a network with no mapping protocol, start-up is not slower by more than
  the timeout and the status says *not mapped* rather than nothing.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/PeerListener.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/main/StatusBar.kt`.
