---
id: B-111
title: "Two connections to the same peer: nothing drops the second, and endgame asks it for everything again"
status: done
priority: P2
size: S
stage: m9-swarm
epic: feature-download
---

# B-111 — Two connections to the same peer: nothing drops the second, and endgame asks it for everything again

Two kachoks on one segment end up with two TCP connections between them, and both sides treat
them as two peers. Seen in [B-110](B-110-this-client-never-uploads-a-block.md)'s acceptance, where
the whole file went across twice:

| | seeder | leecher |
|---|---|---|
| connections | 2 | 2 |
| peers as seen | `192.168.1.102:6882` (dialled), `127.0.0.1:54465` (accepted) | `127.0.0.1:6881` (dialled), `192.168.1.102:54217` (accepted) |
| bytes | uploaded 120 000 | downloaded 120 000 |

of a 60 000-byte file. The leecher dialled the seeder off the tracker; the seeder heard the
leecher's local-discovery announce ([B-102](B-102-local-service-discovery.md)) and dialled it back.
Each side now holds one link it dialled and one it accepted, keyed by two different addresses,
and nothing in the session compares peer ids across them. BEP 3 is explicit that a client should
drop a second connection to a peer id it is already connected to; this one does not, and the
picker — which keys its bookkeeping by address — sees a second peer. Once every block is asked
for, endgame does what it is for and asks the "other" peer for the stragglers, which here is all
of them: the seeder honestly serves the file a second time to the client that already has it.

On a home LAN with two of this client this is the normal case, not an edge one: LSD finds the
other machine within seconds, whichever side dialled first.

- **The decision and its reason.** Drop a second connection to a peer id this session already
  holds, at the point the handshake arrives — keep the one that exists, close the newcomer, and
  say so in the disconnect reasons ([B-98](B-98-how-many-peers-does-this-client-meet.md)'s
  counters are the place). By peer id and not by address, because the address is exactly what
  differs: the same peer reached through loopback and through the LAN is one peer.
- **Own id first.** A connection whose handshake carries *this* session's peer id is the client
  dialling itself — a tracker or LSD handing back one's own address does that — and is the same
  rule's cheapest case.
- Rejected: teaching the picker that two addresses are one peer. The picker's key is the address
  on purpose (it is what the wire names); the duplicate should not reach it.
- Rejected: turning LSD off for the case. LSD found a real peer; the defect is what happened after.
- Not covered: two *distinct* clients behind one NAT sharing an external address — different peer
  ids, and correctly two peers.

- AC: [B-110](B-110-this-client-never-uploads-a-block.md)'s two-runtime test holds one connection
  per side after both discovery paths have fired, the seeder's `uploaded` equals the file's length
  exactly, and the dropped duplicate is counted under a named reason. A session offered its own
  peer id closes that connection the same way.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/jvmTest/kotlin/io/github/youndie/kachok/engine/runtime/KachokSeedsKachokTest.kt`.

## Iteration 1 — 2026-09-18: dropped at the handshake, with a tie both sides break alike

`Session.serve` now compares the arriving handshake's peer id with every held link before the
link exists: a match is closed and counted as `duplicate peer`, this session's own id is closed
and counted as `ourselves`, and either is given the same `failed` wait a refused dial gets so the
tracker or LSD handing the address out again does not turn into a redial every tick. The picker
never sees the second address; its key stays what the wire names.

**The rule is not "keep the one already held", and this is the finding of the iteration.** The
item said keep the existing and close the newcomer. Two clients on one segment that hear each
other's LSD announce at the same moment dial each other at the same moment, and each sees a
*different* connection first — so each keeps its own and closes the other's, both are closed, and
after `reconnectDelay` the same thing happens again. BEP 3 says drop the duplicate and leaves the
choice open; what both sides can compute from the two handshakes alone is the pair of peer ids,
so **the connection dialled by the lower peer id stays**, on both machines, whichever was first.
Two connections of the same kind — a peer reconnecting from a fresh port before the old socket is
noticed dead — keep the one already held, as the item said. The loser, when it is the held one,
is closed and lands in its own coroutine's teardown, which counts it under the same reason.

**Acceptance.** `KachokSeedsKachokTest` asserts the seeder's `uploaded` is the file's length
*exactly* — 60 000, where before it was 120 000 — that both counters agree, and that each side
holds at most one connection after both discovery paths fired; on the build machine, fresh result
file, one test, no failures. `SessionTest` adds four: a second connection of the same kind is
closed and counted; the tie between a dialled and an accepted connection goes to the lower id's
dial when the peer's id sorts below ours (the held one is closed) and to our own dial when it
sorts above (the newcomer is closed); a connection offering our own id is closed as `ourselves`.
The whole `:engine` suite and `./gradlew build` are green on the build machine.

- Not covered, still: two distinct clients behind one NAT — different ids, correctly two peers.
- Not covered, new: a redial to the losing address every `reconnectDelay` while the peer is held
  through the other. It is closed at the handshake and counted; it is not prevented, because the
  dial loop keys on addresses and does not know which id an address will answer with until it has.
