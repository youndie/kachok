---
id: B-34
title: "Peer exchange (BEP 11, ut_pex)"
status: done
priority: P2
size: M
stage: m8-extensions
epic: feature-download
blocked_by: [B-10]
---

# B-34 — Peer exchange (BEP 11, ut_pex)

Peers that tell each other about peers — the cheapest peer source and, for private trackers
(BEP 27), one that must be switched off.

- **The decision and its reason.** Send `ut_pex` every 60 s with added/dropped lists in compact
  form; honour `private = 1` in the metainfo by not advertising the extension at all.
- Rejected: nothing to reject; the extension is small.
- Not covered: IPv6 `added6` until [B-38](B-38-ipv6.md).

- AC: two fake peers connected to the client learn of each other within a minute; a private
  torrent never sends `ut_pex`.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/wire/PexMessage.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/peer/CompactPeers.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`.

**Done.** `ut_pex` every sixty seconds, as a delta each peer's link remembers — a message repeating
the whole swarm every minute would still be well formed and still parse, which is what makes that
the easy thing to get wrong. `dropped` is sent and not acted on: a peer this client is connected to
and enjoying is not dropped because somebody else lost it. Peers named in an incoming message go
into the same `known` set the tracker feeds and are dialled by the same rule — *and dialled at
once*, because the alternative, waiting for a connection to end, is never for a client whose peers
are all healthy.

BEP 27 is honoured by not offering the extension at all rather than by never sending it: a peer
that sees `ut_pex` in the handshake will ask.

Two things beyond the item. The address advertised for a peer this client *accepted* is not the
one it dialled from — that is an ephemeral port nothing listens on — but the `p` of its BEP 10
handshake; a peer that gave none is not advertised, because sending everyone to a dead port is
worse than telling them about one peer fewer. And `CompactPeers`: BEP 23's six bytes had three
copies — the HTTP tracker's `peers`, the UDP tracker's reply and now this — each with its own
`6` and its own loop. One object now, and the two trackers were changed to use it.

The end-to-end test of [B-10](B-10-extension-protocol-handshake.md) asserted that this client's
`m` is empty. It was true then and is not now; the assertion says what it offers instead.
