---
id: B-103
title: "Port mapping (UPnP IGD, NAT-PMP/PCP): reopening B-09's rejection, because the reason given was a dependency"
status: done
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

## Iteration 1 — 2026-09-17: the protocol, before any socket

`engine/.../nat/NatPmp.kt` is the whole of NAT-PMP: twelve bytes out, sixteen back. It is in common
code and has no socket in it, so the parsing is testable without a router — which matters here more
than usual, because the subject of this item is *somebody else's* router and there is exactly one on
this network to try.

**NAT-PMP first and UPnP as the fallback**, and the order is about size rather than preference. UPnP
IGD is an SSDP datagram, an HTTP fetch of a device description, an XML parse and a SOAP call; this
is one file. PCP (RFC 6887) supersedes NAT-PMP and is deliberately absent: its version byte is what
a NAT-PMP-only router rejects, so a client speaking it falls back anyway, and the pair worth having
is these two. PCP earns a place when a router turns up that speaks it and neither of the others.

Two decisions the item did not spell out and the code now does:

- **A refusal comes back carrying its reason, not as a null.** The five RFC result codes become
  five different sentences, and an unknown code still says something. "The router refused" is not
  something a person can act on; "the router has port mapping switched off" is, and it calls for a
  different action from "the router does not speak NAT-PMP". A test asserts the five are distinct,
  because a mapping from five codes to one polite sentence would pass every other check.
- **A release sends a zero external port.** RFC 6886 says the field is ignored when the lifetime is
  zero. A router that does *not* ignore it reads the packet as a request for that port with a zero
  lifetime — which is how an implementation asks for a mapping while meaning to drop one, and
  leaving a hole open in a router this client does not own is the part of this item that is
  somebody else's problem. The mutation confirms the test: naming the port on a release fails
  `releasingAsksForNothingAndForNoTime` and nothing else.

A datagram that is not a response is a non-event rather than a failure — too short, the wrong
version, or somebody *else's* request arriving on the same socket, which is what happens when
another thing on the segment is also mapping ports. Same rule as the DHT's transport, and for the
same reason.

**What is left**: the socket, the default gateway, the renewal timer and the release on shutdown,
then the UPnP fallback, then telling the window. Finding the gateway is the part with no portable
answer — the JVM has no route-table API — so it will be a platform-specific reading of `ip route`,
`route -n get default` or `Get-NetRoute` with a documented fallback, and that is worth writing down
before it is written.

## Iteration 2 — 2026-09-17: the mapper, and the router that is not there

**The network this was written on answers neither protocol.** A NAT-PMP request to the default
gateway drew nothing in three attempts; a UPnP `M-SEARCH` for an `InternetGatewayDevice` drew
nothing in six seconds; and the reference client on the same segment says it in its own words —
`could not map port using UPnP: no router found`. There is a NAT — the gateway is 192.168.1.1 and
the external address is a public one — it simply does not offer mapping, or has it switched off.

That was established **before** the code was written rather than discovered after, and it decided
the shape of it. The path this item can actually demonstrate here is the *failing* one, so that is
the path with the assertions on it:

- Two attempts of two seconds, then `NotMapped` carrying which router did not answer. Measured end
  to end against the real gateway through `:engine:portMapProbe`: **`NOT MAPPED after 4016ms: the
  router at 192.168.1.1 does not answer NAT-PMP`**. A client that waits on a router which will
  never answer is worse than one that never asked, so the test asserts the clock as well as the
  words.
- Three states and not two. "We did not manage" and "we did not try" call for different things from
  a person, and a status line that conflates them stops being read.
- A refusal arrives in the router's own terms. Five RFC codes, five different sentences.

**Finding the gateway has no portable answer.** The JVM has no route-table API, so this reads the
platform's own route command and falls back to the first address of this machine's subnet. The
fallback is a guess and says so: what a mapping is worth is whatever the router's reply says, and a
client that maps on the wrong address has told its owner it is reachable when it is not.

`:engine:portMapProbe` exists for the same reason the MSE probe does — the subject is somebody
else's router, and only a real one can show a mapping being *made*. It releases what it maps.

**What is left**: wiring it into `TorrentSet` beside the listener, the renewal timer, the release on
shutdown, the UPnP fallback, and the status line. And an honest note for whoever closes this: the
acceptance criterion — *receives incoming connections it did not dial* — **cannot be met on this
network**. It needs a router that maps, or this one with mapping turned on in its settings. That is
the owner's to arrange and is not a thing more code can fix.

## Iteration 3 — 2026-09-17: wired into the set, asked beside the opening rather than on it

`TorrentSet` now asks the router for **the port the listener actually bound**, renews at half the
lease, and releases on close. `portMapping` is a sentence the window can show.

The port is the bound one and never the requested one. `PeerListener`'s header already draws that
line for the tracker — *"the port that was free is the one the tracker must be told about"* — and a
mapping that disagreed with the announce is the same defect one layer down: a client telling
everyone about a port that is forwarded nowhere.

**The ask is launched, not awaited, and the reason is measured.** On the gateway here it costs four
seconds to find out the answer is no. Done on the opening path that is four seconds of a client
that has not dialled anybody, every start. Two tests hold the shape — opening a set and closing one
each finish in well under a second — and the mutation confirms them: moving the ask onto the
opening path fails `openingASetDoesNotWaitForTheRouter` and nothing else.

Two decisions worth keeping:

- **A refusal is not retried on a timer.** A router that does not speak NAT-PMP will not have
  learned it in half an hour, and asking again is traffic on somebody's network for no chance of a
  different answer. A *success* is renewed at half the lease, which leaves room for one failure
  before the hole closes.
- **Close cancels the renewal before it releases**, so the loop cannot re-map what is being
  dropped, and it releases only what was mapped.

**What is left**: the UPnP fallback, and the status line in the window. And the acceptance criterion
still needs a router that maps — which this network does not have, as iteration 2 recorded.

## Iteration 4 — 2026-09-17: the window says so, and only when there is something to say

The status line reads `port 6881 to 49152` when the router forwards it, and `port 6881 listening` —
exactly what it read before — when nothing does. The goldens are unchanged, which is the point: a
client whose router does not map has gained no new noise on a line that already carries six figures
in 24 dp, and a client whose router does has gained the one fact it could not otherwise know.

The external port travels to the window as a number beside the sentence rather than being parsed
out of it. A screen wants a figure and a person wants words; deriving the first from the second is
how a status line starts depending on the wording of an error message.

The reason a mapping *failed* is deliberately not on that line. It is one line for the whole
process and the failing case is the common one; a client that shouts "not mapped: the router at
192.168.1.1 does not answer NAT-PMP" at every start has made its status bar unreadable to say
something almost nobody can act on. `TorrentSet.portMapping` carries the sentence for whoever wants
it, and that is where the UPnP fallback's own failures will go too.

**What is left on this item**: the UPnP fallback, and an acceptance run on a router that maps —
which this network does not have.

## Iteration 5 — 2026-09-17: UPnP's text, and the two ways it picks the wrong thing

`Upnp.kt` is the parsing half — the search datagram, the `LOCATION` header, the control URL, the
SOAP envelopes and the fault codes. The I/O is next; this is the part that decides whether the I/O
talks to the right thing.

**Two silent-failure modes, each with a fixture that contains the trap:**

- **The `LOCATION` header is case-insensitive and devices disagree about it.** `LOCATION`,
  `Location` and `location` all turn up. A client that matches one spelling fails against a share of
  the routers it meets, and the failure presents as "this router has no UPnP" — indistinguishable
  from the truth, which is why it survives.
- **A description lists several services and the first one usually maps nothing.** Taking the
  document's first `<controlURL>` is the obvious implementation; on the fixture here it picks
  `/ctl/L3F`, a layer-3 forwarding service that accepts the SOAP call and forwards no port. The
  client then reports a mapping it does not have, which is worse than reporting none — it tells its
  owner they are reachable when they are not. The control URL is taken from inside the *mapping*
  service's block.

**The description is scanned, not parsed as XML, and the trade has a name.** A real parser means
`java.xml` in the run-time image — megabytes against the 32 MB measured in research §1.3b — for one
document read once per start. The scan is narrow and fails by finding nothing rather than by finding
something wrong. If a router turns up whose description defeats it, the answer is the module and not
a cleverer expression, and that is written where the next person will read it.

Refusals carry their code's meaning rather than a number. 718 is somebody else already holds that
port — try another. 725 is the router only makes permanent mappings, which is a different decision
entirely: accepting one leaves a hole that no lease will ever close.

**What is left**: the SSDP socket and the two HTTP calls, then the fallback order in `PortMapper`,
then the acceptance run on a router that maps — which this network still does not have.

## Iteration 6 — 2026-09-17: the fallback, and the test that caught its cost

`UpnpMapper` does the I/O — one SSDP datagram, a GET for the description, a SOAP POST — and
`PortMapper` tries it **only when NAT-PMP is met with silence**. Not when NAT-PMP *refuses*: a
router that says "port mapping is switched off" has answered the question, and asking the same box
the same thing in another protocol is noise on somebody's network for an answer already given.

Three decisions that are easy to get wrong and invisible when you do:

- **Released through whichever protocol made it.** A NAT-PMP release sent to a mapping UPnP made is
  a packet the router has no record for; it succeeds silently and the hole stays open for the rest
  of the lease.
- **The internal address is the one the router sees us at**, taken by connecting a datagram socket
  to the device that replied. `InetAddress.getLocalHost()` on a machine with several interfaces is
  a coin toss, and a mapping pointed at the wrong one is a router forwarding to nothing while this
  client reports success.
- **A non-2xx reply is read, not discarded.** A UPnP refusal arrives as HTTP 500 carrying the fault
  code, which is the only explanation the router ever gives.

**The existing timing test caught exactly the thing it should have**: adding a fallback made the
total wait longer, and it failed rather than quietly passing at the new number. Its bound now covers
both protocols, because silence from the first is what sends the mapper to the second and the sum is
what a person would wait through. Against the real gateway here, end to end:

```
NOT MAPPED after 7028ms: the router at 192.168.1.1 does not answer NAT-PMP, and no UPnP gateway answered
```

Seven seconds, naming both, off the opening path — which is why the ask was launched rather than
awaited two iterations ago, and is the second time that decision has paid.

**What is left**: nothing but the acceptance run, and it needs a router that maps. Everything this
item can build is built.

## Iteration 7 — 2026-09-17: the acceptance, on a router built for it

Iterations 2–6 said the same true thing each time: everything is built, and the acceptance —
*receives incoming connections it did not dial* — cannot be met, because the gateway on this network
maps nothing. So a gateway was built. A container lab holds an inside network, an outside network,
and a `miniupnpd` between them answering NAT-PMP and UPnP IGD, which is the router this item has
needed since iteration 2.

Every clause of the acceptance now holds, against a real mapping daemon and this client's own code:

- **kachok maps its bound port.** Run behind the NAT, the CLI's `TorrentSet` asked over NAT-PMP and
  `miniupnpd` logged `NAT-PMP port mapping request : 6881->10.91.0.10:6881 tcp lifetime=7200s` — the
  7 200 s is `NatPmp.LIFETIME_SECONDS`, this client's own value, which is how the request is known to
  be kachok's and not the reference tool's. The router installed the forwarding rule.
- **It receives a connection it did not dial.** A libtorrent seeder on the outside network dialled
  the *mapped external port*; the packet was forwarded to the client, which finished the 128-piece
  torrent showing `1 of 0 peers` and `dials 0/0` — one peer, zero known, zero dialled. The listener
  header's promise, delivered. The download's SHA-256 matched the seed, so the path carried real
  bytes and not just a handshake.
- **It releases on exit.** The client's `PortMapper.release` sent NAT-PMP external port 0, lifetime
  0; `miniupnpd` logged `NAT-PMP TCP port 6881 mapping removed` and the forwarding rule was gone.

Two findings worth keeping, both recorded in research
[D15](../research/research-architecture.md#d15-mses-prime-is-not-rfc-2409s-and-a-real-router-was-built-to-prove-the-mapping):

- The seeder's *first* attempt to reach the client was µTP, which timed out because this client has
  none ([B-101](B-101-utp-transport.md)); it fell back to TCP and connected. A reference client
  reaching an incoming kachok pays one handshake-timeout of µTP first. Small, and real.
- The lab's own plumbing needed a hairpin masquerade on the inside interface, because the host's
  bridge-netfilter drops a forwarded SYN whose source is the outside address — a fact about testing
  a NAT inside Docker, not about the client, but the reason a first run showed the mapping made and
  the connection still refused.

The failing-path assertions from iterations 2–6 still stand and still run against the real gateway
on this network, which answers neither protocol; what iteration 7 adds is the *succeeding* path,
which only a router that maps could show. Everything this item set out to do is built and now
exercised end to end.
