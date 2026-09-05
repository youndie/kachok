---
id: B-32
title: "UDP tracker protocol (BEP 15)"
status: open
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
- Anchors: `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/tracker/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/tracker/`.
