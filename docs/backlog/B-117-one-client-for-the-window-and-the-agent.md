---
id: B-117
title: "One client for the window and the agent: `kachok mcp` attaches to the running window"
status: done
priority: P2
size: L
stage: phase-3-server
blocked_by: []
---

# B-117 — One client for the window and the agent

Start the window, then let an agent runtime spawn `kachok mcp`, and there are **two clients on one
machine**: two `TorrentSet`s, two peer ports out of 6881–6889, two DHT nodes, two download
directories. Neither can see the other. A torrent the agent adds never appears in the window; the
five the window holds are not in `list_torrents`; and the agent's torrent dies with the
conversation, because the pipe closing stops that engine
([Mcp.kt](../../cli/src/main/kotlin/io/github/youndie/kachok/cli/mcp/Mcp.kt) runs until stdin
closes and then calls `set.close()`).

This is not a defect in either surface. It is [B-108](B-108-an-mcp-server-for-agents.md)'s decision
working as written — *"Rejected in advance: building it into the desktop window. The window is a
person's; the agent's process is the headless one, which is where `serve` already lives."* — and
[B-84](B-84-torrent-files-open-with-the-client.md)'s single-instance lock not covering a surface
that did not exist when it was built. The owner has reversed the first: **the agent and the person should be
looking at one client.**

- **The decision and its reason.** `kachok mcp` keeps its contract with the agent exactly — stdio,
  JSON-RPC, one frame per line — and gains a second thing it may be underneath: instead of building
  a `TorrentSet`, it asks the single-instance lock whether a client is already running, and if one
  is, **relays its frames into that process and its answers back**. One engine, whichever surface
  the person opened first. With no window running it builds the engine as it does today, so the
  headless use — a box with no desktop, an agent runtime and nothing else — is unchanged.
  `--standalone` forces that path on a machine where a window happens to be up.
- **Why the relay and not the WebSocket that exists.** `serve`'s socket already carries a client
  that is not in the process, and pointing the MCP adapter at it is the obvious move. It is worse
  here, for a reason the protocol makes plain: `:wire`'s `Request` is the *narrower* surface.
  `Request.AddMagnet` is answered with a refusal in
  [Backend.kt](../../cli/src/main/kotlin/io/github/youndie/kachok/cli/serve/Backend.kt) — *"a magnet
  has to be fetched from the swarm before it is a torrent, and this backend does not do that yet"* —
  there is no request for a file's priority at all, and `wait_for_completion` would have to be
  rebuilt out of `Snapshot.sequence` polling. So that route costs three protocol additions and
  leaves the tool layer existing **twice**: once over `TorrentSet`, once over the wire. The relay
  costs neither, because what crosses the socket is the agent's own frames and the thing that
  answers them is the same `McpServer` class either way.
- **Why the lock's socket and not a new one.** `SingleInstance` — then in
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/`, and moved by this item —
  already is a loopback socket, a 128-bit secret in the user's configuration directory, and a
  handshake that hangs up on a caller who does not know the word — built because *"a bound port
  disappears when the process does"*. A second socket with the same job is a second thing to get
  wrong, and its stale-file problem would be solved the same way twice.
- **The module, and why the move is not optional.** `McpServer` is in `:cli` and `SingleInstance`
  is in `:ui`, and after this each surface needs both: the window has to *answer* MCP, and the CLI
  has to *speak* the lock's handshake. `:ui` cannot depend on `:cli` — `cli.md` §1 says the UI
  "must be able to replace this module" — so both move to a new JVM module, `:control`: the ways
  something outside this process reaches the running client. It takes the snapshot builders
  (`TorrentSet.snapshot`, `SessionState.onTheWire`) with it, because `McpServer`'s one resource and
  `Backend`'s tick are the same two functions and splitting them is how the browser's numbers and
  the agent's start to differ.
- **What changes for the person, and it is the point.** A torrent the agent adds appears in the
  window, with its row and its progress, and keeps downloading after the conversation ends, because
  the process holding it is the window's. A torrent the person added is in `list_torrents`.
- Deliberately **not** covered: authentication beyond the secret already in the lock file. The
  surface stays "anyone who can read this user's files", which is where the torrents are — the same
  sentence `SingleInstance` and `cli.md` §2 already carry.
- Deliberately **not** covered: MCP over HTTP against an always-on service. That is
  [B-87](B-87-a-server-with-a-web-face.md)'s transport on this same tool set, and this item is what
  makes there be one tool set for it to be.
- Deliberately **not** covered: `serve` growing the same attach. A headless backend and a headless
  MCP server on one machine is not a configuration anybody has asked for.

- AC: with the window open, an agent told *"add this magnet and tell me when it has finished"*
  through a `kachok mcp` server sees the row appear **in the window**, and `list_torrents` in the
  same session lists the torrents that were already there. The agent's session ends; the torrent is
  still downloading in the window. With the window closed, the same server does what it does today,
  and `kachok mcp --standalone` does it whether or not a window is running.
- AC: the flags that are the window's to decide — `--dir`, `--port`, `--no-dht` — are named on
  stderr as ignored when the relay is taken, and the process still starts. A server that refused to
  start would be one whose configuration in an agent runtime works only when no window is open.
- Anchors: `control/src/main/kotlin/io/github/youndie/kachok/control/SingleInstance.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/mcp/Mcp.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/Arguments.kt`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`,
  `docs/services/cli.md`, `docs/services/ui.md`.

## Iteration 1 — 2026-09-18: built, and what the move cost

**The module.** `:control` — the loopback lock and the MCP server, with the snapshot builders
(`TorrentSet.snapshot`, `SessionState.onTheWire`) and `configDirectory()` beside them. `McpServer`
came out of `:cli`, `SingleInstance` out of `:ui`, and both surfaces now depend on the module rather
than on each other. Four files in `:ui` and one in `:cli` changed only their imports; `Backend` lost
sixty lines to the shared snapshot and kept the tick.

**The protocol.** One word after the acknowledgement: `open`, and the lines that follow are paths, as
before; or `mcp`, and the socket is a JSON-RPC pipe. A client with no engine yet answers
`kachok/no-engine` and closes, which the caller can tell apart from nobody being there. Two details
that were not obvious until it ran: the connection had to move off the accept thread, because a
session lasts as long as its agent and `wait_for_completion` blocks for up to an hour, and the two-
second read timeout that is right for a path hand-over had to be cleared for a session that sits
idle between calls.

**The failure that is not a failure.** A frame is written from whichever engine coroutine finished
the call, so an agent that leaves in the middle of a long one makes that write throw. Unhandled it
carries an `IOException` about a departed agent into the window's own scope; it is swallowed at the
write, and the reading loop on the same socket ends on its own.

**Tested.** `AttachTest` stands the two halves up as two real things — a bound `SingleInstance` with
a `TorrentSet` behind it, and `Mcp.run` on a pipe, the same call `main` makes — and asserts both
directions: a torrent only the window was told about is in the agent's `list_torrents`, and a
torrent the agent adds is in the window's own set when the tool answers. `--standalone` is asserted
on a machine where the window *is* running, which is the only place the difference shows. Three
tests, all passing.

**What was not proven here, and it is not this item's.** `./gradlew build` on this machine fails
four tests — `DownloadTest.aSeedingDownloadServesASecondClientUntilItIsInterrupted` and three
`McpServerTest` cases that need a download to complete against another kachok. **They fail
identically on a clean worktree at `0f18e55`**, before any of this, so they are the tree's and not
the move's; every other test passes. Windows is research Risk 6 and this machine is Windows, which
is the first thing to rule out before reading them as a defect on `main`.
