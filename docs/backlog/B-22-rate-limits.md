---
id: B-22
title: "Upload and download rate limits"
status: done
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
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/choke/TokenBucket.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/Arguments.kt`.

**Done.** Two buckets in the session, refilled from the one timer, spent by bytes. Download: the
budget is asked *before* the picker, because `next` marks what it hands back as in flight and
taking more than the limit pays for would leave the picker holding blocks nobody is fetching until
the request timeout expired them. Upload: a request the budget cannot cover waits for the next
refill rather than being dropped — BEP 3 has no way to say "not now", and silence costs the peer a
timeout it did not earn. The queue is bounded, because its other end is somebody else's client.

The measured criterion holds: four peers each asking for more than the limit every second share
10 MiB over ten seconds, and all four are served. The first second is not counted — a token bucket
starts full, so the opening second is a burst by design.

Beside the item: `--up` and `--down`, in kibibytes a second, because a limit a user cannot set is
not a limit. Zero means *no limit* rather than no bytes, which is the one reading of the default
that would stop the client dead.
