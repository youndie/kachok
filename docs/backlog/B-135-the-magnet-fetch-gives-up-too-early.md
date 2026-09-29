---
id: B-135
title: "The magnet fetch gives up too early: first tracker only, twenty dials, no DHT"
status: done
priority: P2
size: M
stage: m8-extensions
blocked_by: []
---

# B-135 — The magnet fetch gives up too early

Two rutracker magnets, each with one tracker (`http://bt*.t-ru.org/ann?magnet`), failed to resolve
through MCP on the first try with the same sentence:

```
the swarm did not hand over the torrent for that magnet: no peer answered with the metadata within 1m;
30 were asked, and the last dial said: 95.24.39.3:42863: Connect timed out
```

Both resolved on the second or third attempt once more trackers had been pasted into the link by
hand, and the running torrent then knew 689 peers. The swarm was there; the fetch did not reach it.
Reported as [youndie/kachok#60](https://github.com/youndie/kachok/issues/60).

`MetadataFetcher` had four limits, and each one was enough on its own:

- `announce()` returned on the **first tracker that answered**, so the rest of the link was never
  asked. Adding trackers helped only when the ones before them were dead.
- It dialled **the first twenty** peers of that answer **once**. When those twenty were behind NAT,
  nobody else was dialled for the rest of the minute.
- It never asked the **DHT**, although the set that called it had one running.
- "30 were asked" was the number the tracker returned. Twenty were dialled.

- **The decision and its reason.** Every source at once and a rolling set of dials. Each tracker in
  the link is announced to on its own coroutine, the DHT is asked for the info hash when the caller
  has one (`TorrentSet.dhtForLookups`), and peers are dialled as they arrive, at most twenty open at a
  time — a dial that ends, however it ended, frees its place for the next peer not yet tried. Halfway
  through the budget the trackers are asked once more, for a different sample of the same swarm.
  None of this is a new mechanism: it is the session's own shape (a queue of known peers, a cap on
  connections) applied to the fetch.
- **The failure says what was tried**: trackers that answered of those asked, peers the DHT found,
  peers dialled, how many were unreachable and how many answered without metadata to give, and the
  last failed dial. "The swarm is asleep" and "our dials cannot get out" read differently now.
- **`--extra-tracker <url>` on `kachok mcp`, off unless named.** The trackers are announced to while
  the metadata is fetched and kept on the torrent only when it is not private. BEP 27 says a private
  torrent talks to its own trackers only, and whether it is private is in the metadata being
  fetched, so before that point the client cannot know.
- **The alternative that was rejected.** A built-in list of public trackers added to every magnet.
  It fixes the reported case by accident — more trackers, more first answers — and leaves the
  twenty-dial limit in place for the next swarm, and it announces somebody's info hashes to trackers
  they never chose.
- Not covered: `kachok download` still fetches without the DHT. It builds its set only after the
  metadata is in hand, so there is no DHT running to ask; its comment claimed otherwise and now says
  so. The window and `kachok mcp` use theirs.
- Not covered: a setting for extra trackers in the window.

- AC: a magnet whose first peers are unreachable, or whose live peer only a second tracker or the DHT
  knows, resolves within the budget; the failure message states what was tried; an extra tracker
  stays on a public torrent and is dropped from a private one. **Met.**
  **Automated:** `engine/src/commonTest/.../metainfo/MetadataFetcherTest.kt` —
  `aLivePeerBehindTwentyFiveDeadOnesIsStillReached`, `aPeerOnlyTheSecondTrackerKnowsIsReached`,
  `theDhtIsAskedAsWell`, `theFailureSaysWhatWasTried`,
  `anExtraTrackerIsKeptOnlyWhenTheTorrentIsNotPrivate`; `cli/src/test/.../DownloadTest.kt` —
  `extraTrackersAreNoneUnlessNamedAndRepeatable`.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/metainfo/MetadataFetcher.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/MagnetFetch.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/Arguments.kt`.
