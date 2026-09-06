---
id: B-68
title: "The peers list, which the session counts and does not name"
status: done
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-54]
---

# B-68 — The peers list

`SessionState` carries `connectedPeers`, `unchokedPeers`, `outstandingRequests` and `knownPeers` —
four numbers and no identities. The design's *Peers* tab wants a row per peer: address, client
string, flags, rate.

- **The decision this needs.** What crosses the seam. `SessionState` is deliberately plain data
  that will go over a socket for the browser build ([B-40](B-40-wasmjs-ui-is-a-client-of-the-headless-engine.md)),
  so a peer list is a list of plain rows, not a handle on a connection — and it changes every
  second for fifty peers, which is a different shape of update from the rest of the state.
- Rejected in advance: exposing `PeerConnection`. It is a socket with a coroutine behind it; a UI
  holding one is a UI that can keep a dead peer alive.
- Not covered: acting on a peer — banning, unchoking by hand — which is a second interface.

## The decision, taken

**One field of plain rows, rebuilt on the session's timer and never on the hot path.** `PeerView` is
address, client, five flags, outstanding, pieces and two rates — no connection, no channel, nothing
a reader can hold. It is the one field whose cost grows with the swarm, and republishing fifty of
them every time a block arrived would be an allocation per block; the timer already runs at the rate
a table is redrawn at, so the list is at most a second old, which is what a table redrawn once a
second wants anyway.

**The per-peer rate was already there.** Each `PeerLink` has carried a `RateMeter` for the choker
since B-19; nothing new is counted, it is only published.

**`clientOf` reads BEP 20's convention and never trusts it.** Every step of the parse can fail —
a peer id is twenty arbitrary bytes and the specification says so — so the answer falls back to
whatever is printable and finally to `unknown`. It never returns an empty string: a row with nothing
in its client column reads as a rendering fault rather than as a peer that said nothing.

## Deviations, and why

- **`Transmission 4.0` where the reference writes `Transmission 4`.** Trailing zeroes are dropped
  down to *two* components, so `-qB5100-` is `qBittorrent 5.1` and `-LT2000-` is `libtorrent 2.0` —
  both as the reference has them. The mockup is inconsistent with itself here; a rule that produced
  `Transmission 4` would also produce `libtorrent 2`.
- **Ties keep the engine's order.** The first version broke them on the address, which is wrong
  twice: it is a text sort of IPv4, putting `5.181.190.7` after `45.83.220.66`, and it reordered the
  design's own nine rows. `sortedByDescending` is stable and the session's table is a
  `LinkedHashMap`, so the tiebreak is the order the peers connected in — which is what the
  reference draws.
- **An empty list says *No peers connected*.** A torrent with no peers and a torrent not yet sampled
  look identical otherwise, and the first is a thing to act on.
- **This client was announcing version 0.0.0.1.** `-KA0001-` had been the peer id since the engine
  was built, and nothing could see it until this tab existed. It is `-KA0100-` now, in the engine
  and the CLI both.

- AC: the *Peers* tab lists connected peers with address, client, flags and per-peer rate, and the
  list is one field on `SessionState` that serialises.
  **Automated:** `engine/src/commonTest/.../peer/PeerClientTest.kt` — every case is a peer id that
  is legal on the wire, including twenty zeroes and twenty control characters;
  `SessionTest.theSessionNamesItsPeersAndNotJustCountsThem` and `aPeerThatGoesLeavesTheList`;
  `ui/src/desktopTest/.../session/DetailsFromTest.kt` for the ordering, including
  `anAddressIsNeverUsedToOrderTheList`; `ui/src/desktopTest/.../details/PeersTabTest.kt` for the
  words and the legend; and the golden `details_planned-tabs.png`, compared row by row against
  `docs/design/screens/details-tabs.png`. Checked by hand against a live swarm host.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/SessionState.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt`.
