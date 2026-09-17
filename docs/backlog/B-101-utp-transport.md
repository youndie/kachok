---
id: B-101
title: "µTP (BEP 29): the transport this client cannot be reached on"
status: open
priority: P3
size: XL
stage: m9-swarm
epic: feature-download
blocked_by: [B-98, B-103]
---

# B-101 — µTP (BEP 29): the transport this client cannot be reached on

Every peer connection here is a TCP `SocketChannel`, dialled by `SocketPeerDialer` and accepted by
`PeerListener`. BEP 29's µTP — a congestion-controlled stream over UDP, tuned to yield to
interactive traffic — is not implemented and is not mentioned anywhere in the repository.

The cost is not that peers are unreachable; a mainstream client offers both transports and will
accept TCP. The cost is narrower and mostly falls on the incoming side. A peer whose network passes
UDP but throttles or blocks TCP for BitTorrent reaches this client on neither, because the one
transport it has is the one being blocked. A peer behind a NAT that only holds a UDP mapping —
which is what makes hole-punching work at all — can be dialled by a µTP client and not by this one.
And a client that speaks only TCP is the one every other peer's congestion control treats as an
ordinary bulk flow, which is the behaviour µTP exists to avoid: an uplink saturated by this client
makes its owner's other traffic worse in a way a µTP client's does not.

This is the largest item in the stage by a wide margin and the one with the least certain payoff,
which is why it sits behind the measurement. Implementing it means a second transport under
`PeerConnection` with its own congestion control, sequence numbers, retransmission and timers —
LEDBAT, a delay-based controller with its own literature — sharing the DHT's UDP socket or opening
another, and multiplexing many streams over one datagram flow. It is not a week.

- **The decision and its reason.** Deferred deliberately, and written down rather than left
  unmentioned so that the next person to notice the gap finds a decision instead of a hole. It is
  taken up only if [B-98](B-98-how-many-peers-does-this-client-meet.md)'s numbers, after
  [B-95](B-95-the-dial-loop-only-runs-when-something-else-happens.md),
  [B-100](B-100-protocol-encryption.md) and
  [B-103](B-103-upnp-and-nat-pmp-port-mapping.md), still show a gap that TCP reachability explains.
- Rejected: a partial µTP that dials but does not accept. The incoming half is where the peers are
  — the accepting side is what a NAT'd peer needs from us — so the half that is easier to write is
  the half that is worth less.
- Rejected: taking a µTP implementation as a dependency. There is no maintained JVM one this
  project would be willing to put on its hot path, and `:engine` is multiplatform with a single JVM
  target precisely so that the transport stays behind an interface it owns.
- Not covered: hole punching (`ut_holepunch`), which needs µTP to be worth anything and is its own
  item if this one is ever taken.

- AC: not yet. The item is a decision to defer; its acceptance criterion is the sentence in the
  research naming the number that would justify starting it.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/peer/Peer.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerDialer.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/PeerListener.kt`,
  `docs/research/research-architecture.md`.
