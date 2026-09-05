---
id: B-15
title: "HTTP tracker announce with compact peers"
status: open
priority: P0
size: M
stage: m4-download
epic: feature-download
blocked_by: [B-03]
---

# B-15 — HTTP tracker announce with compact peers

The first peer source. BEP 3's GET with `info_hash`, `peer_id`, `port`, `uploaded`, `downloaded`,
`left`, `event`, plus `compact=1` (BEP 23); the bencoded response's `interval` and `peers`.

- **The decision and its reason.** A `TrackerClient` interface in common with the request/response
  model; the JVM implementation on `java.net.http.HttpClient` — [../research/research-architecture.md](../research/research-architecture.md) D8. The info hash is
  percent-encoded byte by byte (it is not UTF-8). `failure reason` is surfaced as a typed error
  with the tracker's string.
- Rejected: Ktor client. One GET per interval does not justify a second HTTP stack in the run-time
  image; the interface makes the swap a phase-3 detail.
- Not covered: UDP trackers ([B-32](B-32-udp-tracker.md)); multi-tracker tiers (BEP 12) beyond
  "try each announce URL in order".

- AC: against a local HTTP server, the request line contains the percent-encoded hash and the
  bound port; a compact `peers` string of 12 bytes yields two peers; `failure reason` produces
  the typed error and no peers; the `stopped` event is sent on session close.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/tracker/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/tracker/`.
