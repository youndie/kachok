---
id: B-95
title: "The client stops dialling: there is no periodic top-up, and a dial in flight is dialled again"
status: open
priority: P0
size: S
stage: m9-swarm
epic: feature-download
blocked_by: []
---

# B-95 — The client stops dialling: there is no periodic top-up, and a dial in flight is dialled again

`connectMore` is the only thing that dials, and nothing wakes it on a schedule. Its callers are an
announce, a DHT lookup, an incoming `ut_pex`, `AddPeers`, pause/resume/re-check/reconfigure, and a
peer disconnecting — every one of them an event. `timerLoop` runs keep-alives, `force()`, PEX,
choking, resume and request expiry, and does not dial. A failed dial records `failed[address]` and
returns without asking for a replacement.

What that produces on a real swarm is the sequence this client was measured in during
[B-19](B-19-end-to-end-download-acceptance.md): the first announce yields a few hundred addresses,
`room` is 50, fifty dials go out, and *"22 of 50 dials were stuck in `connect` while five
connections did the work"*. Five peers survive. The other forty-five addresses go into `failed`
with a thirty-second delay that nobody comes back to check, the remaining hundreds are never
reached at all, and the next top-up is the next announce — the tracker's interval, routinely
1 800 s. The client sits at five peers for half an hour while a reference client on the same
torrent is at its own cap within a minute. The comment in `Command.Reconfigure` already states the
consequence for one caller — *"the loop that would is the one that runs when a peer drops — an hour
away"* — and treats it as a property of that command; it is a property of the whole client.

The second half is what makes the first half unsafe to fix on its own. `connectMore` filters
`known` by `connected` and by `failed`, and there is no third set for *dialling*. An address whose
`connect` is in flight is in neither, so the next call dials it again. Today that is rare because
the callers are rare; a per-tick loop makes it the normal case. Two connections to one peer then
race in `serve`, `connected[address] = link` keeps the second, the first leaks with its coroutines
and its picker entry, and `connectedPeers` counts one where two sockets are open.

- **The decision and its reason.** Add a `dialling` set, entered before `runPeer` launches and left
  in its `finally`, and give `connectMore` a slot in `timerLoop` on `config.tick`. A swarm is not
  an event source — peers appear in `known` when a tracker or a peer chooses to say so, and go
  stale on nobody's schedule — so the thing that keeps the connection count up has to be the clock
  and not the swarm. The in-flight set lands in the same change because a periodic dialler without
  one dials every stuck address once a second.
- Rejected: calling `connectMore` from the failed-dial path instead. It fixes the observed case and
  not the rule — a client that dialled nobody because `known` was empty when the last event fired
  still never tries again — and it turns a batch of forty-five simultaneous failures into
  forty-five recursive calls.
- Rejected: dropping an address from `known` when it fails. A peer that was busy ten seconds ago is
  the peer that unchokes in a minute, and a client that forgets every address it could not reach
  keeps only the ones it did not need.
- Not covered: the cap itself. `maxPeers = 50` is [B-98](B-98-how-many-peers-does-this-client-meet.md)'s
  to measure, and it does not bind while the client is at five.
- Not covered: the dial rate. Fifty simultaneous `connect` calls is what this does today and what
  it will keep doing; whether a swarm wants that in bursts is a question for the measurement.

- AC: on a public torrent with several hundred known addresses and no tracker announce in between,
  the connection count climbs to `maxPeers` within a minute of the first announce and stays there
  as peers churn. A test with a dialer that never answers shows each address dialled once while its
  dial is outstanding, not once per tick.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`.
