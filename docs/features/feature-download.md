---
id: feature-download
title: Download — from a torrent to bytes on the disk, and back out to the swarm
type: feature
status: active
owner: unassigned
involved_services:
  - engine
  - cli
client_entries: []
api: []
tags: [phase-1, bep-3, bep-6, bep-10, bep-11, mse]
---

# Download

> **The behaviour described here is what is green today**, written after the fact rather than
> before it: every scenario names the test that covers it, and the ones that cannot be run on this
> stand say so instead of being ticked
> ([B-124](../backlog/B-124-the-download-has-no-feature-document.md)). The reasoning behind the
> shapes — one writer, pooled direct buffers, virtual threads — is
> [research §1](../research/research-architecture.md) and belongs there, not here.

## 1. Overview

A person gives this client a `.torrent` or a magnet link and a directory. It finds peers, asks them
for the pieces it has not got, verifies each one against the hash in the torrent, writes it to the
right place in the right file, and tells every peer it now has it. When it has them all it keeps
serving them until it is stopped. Everything a person sees while that happens — the row, the
details panel, the Files tab — is [feature-ui](feature-ui.md)'s; what survives a restart is
[feature-resume](feature-resume.md)'s; this document is the download itself and the swarm it
happens in.

## 2. Business rules

* **A piece is verified before it is written.** A piece whose SHA-1 does not match the torrent is
  counted as a hash failure and never reaches the disk; its blocks are asked for again.
* **Rarest first, and the first piece of a torrent at random.** A piece only one connected peer has
  is the piece the swarm is about to lose. Every client starting at piece 0 makes piece 0 the only
  piece anyone has, so the first one is drawn instead of chosen.
* **A started piece is finished before a new one is begun**, and no more than `maxStartedPieces`
  are open at once. That bound *is* the memory the download uses: a piece in flight holds
  `pieceLength / 16 KiB` pooled buffers.
* **In order is a mode, not the default.** Under `sequential` a piece-length of bytes at each end of
  every wanted file is asked for first — a player reads the header and the index before it can show
  a frame — and the rest is asked for lowest-first. Rarest-first stays the default: it is what every
  measured number assumes and it is what makes this client a swarm member rather than a guest.
* **A file's priority is a pool the picker empties first, not an order it follows.** Skip wins over
  raise: a file cannot be both not fetched and fetched first. A piece straddling two files takes the
  higher tier, because the swarm serves pieces and not files.
* **A request nobody answers comes back.** An outstanding request older than the timeout is freed
  for another peer, and the peer that never answered keeps its connection and loses the claim.
* **Endgame, and only at the end.** When every block is either had or already asked for, the same
  block is asked of several peers and the losers are cancelled. Availability is part of the
  condition: what endgame waits for is the last *reachable* blocks.
* **Encryption is offered, not demanded.** By default this client opens with MSE and dials again in
  the clear if the peer will not have it; `required` refuses a peer that cannot, `plaintext` is the
  control every encryption measurement compares against.
* **A peer is a connection, not an address.** A second connection from a peer already held is closed;
  which of the two survives is decided by comparing peer ids, so both ends drop the same one.
* **An address that never answers is dialled less and less often**, and earns its place back the
  moment it answers.
* **The rate limits are applied at the two ends they belong to**: an upload limit is what the
  unchoked peers share between them, and a download limit is applied by *not asking* rather than by
  reading slowly, because a request already sent is bytes already on their way.

## 3. Flow

```
.torrent ──▶ tracker announce ──┐
magnet ──▶ DHT / ut_metadata ───┴──▶ peer addresses ──▶ dial (MSE, then the clear)
                                                          │
                                     bitfield / have ──▶ picker ──▶ request
                                                          │
                        piece message ──▶ block (pooled direct buffer) ──▶ writer
                                                          │
                                     hash ──▶ matches? ──▶ gathering write ──▶ have to every peer
                                                     └──▶ hash failure, blocks asked again
```

One writer coroutine, one timer a session, one virtual thread per peer; the timer carries the
keep-alives, the `force()`, the request expiry, the choke pass, the peer exchange and the dialling.

## 4. Code anchors

| Service | Code |
|---|---|
| engine | `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/picker/PiecePicker.kt` — which block to ask which peer for |
| engine | `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt` — the session: peers, commands, the one timer |
| engine | `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/storage/BlockWriter.kt` — blocks in, verified pieces out |
| engine | `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/io/SocketPeerConnection.kt` — the dial, the handshake and MSE |
| engine | `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt` — what a surface builds instead of a session |
| cli | `cli/src/main/kotlin/io/github/youndie/kachok/cli/Download.kt` — the command, its progress line and its exit codes |
| swarm | `swarm/src/main/kotlin/io/github/youndie/kachok/swarm/` — the stand: a tracker, and seeds that hold what they are told to hold |

## 5. Scenarios (BDD / test cases)

### Scenario: A torrent downloads from a swarm and the bytes are the torrent's
* **Given:** a tracker naming one seed that holds the whole torrent, on loopback.
* **When:** `kachok download` is run against it into an empty directory.
* **Then:** the process exits `0`, the file on disk equals the seed's content byte for byte, and the
  seed was asked for at least one block — so the bytes came off the wire and not off the disk.
* **Automated:** `DownloadTest#aTorrentIsDownloadedFromALocalSwarmAndTheBytesMatch`

### Scenario: The piece the swarm is about to lose is asked for first
* **Given:** three peers, two of which are missing piece 7.
* **When:** the picker is asked for work.
* **Then:** piece 7 is what it hands out, before any piece two peers hold.
* **Automated:** `PiecePickerTest#theRarestPieceIsTakenFirst`

### Scenario: A piece only one peer of five has still arrives, from that peer
* **Given:** five seeds, one holding a single piece and the other four holding everything but it.
* **When:** the client downloads the torrent.
* **Then:** the download completes, the file matches, the rare piece was served by the one peer that
  had it, and no peer was ever asked for a piece it had not announced.
* **Automated:** `ARareSwarmTest#aPieceOnlyOnePeerHasArrivesFromThatPeer`

### Scenario: A started piece is finished before another is begun
* **Given:** a picker at rest with a peer holding everything.
* **When:** blocks are taken one at a time.
* **Then:** the blocks of the piece already begun are handed out before any block of a new piece,
  and the number of open pieces never exceeds the bound.
* **Automated:** `PiecePickerTest#aStartedPieceIsFinishedBeforeANewOneIsBegun`,
  `PiecePickerTest#theNumberOfStartedPiecesNeverExceedsTheBound`

### Scenario: The last blocks are asked of several peers and the losers are cancelled
* **Given:** every block of the torrent either had or already asked for, with peers still connected.
* **When:** another peer asks for work.
* **Then:** it is given a block somebody else already has outstanding, and when one of them answers
  the picker names the others so the session can cancel them.
* **Automated:** `PiecePickerTest#endgameAsksSeveralPeersAndNamesTheOnesToCancel`,
  `PiecePickerTest#beingAtTheStartedPieceBoundIsNotEndgame`

### Scenario: A request nobody answers is given to somebody else
* **Given:** a block asked of a peer that answers nothing.
* **When:** the request timeout passes and the session expires it.
* **Then:** the block is offered to another peer, the silent peer keeps its connection, and the same
  block is not offered to it again.
* **Automated:** `PiecePickerTest#aRequestNobodyAnsweredIsOfferedToSomebodyElse`,
  `PiecePickerTest#anExpiredRequestIsNotOfferedTwiceToTheSamePeer`

### Scenario: A piece that does not hash is thrown away rather than written
* **Given:** a session downloading from one peer, and a hasher that disagrees with the torrent.
* **When:** every block of a piece has arrived.
* **Then:** nothing is written to the storage, `hashFailures` is 1, and the piece is asked for again
  from its first block.
* **Automated:** `SessionTest#aHashMismatchIsCountedAndNothingIsWritten`,
  `PiecePickerTest#aFailedPieceIsAskedForAgainFromTheStart`

### Scenario: A verified piece is announced to every peer, once
* **Given:** a session with two peers and a piece whose blocks have all arrived.
* **When:** the writer verifies it.
* **Then:** both peers are sent `have`, the completed count rises by one, and the downloaded counter
  rises by the piece's length and never falls.
* **Automated:** `SessionTest#aVerifiedPieceIsAnnouncedToEveryPeerAndCountedOnce`,
  `SessionTest#theDownloadedCountOnlyEverRises`

### Scenario: In order means both ends of every file first
* **Given:** a torrent of several files and a client with `sequential` on.
* **When:** the picker is asked for work.
* **Then:** a piece-length of bytes at each end of each wanted file is requested before the pieces
  between them, ascending, and the rest follows lowest-first.
* **And:** an MP4 whose `moov` atom sits at the end opens in a player while the middle is still
  arriving — driven against a real swarm in
  [B-121](../backlog/B-121-sequential-does-not-serve-a-player.md), with the rarest-first run at the
  same 28 % as the control that cannot be opened.
* **Automated:** `PiecePickerTest#sequentialTakesBothEndsOfEveryFileFirst`,
  `PiecePickerTest#theTailCoversAWholePieceLengthWhereverTheFileEnds`,
  `CommandsEndToEndTest#sequentialFillsTheFileFromBothEnds`

### Scenario: A file nobody wants is never asked for, and one raised goes first
* **Given:** a running torrent whose Files tab changes a file to `skip`, and another to `high`.
* **When:** the picker begins its next pieces.
* **Then:** no piece belonging only to the skipped file is requested and the torrent stops owing
  those bytes; the raised file's pieces are offered before the rest, rarest-first inside that pool;
  and whatever was already started finishes either way.
* **Automated:** `SessionTest#aFileSkippedOnARunningSessionStopsBeingOwed`,
  `SessionTest#aFileRaisedOnARunningSessionIsAskedForFirst`,
  `PiecePickerTest#aSkippedPieceIsNeverAskedFor`, `PiecePickerTest#withinTheRaisedPoolTheRarestStillWins`

### Scenario: Two of these clients meet and speak MSE
* **Given:** two instances of this client, both with encryption `preferred`.
* **When:** one dials the other and asks for a block.
* **Then:** the handshake is the MSE one, both ends report the connection as encrypted, and a whole
  block arrives intact through the stream cipher.
* **Automated:** `EncryptedPeerTest#twoEndsOfThisClientNegotiateEncryptionAndAWholeBlockSurvivesIt`

### Scenario: A peer that will not speak MSE is dialled again in the clear
* **Given:** a peer that hangs up on anything that is not BEP 3's opener.
* **When:** this client dials it with encryption `preferred`.
* **Then:** the encrypted dial is abandoned, a plaintext dial follows, and the download proceeds.
* **And:** with encryption `required` the same peer is turned away instead.
* **Automated:** `EncryptedPeerTest#aPeerThatWillNotSpeakMseIsDialledAgainInTheClear`,
  `EncryptedPeerTest#aClientThatRequiresEncryptionTurnsAPlaintextPeerAway`

### Scenario: A finished download serves the next client
* **Given:** a client that has the torrent and was started with `--seed`.
* **When:** a second client asks it for the whole torrent.
* **Then:** the second client's copy matches, the first reports what it uploaded, and it goes on
  serving until it is interrupted.
* **Automated:** `DownloadTest#aSeedingDownloadServesASecondClientUntilItIsInterrupted`,
  `KachokSeedsKachokTest#theSeederServesEveryByteAndItsOwnCounterSaysSo`

### Scenario: Only so many peers are served at once, and the limits are shared
* **Given:** more interested peers than the client unchokes, and an upload limit.
* **When:** they all pull at once.
* **Then:** no more than the configured number are unchoked, and the limit is what they share
  between them rather than what each of them gets.
* **And:** a download limit is applied by asking for less, not by reading the socket slowly.
* **Automated:** `SessionTest#onlySoManyPeersAreServedAtOnce`,
  `SessionTest#anUploadLimitIsWhatFourPeersPullingAtOnceShareBetweenThem`,
  `SessionTest#aDownloadLimitIsAppliedByNotAskingRatherThanByReadingSlowly`

### Scenario: The same peer twice is one connection
* **Given:** a peer this client has already dialled, which dials back.
* **When:** the second connection completes its handshake.
* **Then:** one of the two is closed and counted, and which one is decided by comparing the peer ids
  so that both ends drop the same connection; a connection offering this client's own id is closed
  as itself.
* **Automated:** `SessionTest#aSecondConnectionFromAPeerAlreadyHeldIsClosedAndCounted`,
  `SessionTest#aTieBetweenDialledAndAcceptedGoesToTheLowerPeerIdsDial`,
  `SessionTest#aConnectionOfferingOurOwnIdIsClosedAsOurselves`

### Scenario: The swarm is topped up by the clock, not only by events
* **Given:** a client with addresses it has not tried and fewer connections than it wants.
* **When:** nothing happens — no announce, no message, no disconnection.
* **Then:** the timer dials anyway; an address whose dial is still outstanding is not dialled twice;
  and an address that never answers is tried less and less often until it answers.
* **Automated:** `SessionTest#theConnectionsAreToppedUpOnTheTimerAndNotOnlyWhenSomethingHappens`,
  `SessionTest#anAddressWhoseDialIsStillOutstandingIsNotDialledAgain`,
  `SessionTest#anAddressThatNeverAnswersIsDialledLessAndLessOften`,
  `SessionTest#aPeerThatAnswersLosesTheBackoffItHadEarned`

### Scenario: Peers introduce each other, and a private torrent does not
* **Given:** two peers connected to this client, both speaking BEP 10.
* **When:** a minute passes.
* **Then:** each is told about the other, the next message carries only what changed, and a torrent
  marked private offers peer exchange to nobody at all.
* **Automated:** `SessionTest#twoPeersLearnOfEachOtherWithinAMinute`,
  `SessionTest#theSecondMessageIsADeltaAndNotTheSwarmAgain`,
  `SessionTest#aPrivateTorrentNeverOffersPeerExchangeAtAll`

### Scenario: A tracker that refuses is quoted rather than summarised
* **Given:** a tracker answering with `failure reason`.
* **When:** the client announces.
* **Then:** the refusal is recorded in the tracker's own words, and the CLI exits non-zero with that
  sentence on stderr rather than a stack trace.
* **Automated:** `SessionTest#aTrackerRefusalIsRecordedInItsOwnWords`,
  `DownloadTest#anUnreachableTrackerExitsOneWithTheTrackersOwnWords`

### Scenario: A magnet becomes a torrent by asking a peer for it
* **Given:** a magnet link and a peer that serves the `info` dictionary over BEP 9.
* **When:** the client is given the link.
* **Then:** the metadata is fetched from the peer, its SHA-1 equals the info hash in the link, and
  the download proceeds as though the `.torrent` had been on disk; this client answers the same
  question for others, and rejects a request past the end of the metadata.
* **Automated:** `MagnetTest#aMagnetBecomesATorrentAndDownloadsIt`,
  `SessionTest#aPeerAsksForTheMetadataAndGetsBytesThatHashToTheInfoHash`,
  `SessionTest#aRequestPastTheEndOfTheMetadataIsRejected`

### Scenario: Asking in order costs the swarm something, and the number is not known
* **Given:** a swarm in which pieces differ in rarity, and two clients differing only in whether
  they ask in order.
* **When:** both download the same torrent.
* **Then:** the in-order client finishes later, and by how much is the figure this repository has
  never had.
* **Not automated, and deliberately not ticked.** The stand can now make a piece rare
  ([B-123](../backlog/B-123-a-seed-that-holds-part-of-the-torrent.md)), which is what was missing;
  what is still missing is a harness that runs two variants interleaved and publishes a ratio rather
  than a lonely number ([B-125](../backlog/B-125-a-measurement-that-is-a-pair.md)). Until then the
  cost is stated as a direction and not as a number.

### Scenario: This client meets as much of a public swarm as a mature one
* **Given:** a popular public torrent and a client left running beside qBittorrent on the same
  machine.
* **When:** both have been running for an hour.
* **Then:** the two have met comparable numbers of peers.
* **Not automated, and it cannot be.** A public swarm is not a stand: it is not reproducible, it is
  not this machine's to schedule, and a check that needs the internet to pass is a check that goes
  red for the weather. The figures taken by hand live in
  [research §2](../research/research-architecture.md) with their dates; the work is the `m9-swarm`
  stage.

## 6. Out of scope

* What a person sees — rows, tabs, dialogs, the tray: [feature-ui](feature-ui.md).
* What survives a restart — the record, the start-up check, the shutdown order:
  [feature-resume](feature-resume.md).
* Parsing `.torrent` files and computing the info hash: [feature-metainfo](feature-metainfo.md).
* µTP, IPv6, v2 torrents, super-seeding: backlog, unbuilt, and each says why.

## 7. Quirks

The traps live where they can be read beside the code that has them —
[services/engine.md](../services/engine.md)'s quirks section — rather than being copied here to
drift. The three worth knowing before reading any of the scenarios above:

* **A local swarm where every seed holds everything cannot answer a question about the picker.**
  Every seed having the torrent means no piece is rarer than any other, so rarest-first and in-order
  make the same requests in a different order. The stand can now be told what each seed holds; a
  measurement taken before that could not have meant anything.
* **`piece` is not `have`.** The download counter rises when a piece is *verified*, not when its
  blocks arrive, so a torrent whose last piece fails its hash goes backwards in pieces and not in
  bytes. That is the honest direction: the bytes did arrive, and they were wrong.
* **A rate limit on the seeding side and on the leeching side are different mechanisms.** Upload is
  shaped by a budget the unchoked peers share; download is shaped by asking for fewer blocks. A
  client that limited its download by reading its sockets slowly would hold the swarm's requests
  open and be choked for it.
