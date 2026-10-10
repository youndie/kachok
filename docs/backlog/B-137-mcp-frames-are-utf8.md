---
id: B-137
title: "MCP frames are UTF-8 on every platform"
status: done
priority: P2
size: XS
stage: phase-3-server
blocked_by: []
---

# B-137 — MCP frames are UTF-8

On Windows every non-ASCII character in a `kachok mcp` reply reached the agent as a question mark —
`Added ???????.1999.WEB-DL.2160p.mkv`, and the same in `list_torrents` — while the file on the disk
was correctly `Матрица.1999.WEB-DL.2160p.mkv`. Reported as
[youndie/kachok#72](https://github.com/youndie/kachok/issues/72).

`main` passes `System.out` to the command, and `System.out` encodes with `stdout.encoding`: the
console's code page on Windows (cp1252, cp866…), UTF-8 elsewhere. JSON-RPC over stdio is UTF-8, so
every character outside the code page was replaced before it left the process.

- **The decision and its reason.** For `mcp`, a print stream is wrapped in a UTF-8 `PrintStream`
  (`frames()` in `Main.kt`). A `PrintStream` passes the bytes written to it through unchanged, so
  the descriptor carries exactly UTF-8, and every other command keeps printing for a human in the
  console's own encoding — which is right for them and wrong only for a protocol.
- **The alternative that was rejected.** `-Dstdout.encoding=UTF-8` in the start scripts. It fixes
  the scripts and nothing that starts the JVM another way — the run-time image, an IDE, a test —
  and it changes `download`'s output too, which a Windows console would then show as mojibake.
- Stdin was already read as UTF-8 (`bufferedReader()`), and the relay and the window's socket
  write `encodeToByteArray()`, which is UTF-8; neither changes.

- AC: a torrent named in Cyrillic comes back intact in an MCP reply on a stdout whose encoding is not
  UTF-8. **Met.**
  **Automated:** `cli/src/test/.../mcp/FramesAreUtf8Test.kt` — `aCyrillicNameSurvivesAStdoutInTheConsolesCodePage`,
  which fails without the change.
- Anchors: `cli/src/main/kotlin/io/github/youndie/kachok/cli/Main.kt`.
