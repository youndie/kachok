---
id: B-97
title: "The announce never says how many peers it wants, and only one tracker is ever asked"
status: open
priority: P1
size: S/M
stage: m9-swarm
epic: feature-download
blocked_by: []
---

# B-97 — The announce never says how many peers it wants, and only one tracker is ever asked

`AnnounceRequest.numWant` exists, `TrackerProtocol.announceUrl` appends `&numwant=` when it is set,
`UdpTrackerProtocol` writes it into the packet — and `Session.announce` never fills it in. Both
transports therefore ask for the tracker's default. That default is widely quoted as fifty and is
not verified here — confirming it against the trackers the test torrent names is this item's first
job — but whatever it is, it is a number chosen by somebody who does not know how many connections
this client is short of. If it is fifty, it is also `maxPeers`, and the very first thing the client
does is ask for exactly as many peers as it can hold, of which — by [B-19](B-19-end-to-end-download-acceptance.md)'s own
count — roughly one in ten answers. The field was added with the request object and never wired to
a caller; nothing failed, because a tracker that is asked nothing answers anyway.

The second half is smaller and is a choice rather than an oversight. `announce` walks
`metainfo.trackers` and `return`s on the first one that does not throw, which is BEP 12's rule and
is right as a default. What it cannot do is anything else: a torrent whose swarm is split across
trackers that do not share peers is one this client sees a slice of, and there is no way to say
"ask all of them" the way other clients expose as a setting. Underneath, `MetainfoParser.readTrackers`
flattens `announce-list` into one deduplicated list and drops the tiers, which is recorded there as
deferred — *"the tier semantics … arrive with the tracker layer if they ever earn their keep"*.
This item is where they earn it or stay gone.

- **The decision and its reason.** Send `numwant` on every announce — the number of connections
  this client is short of, not a constant — and keep the first-that-answers default while adding an
  *announce to every tracker* setting for the case the default cannot serve. Asking for the deficit
  rather than a fixed 200 is the reason for doing this at all: a client at its cap that keeps asking
  for hundreds of addresses is load on a tracker for a list it will not dial.
- Rejected: always announcing to every tracker. It multiplies this client's announces by the number
  of trackers a torrent happens to name, for peers that on a public torrent are largely the same
  peers, and BEP 12 asks clients not to.
- Rejected: restoring the tiers in `MetainfoParser` as part of this. The parser's list is the right
  shape for every other caller; tiers belong to the tracker layer, and a change of the metainfo's
  public shape is not something to fold into a query-string fix.
- Not covered: the `key` parameter and `trackerid`, which are about a tracker recognising this
  client across announces rather than about how many peers it hands back.
- Not covered: tier ordering and the shuffle. If the setting above is enough, the tiers stay
  flattened and the comment in `MetainfoParser` is amended to say so rather than left as a promise.

- AC: the announce URL of a client with five connections and a cap of fifty carries `numwant=45`,
  the UDP packet carries the same number in the same place, and a client at its cap sends
  `numwant=0`. With the setting on, a torrent naming three working trackers produces three
  announces and a `known` set that is the union of what they said.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/tracker/TrackerProtocol.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/tracker/UdpTrackerProtocol.kt`.
