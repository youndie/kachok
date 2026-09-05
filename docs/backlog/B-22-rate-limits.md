---
id: B-22
title: "Upload and download rate limits"
status: open
priority: P2
size: S/M
stage: m5-seeding
epic: feature-seeding
blocked_by: [B-21]
---

# B-22 — Upload and download rate limits

A client on a home connection has to be told how much of the uplink it may use.

- **The decision and its reason.** Token buckets in the session, refilled from the one timer;
  a peer's reader checks the download bucket before issuing requests and the writer checks the
  upload bucket before serving a `piece`. No per-peer limits in phase 1.
- Rejected: throttling at the socket level. Blocking a virtual thread's read does not stop the
  peer sending; not requesting does.
- Not covered: a per-torrent limit.

- AC: with an upload limit of 1 MiB/s and four unchoked fake peers pulling as fast as they can,
  the total served in 10 s is within 10 % of 10 MiB.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/session/`, `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/peer/`.
