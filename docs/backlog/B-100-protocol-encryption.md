---
id: B-100
title: "Protocol encryption (MSE/PE): the peers that will not talk in the clear"
status: wip
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

## Iteration 1 — 2026-09-17: the primitives, probed before they were written

`engine/.../mse/` holds Diffie-Hellman over MSE's 768-bit prime and a running RC4 keystream, behind
interfaces with an `expect` factory. Not `expect class`: that is still Beta and this project
compiles with warnings as errors — a constraint worth recording, because the obvious shape for a
platform primitive with state is exactly the one the build refuses.

**Three things were measured on JDK 25 before a line of this was committed**, because all three
decide the design and two of them fail silently:

- The JDK's ARCFOUR matches RFC 6229's 40-bit vector, so nothing here is hand-written — which is
  what this item's decision said.
- `modPow` on the prime costs 3 ms. Once per connection, beneath notice.
- **`Cipher.doFinal` restarts the keystream; `Cipher.update` continues it.** A client using
  `doFinal` re-encrypts every message with the same keystream bytes. Talking to its own other half
  it is perfectly symmetric and every test passes; against any other client it is gibberish after
  the first message. The test asserts RFC 6229's *second* block explicitly for that reason, and the
  mutation confirms it: swapping `update` for `doFinal` fails
  `theKeystreamContinuesAcrossCallsRatherThanRestarting` and `discardingAdvancesTheStreamByExactlyThatMany`,
  and nothing else.

One more trap closed in passing: the public key is padded to 96 bytes by hand, because
`BigInteger.toByteArray()` returns the two's-complement form — 97 bytes whenever the top bit is set,
fewer than 96 whenever the value is small. Both are wrong on the wire, the first happens about half
the time, and the symptom is a peer that disconnects without a word. `everyKeyIsPaddedToTheFullWidth`
runs forty key pairs rather than one.

`MseHandshake` carries the key schedule — the five prefixed hashes, the obfuscated info hash, the
1 024-byte keystream discard — and a test asserts the five derived values are five *different*
values, which is the whole reason the prefixes exist.

**What is left, and it is most of the item.** The handshake state machine and the socket
integration: the five messages, the padding, `crypto_provide`/`crypto_select`, and the half that is
harder and pays more — an accepting side that must tell a plaintext `0x13 BitTorrent protocol`
from the first key of an obfuscated handshake with no length to go on. Getting that wrong loses the
plaintext peers as well, so it is the part that needs a test for both openings before it is wired
into `PeerListener`.

## Iteration 2 — 2026-09-17: the handshake, and what a symmetric test cannot see

Both sides are written, against a `ByteStream` rather than a socket, and tested with a pipe and two
threads. The hard part is that **neither side knows where the other's padding ends**, so both scan:
the accepter for `HASH('req1', S)`, which it can compute as soon as it has `Ya`, and the dialler for
the accepter's encrypted `VC`, which is the first eight bytes of that side's keystream and therefore
predictable exactly. Both scans are bounded — past the bound the peer is not speaking MSE, and
waiting longer is how a client hangs instead of failing, which is
[B-96](B-96-the-handshake-read-has-no-deadline.md) one protocol up.

A recorded byte script would have asserted the recording. The scan is what goes wrong and it only
misbehaves at particular padding lengths, so `theScanSurvivesEveryPaddingLength` runs twenty
exchanges rather than one, and the assertion is not that the handshake *completed* but that a
message sent through the resulting streams comes back — a handshake can complete with the two
keystreams one byte apart.

### The finding, and it is about the tests rather than the code

**Removing the 1 024-byte keystream discard changes nothing. Every test still passes.**

That is not a weak test suite, it is the shape of the problem: when both ends of the conversation are
this client, every *symmetric* property is invisible. The discard, the exact ASCII prefixes, the key
widths, the byte order of `crypto_provide` — get any of them consistently wrong and this client
still talks to itself perfectly. It is precisely the class of defect that ships.

So the tests here are worth what they are worth — they cover the asymmetric half, which is real: the
scan, the torrent lookup, the carried `IA`, the plaintext discrimination, the verification constant.
What they cannot do is decide whether this is MSE or merely an internally consistent protocol of its
own. **Only a third party can**, and the acceptance criterion already says so; what has changed is
that it is now the *only* thing that can close this item, rather than a nice confirmation at the end.

The oracle is at hand: the measurement machine of [B-98](B-98-how-many-peers-does-this-client-meet.md)
runs qBittorrent 5.2.1, whose own start-up log reports `Encryption support: ON`. The next iteration
wires the handshake into `SocketPeerConnection` and `PeerListener` and dials that client — which is
what will say whether the discard matters, and will say it in one connection.
