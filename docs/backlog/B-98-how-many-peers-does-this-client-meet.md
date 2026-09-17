---
id: B-98
title: "How many peers does this client meet? Measure it against a reference client, then set the cap"
status: open
priority: P1
size: M
stage: m9-swarm
epic: feature-download
blocked_by: []
---

# B-98 — How many peers does this client meet? Measure it against a reference client, then set the cap

The rest of this stage was found by reading the code after an owner reported that a mature client
on Windows showed several times as many peers on the same torrent. Every item in it names a
mechanism that plainly costs peers, and not one of them names a number. The ordering between them
is therefore a hypothesis: the dial loop
([B-95](B-95-the-dial-loop-only-runs-when-something-else-happens.md)) looks like the whole of it,
and it is entirely possible that after the fix this client is still short and the missing peers are
the ones that will only speak encrypted ([B-100](B-100-protocol-encryption.md)) or the ones that
can only reach a port nobody forwarded ([B-103](B-103-upnp-and-nat-pmp-port-mapping.md)).

`maxPeers = 50` is in the same position. It is the placeholder `SessionConfig` opens with — *"every
number here is a placeholder until B-26 measures it"* — and B-26 measured allocation on the hot
path, not the swarm. Fifty has never bound, because the client has never reached it.

- **The decision and its reason.** One public torrent with a large swarm, four runs of thirty
  minutes: this client before the stage, this client after it, and a reference client, each run
  twice. Record `knownPeers`, `connectedPeers`, dials attempted, dials that reached a handshake, and
  the reason each failure gave, sampled every ten seconds. The point of measuring before as well as
  after is that "it got better" is the claim least worth making — what the stage needs to know is
  which of six mechanisms the gap was actually made of, and only the before-run can say.
- **Two clients on one machine is not two independent samples**, and the design has to say so. They
  share one NAT binding, one uplink and one public address; a peer already connected to the
  reference client will refuse a second connection from the same address, so running them at once
  makes each look worse than it is. Run them one after the other on the same torrent within the
  same hour, and record the swarm size the tracker reported in each run so that a swarm that
  emptied between them is visible rather than invisible.
- Rejected: `swarm`, this repository's own test seeder, as the subject. It answers every dial
  immediately and has no address that refuses, which is the one property the measurement is about.
  It stays what it is — the harness for correctness, not for reach.
- Rejected: taking the reference client's peer count off its own window. Its number counts what it
  counts; the comparable figure is connections on the wire, from `netstat`/`ss` on the same
  machine, for both.
- Not covered: throughput. A client with more peers is not automatically a faster client, and
  conflating the two here would let a good bytes-per-second number close an item about reach.
- Not covered: choosing `maxPeers` before the runs. The number the measurement produces goes into
  `SessionConfig` with the run that produced it named beside it, the way every other default here
  is written.

- AC: the research document carries a table of the four runs with the five figures each, the swarm
  size each tracker reported, and a sentence naming which mechanism the gap was made of. The
  `maxPeers` default is either changed or explicitly kept, with the measurement cited either way.
- Anchors: `docs/research/research-architecture.md`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/SessionState.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt`.
