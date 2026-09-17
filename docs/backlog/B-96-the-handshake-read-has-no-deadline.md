---
id: B-96
title: "A peer that accepts the connection and then says nothing is never given up on"
status: done
priority: P1
size: S
stage: m9-swarm
epic: feature-download
blocked_by: []
---

# B-96 — A peer that accepts the connection and then says nothing is never given up on

`SocketPeerConnection.connect` bounds the TCP connect and nothing after it. The call that follows —
`while (theirs.hasRemaining()) { if (socket.read(theirs) < 0) …` — is a blocking read on a
`SocketChannel` with no deadline, and `SocketChannel` does not honour `SO_TIMEOUT` even if one were
set. A peer that completes the TCP handshake and then sends no sixty-eight bytes holds that read
for as long as the connection stays open, which for a silent peer behind a stateful firewall is
until the firewall's idle timer fires — minutes, or never.

That address is then in none of the session's three states. It is not in `connected`, because
`serve` was never reached; it is not in `failed`, because nothing threw; and after
[B-95](B-95-the-dial-loop-only-runs-when-something-else-happens.md) it is not in `dialling` for
long enough to matter, because the dial never ends. So it is dialled again, and again, one virtual
thread and one socket per attempt, against a peer that will not answer either of them.

The case is not exotic. It is what a peer that requires encrypted connections does with a
plaintext BitTorrent handshake ([B-100](B-100-protocol-encryption.md)), what an overloaded client
does with a connection it has accepted and not yet looked at, and what a middlebox does when it
answers the SYN on the peer's behalf and has nothing to forward it to. `accept` has the same shape
and the same gap: `readHandshake` on a socket somebody else dialled waits just as long, and there
the caller is the listener's accept loop.

- **The decision and its reason.** Bound the handshake on both paths — a deadline across the write
  and the read, not a per-read timeout — and throw on expiry so the address lands in `failed` like
  any other refusal. The deadline belongs to the handshake and not to the connection: once a peer
  has identified itself, silence is what `keepAliveInterval` is for, and a deadline that kept
  running would close a healthy idle seed.
- Rejected: a watchdog coroutine per dial that closes the socket. It works — closing the channel
  ends the read — but it is one more coroutine per peer for a period that is not per-peer, which is
  the reasoning `timerLoop` already exists for.
- Not covered: how long. The connect timeout is 10 s by measurement; the handshake deadline is a
  guess until [B-98](B-98-how-many-peers-does-this-client-meet.md) has a swarm to try it on, and
  says so where it is written.
- Not covered: a peer that handshakes and then goes silent. That is the keep-alive's business and
  it is already covered.

- AC: against a fake peer that accepts the connection and sends nothing, the dial ends within the
  deadline, the address appears in `failed`, and no coroutine is left behind — asserted on the
  session's job children, not on a log line. The same against a peer that accepts and sends
  thirty-four of the sixty-eight bytes.
- Anchors: `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerConnection.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/PeerListener.kt`.

**Done 2026-09-17.** The handshake is read through the channel's own
`socket().getInputStream()`, which honours `SO_TIMEOUT`, with the timeout recomputed from a single
deadline before every read. Both entry points use it: `connect` for the dial and `readHandshake` for
the accept.

**The mechanism was probed before it was written into the code, because three of the four obvious
routes do not work.** A blocking `SocketChannel.read` ignores `SO_TIMEOUT` by specification.
`withTimeout` around it does not end it either — a virtual thread blocked in a socket read is not at
a suspension point, so cancellation has nowhere to land. A selector would work and is the one thing
this file has committed to not having. The adapted `InputStream` was the remaining door, and two of
its properties had to hold or the approach fails silently; both were measured on JDK 25.0.2 on
Linux and on macOS: a read expires at the deadline (401–405 ms for a 400 ms timeout), and it does
**not** read ahead — after taking exactly sixty-eight bytes, the next channel read returned byte
sixty-eight. The second one has its own test, `theFirstWireMessageAfterTheHandshakeIsNotSwallowedByTheAdaptor`,
because if it were false the symptom would be a peer that connects and then never says anything —
indistinguishable from the defect this item fixed, and invisible to a timeout test.

**A test that looked right and proved nothing.** The first version of the total-deadline test used a
peer that sent thirty-four bytes and stopped. `SO_TIMEOUT` is per read, so that peer expires it
anyway, one interval later: the test passed with and without the total deadline. What separates
them is a peer that keeps dribbling — a byte every 300 ms renews a 600 ms per-read timeout for ever.
That is what the test does now.

**Two mechanisms hold the total deadline, and the first mutation only moved one of them.** The check
at the top of the loop is what throws with a message naming how far the peer got; the shrinking
`SO_TIMEOUT` is what keeps the last read from overshooting by a further whole timeout. Replacing
only the second left the first enforcing the deadline, the test stayed green, and the mutation
looked like evidence that the test was weak. It was evidence that the analysis was. With both
replaced, the dribble test fails after the twenty seconds the peer needs to finish.

Mutations run, after the implementation was committed: `soTimeout = 0` — the suite **hangs** rather
than failing, which is exactly the defect's signature and the reason it was never noticed; a purely
per-read timeout — `aPeerThatDribblesTheHandshakeDoesNotRenewTheDeadline` fails and nothing else.

**One thing beyond the item.** The expiry is rethrown naming how many of the sixty-eight bytes
arrived. `SocketTimeoutException`'s own text is "Read timed out", and that string reaches a person
through `lastPeerError`, where "nothing at all" and "half a handshake" are worth telling apart.

The timeout is eight seconds and is a guess, marked as one where it is declared;
[B-98](B-98-how-many-peers-does-this-client-meet.md) is what gives it a number.

Ran on the Linux build machine: `./gradlew build` green, 633 tests across `:engine` and `:ui`,
0 skipped, 0 failed, counted out of the JUnit XML.
