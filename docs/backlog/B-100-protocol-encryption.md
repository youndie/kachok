---
id: B-100
title: "Protocol encryption (MSE/PE): the peers that will not talk in the clear"
status: open
priority: P2
size: L
stage: m9-swarm
epic: feature-download
blocked_by: [B-98]
---

# B-100 — Protocol encryption (MSE/PE): the peers that will not talk in the clear

This client speaks one dialect: `SocketPeerConnection` writes a BEP 3 handshake — the byte `0x13`,
then `BitTorrent protocol` — and reads one back. A peer configured to require an encrypted
connection sees neither what it expects nor anything it can answer, and either closes or, more
often, says nothing at all and leaves this client in the read that
[B-96](B-96-the-handshake-read-has-no-deadline.md) is about. Requiring encryption is a common
default on private trackers and a common setting on public ones, and it is invisible from the
outside: the address a tracker hands out looks exactly like every other address, so these peers are
counted in `knownPeers`, dialled, and silently lost.

There is a second reason beyond reach. The obfuscated handshake exists because middleboxes classify
BitTorrent by that literal `BitTorrent protocol` string in the first packet, and some networks
throttle or drop what they classify. A client that can only speak in the clear is a client whose
throughput on such a network is a property of somebody else's traffic shaper.

None of this is in the repository — not the code, not the research, not the backlog. It was never
rejected; it was never considered.

- **The decision and its reason.** Implement Message Stream Encryption as both dialler and
  accepter: Diffie-Hellman over the specification's 768-bit prime, RC4 over the negotiated key,
  and both modes of `crypto_provide`/`crypto_select` — plaintext after the handshake, and fully
  encrypted. Both modes and not just the cheap one, because the peers this item exists to reach are
  the ones that insist on the expensive one.
- **The accepting side is the harder half and is the half that pays.** A peer dialling this client
  may open with the plaintext `0x13` or with the first key of an obfuscated handshake, and the
  listener has to tell them apart from the first bytes without a length to go on. Getting that
  wrong does not lose a peer, it loses the plaintext peers as well.
- Rejected: dialling encrypted only, or plaintext only, chosen by a setting. A client that cannot
  fall back meets fewer peers than one that can, in one direction or the other, which is the
  problem this stage is about.
- Rejected: writing the RC4 by hand. The JDK ships it; the specification's own quirk — the first
  1 024 bytes of each keystream are discarded — belongs in a comment beside the line that discards
  them, not in a reimplementation.
- Not covered: the security of any of this. MSE is obfuscation and interoperability, not privacy;
  RC4 and a 768-bit prime are what the ecosystem agreed on in 2006 and what this has to speak to be
  understood. The research says so plainly, so that nobody later reads "encryption" as a promise.
- Not covered: tracker peer obfuscation, which is a separate mechanism about the announce rather
  than the connection.

- AC: this client connects, in both directions, to a mainstream client configured to *require*
  encryption, and still connects to one that does not offer it — the same test run twice against
  the same reference client with the setting flipped. The measurement of
  [B-98](B-98-how-many-peers-does-this-client-meet.md) is re-run and says what the encrypted peers
  were worth.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerConnection.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/PeerListener.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/wire/Handshake.kt`.
