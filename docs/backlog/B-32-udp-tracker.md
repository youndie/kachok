---
id: B-32
title: "UDP tracker protocol (BEP 15)"
status: done
priority: P1
size: M
stage: m8-extensions
epic: feature-download
blocked_by: [B-15]
---

# B-32 — UDP tracker protocol (BEP 15)

Most public trackers in `.torrent` files today are `udp://`; without BEP 15 the HTTP-only client
finds few peers.

- **The decision and its reason.** A second `TrackerClient` implementation on `DatagramChannel`,
  with the `connect` → `connection_id` → `announce` sequence and the retransmit schedule BEP 15
  prescribes. Blocking receive on a virtual thread, like the peers.
- Rejected: an async UDP framework. Same reason as the peers: a virtual thread is the framework.
- Not covered: the `scrape` request.

- AC: against a local UDP tracker fake, a wrong `connection_id` is ignored; the announce response
  yields peers; a lost packet is retransmitted on schedule.
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/tracker/UdpTrackerClient.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/tracker/UdpTrackerProtocol.kt`,
  `engine/src/jvmTest/kotlin/ru/workinprogress/kachok/engine/tracker/FakeUdpTracker.kt`.

**Done.** The protocol is common code and the socket is a JVM one, split so that every rule about
hostile or stale input can be tested without arranging it on a wire. `DatagramSocket` rather than
`DatagramChannel`: BEP 15 is made of timeouts and a blocking channel has none.

Two things beyond the item. A `udp://` tracker did not merely fail before this — it threw
`IllegalArgumentException: invalid URI scheme udp` out of `HttpRequest.newBuilder`, which is not a
`TrackerException` and so escaped the session's announce loop entirely; `TrackerClientByScheme` is
the fix and has its own test. And the fake tracker and the client were written from the same reading
of the same document, so they agree by construction: one announce to
`udp://tracker.opentrackr.org:1337` returned ten peers for `numwant=10` with plausible counts, which
is the only check here that a shared misreading could not pass (research §1.5a).
