---
id: B-38
title: "IPv6 peers and trackers (BEP 7)"
status: open
priority: P3
size: S
stage: m8-extensions
epic: feature-download
blocked_by: [B-15]
---

# B-38 — IPv6 peers and trackers (BEP 7)

Compact peer lists have an 18-byte IPv6 form (`peers6`); the listener should bind both families.

- **The decision and its reason.** Accept `peers6`, dial IPv6 peers, bind a dual-stack listener
  where the platform offers one. Nothing else changes.
- Rejected: IPv6-only. No.
- Not covered: IPv6 in PEX (`added6`) beyond parsing.

- AC: a `peers6` string of 18 bytes yields one peer with an IPv6 address; the listener accepts
  a connection over `::1`.
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/tracker/`, `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/`.
