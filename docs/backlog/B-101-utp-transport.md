---
id: B-101
title: "µTP (BEP 29): the transport this client cannot be reached on"
status: done
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

## Done 2026-09-17 — deferred, with the number that would un-defer it

**Taken while [B-103](B-103-upnp-and-nat-pmp-port-mapping.md) is still `wip`, and that is said out
loud rather than arranged around.** The `blocked_by` was never about B-103 being *finished*; it was
about its evidence existing, because the question here is whether TCP reachability is what is left
after everything cheaper has been done. That evidence exists now, and B-103's remaining work — an
acceptance run on a router that maps, which this network does not have — cannot change it.

### What the measurement says about reachability

From [B-98](B-98-how-many-peers-does-this-client-meet.md)'s twenty-minute run, the client's own
counters:

```
dials 303/4423 (connect timed out 2856, refused 850, closed during the handshake 349, reset 29, …)
```

**2 856 of 4 423 dials — 65 % — end in `connect timed out`.** Those are peers an outgoing TCP
connection cannot reach. That is the number this item is about, and it is large.

It is also **not** a number µTP fixes. A peer unreachable over TCP is, in the overwhelming majority,
a peer behind a NAT with nothing forwarded — and µTP does not traverse a NAT any more than TCP does.
What reaches those peers is one of two things, and neither is this item:

- **They dial us**, which needs a forwarded port — [B-103](B-103-upnp-and-nat-pmp-port-mapping.md),
  built, and the reason it was built first.
- **Hole punching**, which needs a peer that can reach both ends to relay the attempt — and which
  needs µTP underneath it, which is how µTP would earn its place rather than by being a second
  transport for its own sake.

### The decision

**Deferred.** The number that would un-defer it is not the 65 % above; it is what remains *after* a
run with a forwarded port. If, on a network where B-103 succeeds, incoming connections still leave
this client materially short of a reference client on the same swarm, then the difference is
reachability that TCP cannot buy and µTP becomes the next thing. Until that run exists there is no
evidence for starting an XL piece of work whose literature is congestion control.

Two smaller things that are true regardless and are worth having written down:

- **µTP's other half is politeness, not reach.** LEDBAT yields to interactive traffic; a TCP-only
  client saturating an uplink makes its owner's other traffic worse in a way a µTP client's does
  not. That is a real cost this client imposes and it is not measured anywhere. It is not enough on
  its own to justify the work, and it should not be forgotten when the work is next considered.
- **The half that is easier to write is the half worth less.** Dialling over µTP reaches peers that
  already reach us; *accepting* over it is what a NAT'd peer needs. An implementation that does the
  first and defers the second has spent the effort and bought nothing.

### What was rejected and stays rejected

Taking a µTP implementation as a dependency. There is no maintained JVM one this project would put
on its hot path, and `:engine` keeps its transport behind an interface it owns precisely so that
this stays a choice rather than a constraint.
