---
id: B-139
title: "UPnP finds the router on a machine with several adapters, and a refusal is checked against the router's table"
status: done
priority: P2
size: S
stage: m9-swarm
blocked_by: []
---

# B-139 — UPnP searches every interface, and checks a refusal

Two defects stood between a real netis router with UPnP switched on and a mapped port. Reported as
[youndie/kachok#76](https://github.com/youndie/kachok/issues/76).

**The search left through the wrong adapter.** `UpnpMapper.discover` sent its `M-SEARCH` from one
unbound `DatagramSocket`, so the operating system picked the interface for `239.255.255.250`. The
Windows machine had Wi-Fi `192.168.1.102` on the router's network and four adapters holding
link-local `169.254` addresses (Ethernet, two virtual Wi-Fi, Bluetooth). The search went out through
one of those and nothing answered. The same search, `ssdp:all`, from a socket bound to the Wi-Fi
address drew eleven answers from `192.168.1.1`.

**The router's refusal was not the truth.** Once found, the netis answered the first
`AddPortMapping` for a port with UPnP error 501 ActionFailed, and made the mapping anyway.
`GetSpecificPortMappingEntry` straight after showed it pointed at this machine, and a second
identical request was answered OK. The pattern was measured over eight add, re-add, delete rounds:
501, OK, OK every time.

- **The decision and its reason.** The `M-SEARCH` goes out from every up, non-loopback,
  multicast-capable IPv4 address that is routable (`Upnp.routable`: not `0/8`, `127/8` or
  `169.254/16`), with `IP_MULTICAST_IF` set to that address's interface. It is also sent unicast to
  the default gateway's port 1900, which most routers answer and which needs no interface chosen.
  Replies are read from all of those sockets until the deadline. A refusal of `AddPortMapping` is
  then checked with `GetSpecificPortMappingEntry`: if the router's own table forwards the port to
  this machine, the port is mapped, whatever the refusal said.
- **The alternative that was rejected.** Retrying `AddPortMapping` after a 501. It works on this
  router because the second request finds the mapping already there, but it is a guess about one
  firmware's behaviour. Reading the table is the router saying what it actually did.
- **Driven, with a control.** `./gradlew :engine:portMapProbe -Pport=6899` against the netis, which
  maps and then releases the port:

  | Build | Result |
  |---|---|
  | `main` before this item | `NOT MAPPED after 7037ms: … does not answer NAT-PMP, and no UPnP gateway answered` |
  | the search on every interface, refusal believed | `NOT MAPPED after 4333ms: … refused with UPnP error 501` |
  | this item | `MAPPED: external port 6899 for 7200s (4287ms)` |

- Not covered, and outside the client: that router's own WAN address is private (`192.168.x.x`), so
  it sits behind another NAT. A mapping on it forwards nothing from the internet until the upstream
  router forwards the same port. Saying so where an agent can see it is
  [#77](https://github.com/youndie/kachok/issues/77)'s business.

- AC: on a machine where the router is reachable only through one of several adapters, or only by
  being asked directly, the router is found; a refusal the router's table contradicts is reported as
  a mapping, and one it confirms stays a refusal with the router's words. **Met.**
  **Automated:** `engine/src/jvmTest/.../nat/UpnpMapperTest.kt` —
  `theGatewayAskedDirectlyIsFoundWhenTheGroupIsSilent`, `aSearchFromAListedAddressIsAnswered`,
  `nobodyAnsweringEndsWithinTheWindow`, `aRefusalTheRoutersTableContradictsIsAMapping`,
  `aRefusalTheTableConfirmsIsReported`; `engine/src/commonTest/.../nat/UpnpTest.kt` —
  `onlyAnAddressOnARealNetworkIsRoutable`, `aMappingEntryNamesItsTarget`.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/nat/UpnpMapper.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/nat/Upnp.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/nat/PortMapper.kt`.
