---
id: B-38
title: "IPv6 peers and trackers (BEP 7)"
status: done
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
- Anchors: `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/peer/CompactPeers.kt`,
  `engine/src/commonMain/kotlin/ru/workinprogress/kachok/engine/tracker/TrackerProtocol.kt`,
  `engine/src/jvmMain/kotlin/ru/workinprogress/kachok/engine/io/PeerListener.kt`.

**Done.** `peers6` is read beside `peers` — a separate function on a separate field, because
eighteen bytes read as six are three peers made of one peer's halves, and that is a bug that looks
like data. PEX's `added6` and `dropped6` are read too; neither is written, which is the item's
"beyond parsing" line and has a reason: sending `added6` means claiming something about this
client's own IPv6 reachability, and reading somebody else's answer needs no such claim.

The listener now binds the **wildcard** rather than `0.0.0.0`. On a dual-stack JVM that is `::` and
accepts both families as one socket. The old bind was a client announcing a port no IPv6 peer could
reach, and the failure was invisible from an IPv4 test — which is why the new test connects over
`::1` *and* `127.0.0.1` to the same listener.

Beside the item: an IPv6 host is printed in brackets. `2001:db8::1:6881` cannot be read back and
`[2001:db8::1]:6881` can, and these strings end up in error messages a person is meant to act on.
Addresses are compressed per RFC 5952 — longest run of zero groups, and never a single group.
