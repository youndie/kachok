---
id: B-119
title: "The Files tab called a file 100% and the bytes were not there"
status: done
priority: P1
size: M
stage: m6-resume
epic: feature-ui
blocked_by: []
---

# B-119 — A file the Files tab calls complete is not

Downloading a multi-file torrent with *sequential* on, the owner saw the first file at **100 %** in
the Files tab, the client moving on to the second — and the file would not play. qBittorrent, given
the same data, called that file **95 %**.

Where that number can come from is short, which is what makes this worth chasing rather than
guessing. The row prints
`verifiedBytes * 100 / length` with integer division, so it floors: 99.6 % prints as `99%`, and the
`complete` flag beside it is `verifiedBytes >= length` in bytes, deliberately not the rounded
percentage ([DetailsFrom.kt](../../ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/DetailsFrom.kt)).
`verifiedBytes` is
[`verifiedBytesPerFile`](../../engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/storage/FileProgress.kt)
over `picker.completed`, clamped to the file's byte range so a straddling piece credits each file
with the bytes it actually holds. So a 100 % row means **every piece of that file is set in the
picker's bitfield**, and that bitfield is written in exactly two places
([Session.kt](../../engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt)):
`pieceVerified`, after the hash matched *and* `storage.write` returned, and `picker.restore`, from
the resume record. The first cannot be wrong without the disk lying. The second never touches the
disk — it is the whole point of the record.

**One defect is visible by reading, and it is in the record.** Three of the four places that save a
record flush first, and say so in as many words — *"the record vouches for pieces that are hashed
*and* on the disk, so it is written after the flush and never before"* (`shutDown`, `pause`,
`recheck`). The fourth, the periodic save in `timerLoop`, runs on `resumeInterval` while `force()`
runs on its own `flushInterval`, so a record written by the timer can vouch for pieces whose bytes
are still only in the page cache. `ResumeRecord`'s own documentation states the invariant this
breaks: *"a record that vouched for a written piece would send the client back to a swarm with a
piece it does not have"*.

- **The decision and its reason.** The periodic save flushes first, like the other three. The
  invariant is then true at every call site rather than at three of four, and it costs one `force()`
  per resume interval — on a timer that already forces on its own schedule. Under-claiming after a
  crash costs a re-hash; over-claiming costs a client that tells its owner a file is finished and
  tells a swarm it can serve pieces it cannot.
- The alternative that was rejected: recording a watermark of "forced up to here" and trusting the
  record only below it. It is more bookkeeping for the same guarantee, and the thing it saves —
  a `force()` every resume interval — is not scarce.
- **What this does not explain, and must not be written up as though it did.** Page cache survives a
  process exiting, however it exits; only the host going down loses it. So this defect accounts for
  the symptom after a power cut or a hard reset, and not after an ordinary restart of the app, which
  is what the owner reports (*"возможно перезапускался kachok"*). Until a re-check has been run on
  that download the cause is **not** established, and the fix above is a broken invariant repaired,
  not a diagnosis.
## The case that prompted it cannot be measured, and that is the second finding

The one pass that separates the candidates is a **re-check**: it ignores the record and re-hashes
every piece from the disk, so a file that drops to 95 % means the picker was believing a record the
disk does not back, and a file that stays at 100 % while a player disagrees means the bytes
themselves are wrong. It was never taken — the download finished first, and a re-check on a complete
file says 100 % whatever went on an hour earlier. **The cause of the reported symptom is therefore
unknown and stays unknown.** The invariant above was broken and is repaired; whether it is what the
owner saw is not established, and page cache surviving an ordinary restart argues that it is not.

**So the second half of this item is making the next one measurable.** A re-check used to throw away
exactly the thing worth knowing: it learned which claimed pieces the disk could not show, and then
overwrote the claim with the truth and said nothing. It now counts them — `claimedNotOnDisk` in
`SessionState`, *Claimed, not on disk* in the details panel's INTEGRITY section, zero until a
re-check has run. Non-zero means one thing and only one: this client told its owner, and the swarm,
that it had pieces it had not.

There is also a plainer reading of the report that needs no defect at all, and it cannot be ruled
out either: sequential finished the first file honestly, the Files tab said 100 % about *that* one,
and the file that would not play was the second — 95 %, missing its tail, which is
[B-118](B-118-sequential-does-not-serve-a-player.md) and now fixed.

- AC: the periodic resume save happens after a flush, asserted by a storage that records the order
  it was called in; and a re-check says how many pieces this client claimed and could not show.
  **Both met.** The third thing this item wanted — the owner's re-check result — is gone with the
  download and is recorded above as not obtained.
  **Automated:** `engine/src/commonTest/.../session/SessionTest.kt` —
  `theRecordOnTheTimerIsWrittenAfterTheDiskIsForced`,
  `aRecheckSaysHowManyPiecesThisClientClaimedAndCouldNotShow`.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/resume/ResumeRecord.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/SessionState.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/DetailsFrom.kt`,
  `engine/src/commonTest/kotlin/io/github/youndie/kachok/engine/session/SessionTest.kt`.
