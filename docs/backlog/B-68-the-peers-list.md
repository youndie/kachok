---
id: B-68
title: "The peers list, which the session counts and does not name"
status: open
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

- AC: the *Peers* tab lists connected peers with address, client, flags and per-peer rate, and the
  list is one field on `SessionState` that serialises.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/SessionState.kt`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/details/DetailsPanel.kt`.
