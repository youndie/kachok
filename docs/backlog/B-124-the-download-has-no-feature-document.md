---
id: B-124
title: "The thing this client is for has no feature document, so it has no scenarios"
status: done
priority: P2
size: M
stage: m4-download
blocked_by: []
---

# B-124 — The download has no feature document

`bdd_report.py` says 28 scenarios, 28 automated, 100 %. It says nothing about the download, because
the three feature documents are `feature-ui`, `feature-metainfo` and `feature-resume`: **the
picker, the swarm, the wire, seeding, encryption and per-file priority are not described anywhere
as behaviour a person can read and a check can count.** The tests exist and are good — `DownloadTest`
runs a real tracker and a real seeding peer, `CommandsEndToEndTest` drives five commands against a
real swarm — but a suite is not a specification, and "what is this client supposed to do when a peer
chokes it" is answered today only by reading Kotlin.

The cost is not theoretical. The owner asked for exactly this — *"нужно какие-то сценарии проверок
иметь"* — and the layer that would hold them has been in the repository since M0, empty for the one
feature the repository is about.

- **The decision and its reason.** One `docs/features/feature-download.md` covering the download
  path end to end, in the shape the other three already have: overview, business rules, flow, code
  anchors, and **scenarios with `**Automated:**` lines naming the test that covers each**. It is
  written against what is green *today* — nothing is invented and no scenario is ticked that is not
  run — so the first version is a description of the client that exists, and every gap it exposes
  becomes a line in this item rather than an ambition in a document.
- **Why not one document per subsystem.** Because a download is one behaviour: the picker's choice
  becomes a request that becomes a block that becomes a verified piece that becomes a `have` to
  every peer. Splitting it into four documents would put the interesting rules — the ones that span
  two of them — in none.
- The alternative that was rejected: generating scenarios from the test names. A name is not a
  scenario: it says what was asserted, not what was promised, and the promise is the half that
  catches a test asserting the wrong thing.
- Not covered: scenarios that cannot be run yet because every seed on the stand holds everything
  ([B-123](B-123-a-seed-that-holds-part-of-the-torrent.md)). They are written and left unticked,
  which is what an honest gap looks like here.

## What writing it against the code found

**21 scenarios, 19 of them automated by tests that already existed.** The client was better covered
than the documentation could show: `bdd_report.py` went from 28 scenarios to 49 without a line of
Kotlin being written, because the checks were there and nothing said what they were for.

The two that are not ticked are the interesting half, and both are honest rather than pending:

* *Asking in order costs the swarm something* — the stand can now make a piece rare
  ([B-123](B-123-a-seed-that-holds-part-of-the-torrent.md)), and what is still missing is a harness
  that runs two variants interleaved and publishes a ratio
  ([B-125](B-125-a-measurement-that-is-a-pair.md)). Until then the cost is written as a direction
  and not as a number.
* *This client meets as much of a public swarm as a mature one* — **and it cannot be automated at
  all.** A public swarm is not reproducible, is not this machine's to schedule, and a check that
  needs the internet to pass goes red for the weather. Recorded as unautomatable rather than left
  looking like work somebody forgot.

Every cited test was checked to exist by name before the document was committed — a scenario naming
a test that does not exist is worse than one with no `**Automated:**` line at all, because it counts
as covered.

- AC: `feature-download.md` is `status: active` on `main`, `make check` passes with it, every ticked
  scenario names a test that exists, and `bdd_report.py` counts the new document; the scenarios that
  need a rare piece are listed and explicitly not ticked. **All met.**
- Anchors: `docs/features/feature-download.md` (target),
  `cli/src/test/kotlin/io/github/youndie/kachok/cli/DownloadTest.kt`,
  `ui/src/desktopTest/kotlin/io/github/youndie/kachok/ui/session/CommandsEndToEndTest.kt`,
  `engine/src/commonTest/kotlin/io/github/youndie/kachok/engine/picker/PiecePickerTest.kt`.
