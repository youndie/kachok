---
id: B-33
title: "Fast extension (BEP 6): reject, have all/none, allowed fast"
status: done
priority: P2
size: M
stage: m8-extensions
epic: feature-download
blocked_by: [B-06]
---

# B-33 — Fast extension (BEP 6): reject, have all/none, allowed fast

`reserved[7] |= 0x04`. Its value for this engine is `reject`: a request that will not be served is
answered instead of silently dropped, which makes the picker's bookkeeping exact instead of
timeout-based.

- **The decision and its reason.** Implement the full set (ids `0x0D`–`0x11`) but use `reject`
  and `have all`/`have none` first; `suggest` and `allowed fast` are honoured when received and not
  sent in phase 1.
- Rejected: skipping it. Every major client has it, and without `reject` a choke means guessing
  which requests died.
- Not covered: nothing else.

- AC: with the bit set on both sides, a choke produces one `reject` per outstanding request; a
  seed's first message is `have all` instead of a full bitfield.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/wire/Message.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/wire/PeerWire.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`.

**Done.** All five ids, and the three that do something now do it: `reject` both ways, `have all` /
`have none` as the opening message, `allowed fast` honoured. `suggest` is honoured as a `have` —
which is what BEP 6 says it implies — and deliberately not as a preference, because ranking one
peer's hint above rarest-first and above the started pieces needs a rule for two peers suggesting
different pieces that this item has no data for. Neither `suggest` nor `allowed fast` is sent.

The acceptance criterion about a choke wanted "one reject per outstanding request", and outstanding
turned out to be a narrower set than it sounds: with no upload limit a request is answered as it
arrives, so nothing is ever outstanding and a choke rejects nothing. The queue that a rate limit
builds is the outstanding work, and that is what the test chokes.

Two things the item did not ask for. `SessionConfig.reserved` carries the **same array** the dialer
and listener send, rather than a boolean saying what they were told to send: BEP 6 and BEP 10 are
both two-sided, so the session must know what it advertised, and a second copy of that fact is a
second place for it to be wrong. And `Session` now takes its `Random`, because BEP 3 picks the
optimistic unchoke among *all* peers at random — without a seeded source a test can say that
somebody lost the slot but not who, and "one reject per request" needs to know who.
