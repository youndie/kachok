---
id: B-111
title: "Two connections to the same peer: nothing drops the second, and endgame asks it for everything again"
status: open
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
