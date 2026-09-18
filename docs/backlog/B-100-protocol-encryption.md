---
id: B-100
title: "Protocol encryption (MSE/PE): the peers that will not talk in the clear"
status: done
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

## Iteration 3 — 2026-09-17: the oracle answered, and the answer was no

`:engine:mseInteropProbe` dials a real client and checks that its BitTorrent handshake decrypts
through the stream this client derived. Run against qBittorrent 5.2.1 on the measurement machine,
holding `ubuntu-26.04-desktop-amd64.iso`, it **fails**: the peer resets the connection after this
client's third message.

That is the item working as designed. Four things are now established, and the order they were
established in is the point:

1. **The subject is real.** A plaintext BEP 3 handshake to the same port, for the same info hash,
   gets a full sixty-eight bytes back from `-qB5210-`. The torrent is active, the port is right,
   and the peer accepts connections. Without this control the reset would have been just as
   consistent with a paused torrent, and every conclusion below would have been about nothing.
2. **It is not the Kotlin.** An independent MSE dialler written in Python from the specification,
   sharing no code with this repository, is rejected in exactly the same way. Two implementations
   that disagree with a third party in the same place agree with *each other* about something that
   is wrong — which is what a second implementation is for, and what neither could have told me
   alone.
3. **The peer is not rejecting the connection itself.** Sending only `Ya` and padding, it replies
   with `Yb` and 379 bytes of `PadB` immediately and then holds the connection open, waiting. It is
   the third message it refuses.
4. **So the fault is in message 3, in a reading of the specification shared by both
   implementations** — `HASH('req1', S)`, the masked info hash, or the encrypted body's layout.

**What was not obtained, and it is the thing that would settle it.** libtorrent's own bytes as a
*dialler*. Three attempts to make qBittorrent connect to a listener failed for a reason that has
nothing to do with MSE: a process started from an SSH session on that machine dies with the session,
`x.pe` in a magnet is ignored for a torrent already held, and the scheduled-task route that worked
earlier did not this time. A listener that records what a correct client sends, checked against the
same derivation, would say in one connection whether `req1` matches — and therefore whether `S` is
right — and that is the next thing to do rather than another reading of the same paragraph.

The probe stays. It is the only test in this item that can fail for a true reason, and it should be
run before every claim that the encryption works.

Incidentally, from the same machine's log while it was up:
`UPnP/NAT-PMP port mapping failed. Message: "could not map port using UPnP: no router found"` — the
reference client tries to map its port on every start, which is
[B-103](B-103-upnp-and-nat-pmp-port-mapping.md)'s whole premise, observed rather than assumed.

## Iteration 4 — 2026-09-17: the previous iteration's conclusion was wrong

**Withdrawn: "the fault is in a reading of the specification shared by both implementations."** It
was drawn from one comparison — the Kotlin failed, the independent Python failed — without noticing
that the two also ran *from different machines*. Comparing at points that differ in more than the
one thing under test is how a confident wrong answer gets written down, and this one was written
down.

What replaced it is evidence. The independent Python implementation was turned round to act as the
**accepter**, and the Kotlin dialled it. It validates message 3 field by field:

```
SHA1('req1' + S) found at offset 344
req2^req3 matches: True
VC decrypts to zeros: True
crypto_provide = 0x00000003, len(PadC) = 206
len(IA) = 68, IA starts 13426974546f7272656e742070726f746f636f6c
```

`13` then `BitTorrent protocol`. **The dialling side's message 3 is byte-correct**, and with it the
derivation of `S`, of all five prefixed hashes and of the RC4 keys — none of which any test in this
repository could have said, because until now both ends were the same code.

### What is still unexplained, stated as what was observed

Against qBittorrent 5.2.1 the exchange still ends the same way: it replies with `Yb` and its
padding, then closes without sending message 4. Measured rather than assumed:

- It closes after 112, 117, 180, 229, 239 and 377 bytes of this side's scan across runs — not a
  fixed offset, and not the padding length.
- **From both source machines**, the mac and the Linux build box, and with both implementations.
  Running the Python from the second machine was how that variable was tested, and it failed there
  exactly as the Kotlin does.
- Sending only `Ya` and padding and then waiting, the peer holds the connection open indefinitely.
  It is message 3 it refuses — the same message an independent implementation reads correctly.
- **The positive control passes in the same minute**: a plaintext BEP 3 handshake to the same port
  for the same info hash gets sixty-eight bytes and a matching hash back. The peer is live, the
  torrent is active, and this negative result is about MSE rather than about a dead subject.
- Writing message 3 as one write rather than three changes nothing. The single write was kept
  regardless: three parts of one message given to a parser as three arrivals is a difference a pipe
  cannot show and a socket can.

**One run succeeded.** An inline dialler, run seconds after qBittorrent was restarted, received 260
bytes back and parsed `crypto_select` — so the protocol as implemented here *can* be accepted by
this client. Nothing since has reproduced it. That single success is the most informative thing on
this page and is the next thing to chase: what is true of a freshly started libtorrent that stops
being true a minute later.

The evidence still not obtained is libtorrent's own bytes **as a dialler**. Four attempts failed for
reasons with nothing to do with MSE — a process started from an SSH session dies with it, `x.pe` is
ignored for a torrent already held, and the scheduled-task route is unreliable. Until that capture
exists, this item's own probe is the only honest verdict: it fails, and the item stays open on it.

## Iteration 5 — 2026-09-17: a clean differential, and the hypotheses that died

The peer's state was ruled out first, because every earlier conclusion had been contaminated by it.
A plaintext connection to the same port is **fully** accepted — sixty-eight bytes of handshake and
then 3 119 more, a bitfield — so the client is not at a connection limit and not merely reflexively
answering. And an independent Python dialler now succeeds **seven times out of seven** from the mac,
at every padding length including 0 and the maximum 512. So neither padding length nor peer state
explains anything.

In the same minutes, from the same machine, the Kotlin probe fails every time. That is the clean
differential this item has needed: **the implementations differ, and the difference is in the
Kotlin.**

**Except that their messages are identical.** Both were run against the Python accepter, which
reports the same fields for each:

| | Python dialler | Kotlin dialler |
|---|---|---|
| req1 found | yes, at offset 120 | yes, at offset 344 |
| req2^req3 | matches | matches |
| VC decrypts to zeros | yes | yes |
| crypto_provide | `0x00000003` | `0x00000003` |
| len(PadC) | 40 | 206 |
| len(IA) | 68, opens `13 BitTorrent protocol` | 68, opens `13 BitTorrent protocol` |

Only the padding lengths differ, and those have been shown not to matter.

Hypotheses tested and dead:

- **The peer is full or the torrent is inactive** — no: plaintext gets a bitfield.
- **The padding length** — no: the Python succeeds at 0 and at 512.
- **The source machine** — no: the Kotlin fails from the mac and from the build box, and the Python
  failed from the build box on an earlier attempt and succeeds from the mac now.
- **Message 3 split across three writes** — no: made one write, no change. Kept anyway.
- **Message 1 split across two writes** — no: made one write, no change. Kept anyway.
- **The reading of the specification** — no: retracted in iteration 4 and now doubly so, since an
  implementation built from the same reading is accepted.

**The next step is the one that cannot fail to answer, and it has not been taken.** Both diallers
write their exact bytes to a file and the two are diffed. Everything so far has compared them
through a parser that agrees with both; comparing the wire itself does not. It is a small change to
the probe and it should have come before the last three hypotheses.

The interop probe still fails, so the item stays open. What can be said now that could not before is
that the failure is narrow: two implementations of the same protocol, sending fields a third
implementation reads identically, and only one of them accepted.

## Iteration 6 — 2026-09-17: the wire compared, and the item parked

The probe now dumps every read and write with `-Pdump=<file>`. The Kotlin dialler's wire:

```
W 394   Ya (96) + PadA (298)
R 96    Yb
W 454   req1 (20) + req2^req3 (20) + body (414)
R 1 …   the scan, one byte at a time, until the peer closes
```

The independent Python dialler's is the same shape: two writes, the first a key and its padding, the
second forty bytes of markers and an encrypted body. Only the padding lengths differ, and those are
proven not to matter.

**So every comparison available has now been made and every one of them says the two are the same.**
The fields, read by an independent accepter: identical. The structure on the wire: identical. And
the RC4 itself is transitively proven equal — that accepter decrypted the *Kotlin's* body with
*Python's* cipher and got `VC` as zeros and every field after it, which two different keystreams
cannot do.

What remains untested is not in the bytes:

- **Timing.** The JVM does `SecureRandom` seeding, a `modPow`, five SHA-1s and two 1 024-byte
  keystream discards between reading `Yb` and sending message 3, all of it cold. If libtorrent
  bounds that gap more tightly than its general handshake timeout, a slow dialler is refused whatever
  it sends. Measuring the gap in the probe is the next thing to try and it is one line.
- **libtorrent's own bytes as a dialler**, which five attempts have failed to capture for reasons
  unrelated to MSE, the last of them an LSD announce that drew no connection — itself a small finding
  for [B-102](B-102-local-service-discovery.md).

**The item is parked here, not abandoned.** What exists is real and tested: the primitives, the
handshake both ways, the plaintext discrimination, and a probe that fails honestly against a third
party. What is missing is one difference that six rounds of comparison have not located, and the
stage has three untouched items whose value does not depend on finding it. This is where a loop
should move on rather than grind, and the note above says exactly where to resume.

## The question, 2026-09-17

Six iterations, and the rule this backlog works to is explicit about what that means: three
attempts without an acceptance criterion moving is a question for a person, not a fourth attempt.
This is the sixth.

**What exists and is worth keeping whatever is decided.** The primitives, measured rather than
assumed. Both sides of the handshake, tested across padding lengths with the assertion that the
streams stay in step rather than that the handshake completed. The plaintext discrimination. A
probe that dials a third party and fails honestly. And a set of findings that are true regardless:
that a symmetric test cannot see a symmetric mistake, and that comparing implementations at points
which differ in more than one thing produces confident wrong answers — twice, here.

**What is not known**: why a message an independent implementation reads field-for-field correctly
is refused by libtorrent when this client sends it and accepted when the independent one does.

The three answers, and they are the owner's:

- **Keep going.** The next step is the timing measurement — the gap between reading `Yb` and
  sending message 3, cold on a JVM — and then capturing libtorrent's own bytes as a dialler, which
  needs a way to run a process on that machine that survives an SSH session. Neither is more than an
  hour, and either could end it.
- **Ship what there is, disabled.** The code is sound as far as anything can show; leave it behind a
  setting that is off, with the probe as the gate that turns it on. The cost is dead code with a
  known unknown in it.
- **Drop it.** [B-98](B-98-how-many-peers-does-this-client-meet.md) did not show encryption to be
  the bottleneck — 2 856 of 4 423 failed dials were `connect timed out`, which is
  [B-103](B-103-upnp-and-nat-pmp-port-mapping.md)'s problem and not this one. MSE's value here was
  always the peers that refuse plaintext, and nobody has counted them.

## Iteration 7 — 2026-09-17: the question is answered — it was the prime

**The prime.** Message Stream Encryption uses its own 768-bit modulus, and it is **not** RFC 2409's
group 1 — the reading six iterations were built on. The two agree for 180 of 192 hex digits (both
are π) and differ in the last twelve: RFC 2409 ends `…A63A3620FFFFFFFFFFFFFFFF`, MSE ends
`…A63A36210000000000090563`. libtorrent's `pe_crypto.cpp` and Transmission's `crypto.c` carry the
latter; this client and its independent Python check both carried the former, which is exactly why
they agreed with each other and with nothing else. `MseHandshake.PRIME_HEX` now holds MSE's value
and `Crypto.jvm.kt` reads it; the generator is unchanged.

**Proven against a real client, both ways.** A libtorrent 2.0.10 seeder was stood up on the build
machine with encryption forced, and `:engine:mseInteropProbe` dialled it:

| reference setting | `crypto_select` | result |
|---|---|---|
| `out_enc_policy=forced, allowed_enc_level=rc4` | RC4 | INTEROP OK — its handshake decrypted to the right info hash |
| `out_enc_policy=forced, allowed_enc_level=both` | plaintext | INTEROP OK |

The independent Python dialler, corrected to the same prime, is now `INDEPENDENT IMPLEMENTATION:
ACCEPTED` seven times running, where before it was refused after message 3 — the clean differential
iteration 5 asked for, resolved.

**The regression the six iterations could not have caught, now caught.**
`theSharedSecretIsComputedOverMsesPrimeAndNotRfc2409s` is a known-answer test: with fixed exponents
the two primes give different shared secrets, and the code's `agree` must match MSE's. Reverting
`PRIME_HEX` to the RFC value fails it — mutation-verified. This is the CI guard that does not need a
peer, and it exists because a symmetric test cannot see a symmetric mistake (research
[D15](../research/research-architecture.md#d15-mses-prime-is-not-rfc-2409s-and-a-real-router-was-built-to-prove-the-mapping)).

**What is done, and the one thing that is not.** The handshake, both sides, the primitives, the
plaintext discrimination, and interop against a real encryption-requiring client: done and proven.
What remains is wiring MSE into the **live** connection path — `SocketPeerConnection` still speaks
only plaintext, so a real download does not use any of this yet. It is a separate piece of work and
not a wrapper, because the zero-copy upload path (`FileChannel.transferTo`) cannot be RC4'd in the
kernel: an encrypted connection has to abandon it and copy each block through user space, which is a
change to the measured hot path. The dial path also needs a fall-back-to-plaintext-on-refusal, and
the accept path the first-byte sniff (`0x13` vs a key) that `Mse.looksPlaintext` already provides.
Then B-98's measurement is re-run to count what the encrypted peers were worth.

## The question, resolved 2026-09-17

The six-iteration question — *why a message an independent implementation reads correctly is refused
by libtorrent* — is **answered**: both implementations read the wrong prime, so "independent" was an
illusion of a shared misreading. The owner's three options collapse into one: **keep going**, which
was done, and it works against a real client.

The item stays `wip` rather than `done` for one honest reason: its acceptance names a real
connection ("this client connects, in both directions, to a mainstream client configured to require
encryption"), and the interop *probe* is not a real download — MSE is proven but not yet on the live
connection path. The remaining work is scoped in iteration 7 and is a candidate for its own item
(the hot-path integration is independent of everything else in this one). What is built is correct,
proven, and guarded; what is left is integration, not discovery.

## Iteration 8 — 2026-09-18: on the live path, and proven against a client that insists on it

**The wiring.** `Encryption` (`PLAINTEXT`, `PREFERRED`, `REQUIRED`) is what a caller asks for;
`SocketPeerDialer`, `RuntimeOptions` and `SetOptions` default to `PREFERRED` and the transport
primitive defaults to `PLAINTEXT`, because the policy belongs to the product and not to
`SocketPeerConnection.connect` — ten tests dial that function about something else. The dial sends
the BitTorrent handshake as MSE's `IA`, so an encrypted connection costs no extra round trip; a
peer that will not answer it is dialled again in the clear, and only when the *encrypted
handshake* was what failed. The accepting side reads twenty bytes and decides: BEP 3's fixed
header, or the top of a public key. Everything after the handshake goes through
`DecryptingChannel` and `EncryptingChannel`, which is where the cost lands — **an encrypted
connection cannot use `transferTo`**, so its blocks are read into a heap array, encrypted and
written, while a plaintext connection still hands the socket straight to the page cache.

**Acceptance, against qBittorrent 5.2.1 on the same machine, one 2 MiB torrent, a local tracker
and nothing else in the swarm:**

| | qBittorrent's setting | what happened |
|---|---|---|
| it dials us | **require encryption** | downloaded 2 097 152 bytes; its own peer row reads `client='kachok 0.1' flags='d E'`, and `E` is libtorrent's *Encrypted traffic* |
| we dial it | **require encryption** | `8/8 pieces (100%) … 1 encrypted`, file complete |
| we dial it | **encryption disabled** | complete, and no `encrypted` on the line: the fall-back, against a real client rather than a fake |

The count on the progress line is `PeerView.encrypted`, published per peer from the connection
itself, so what is asserted is what the socket did and not what was asked for. On the public
swarm the same build reads `1 encrypted` within a minute of starting, which is a real peer on the
internet and not a test.

**What the encrypted peers were worth, which is the measurement the acceptance asked for.** The
same public torrent as B-114, on the same Windows machine, three minutes, upload capped at
800 KiB/s, `--encryption preferred`:

| at | peers held | of them encrypted |
|---|---|---|
| 30 s | 22 | 19 |
| 90 s | 167 | **153** |
| 150 s | 117 | 105 |

Nine peers in ten on a public swarm talk to this client encrypted once it offers to. The run
downloaded 3.09 GiB in 172 s — 18.4 MiB/s, against 19.7 for the plaintext client of B-114 in the
same conditions on the same machine. One run each, and the difference is inside the spread the
reference client showed between its own two runs, so what this says is "the copy through user
space is not visibly expensive at this rate", not "it costs 1.3 MiB/s".

**Two defects this exposed, both older than it, both fixed here.** An encrypted dial is two round
trips and — against a peer that will not have it — a timeout and a second dial, so the window
between "the session stopped" and "this dial finished" grew from milliseconds to seconds. What
landed in it was a connection nobody closed:

- `runPeer` lost the connection when the dial finished into a cancelled session: `withContext`
  discards the value of a block that completed and throws instead, so the socket, its reader and
  its writer existed with no owner. The reader is a virtual thread inside a blocking read, which
  no cancellation reaches — the client printed `stopping` and never exited. It is held outside the
  `withContext` now and closed on cancellation.
- `serve` registered such a connection into the map `shutDown` had just emptied, and `shutDown`
  left any queued `AcceptPeer` unread. Both are closed now, and `SessionTest` has the first case
  as a test: a dialler that returns *after* the stop, and a connection that must come back closed.

**Not covered, deliberately.** The window has no setting for this — it takes the default like
everything else the settings screen does not draw; `--encryption` exists on `download` because the
measurement needed a control. And obfuscated *tracker* announces remain a separate mechanism.
