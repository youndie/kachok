---
id: B-138
title: "One torrent whose drive is not there does not cost the rest of the list"
status: done
priority: P2
size: S
stage: phase-3-server
blocked_by: [B-136]
---

# B-138 — One missing drive does not cost the list

A headless `kachok mcp` started before drive `D:` had woken up. The first remembered torrent was
saved to `D:\Games`, and opening it threw
`FileSystemException: D:\Games: Unable to determine if root directory exists`. The loop that
reopens the list caught only `IllegalArgumentException`, so the exception ended it: none of the
nine torrents behind it was opened, `list_torrents` answered "No torrents", and nothing said why
except a stack trace in the MCP server's stderr log. Restarting the process once `D:` answered
reopened all ten. The window's loop was the same, and caught nothing at all. Reported as
[youndie/kachok#71](https://github.com/youndie/kachok/issues/71).

- **The decision and its reason.** `reopenStored` in `:control` is the one loop both use. Each
  torrent is opened on its own, and a failure is that torrent's. A refusal from the set
  (`IllegalArgumentException`: already open, or its files are another's) is reported and not retried,
  because retrying cannot change it. Anything else, such as a drive that is not there or a directory
  that cannot be created, is reported once and tried again every 30 seconds for five minutes, then
  every five minutes, for as long as the torrent is still in the list. Removing it while it waits is
  how a person says "stop trying".
- **Nothing is left behind by a failed open.** A torrent added to the set and then failing its
  check is removed again, or every later try would be refused as a duplicate.
- **The alternative that was rejected.** Waiting for every drive before opening anything. One
  unplugged disk would then hold the whole list back, which is the failure this item is about,
  delayed rather than avoided.
- Not covered: telling an agent which remembered torrents are still waiting. It reads as absent
  from `list_torrents` until it opens, and the reason is on stderr.

- AC: a remembered torrent whose directory throws on open does not stop the others from opening, and
  it is opened once its directory becomes available, without a restart; one removed from the list
  while it waits is not tried again. **Met.**
  **Automated:** `control/src/test/.../store/ReopenTest.kt` —
  `aDriveThatIsNotThereDoesNotStopTheOthersAndComesBackLater`, `aRefusalIsReportedAndNotRetried`,
  `aTorrentForgottenWhileItWaitsIsNoLongerTried`.
- Anchors: `control/src/main/kotlin/io/github/youndie/kachok/control/store/Reopen.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/mcp/Mcp.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/ClientModel.kt`.
