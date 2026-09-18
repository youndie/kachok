---
id: control
title: control (the ways in that are not a window)
type: service
module: control
tech_stack: [Kotlin 2.4 JVM, JDK 25, Gradle]
owner: unassigned
depends_on:
  - engine
  - wire
publishes:
  - "nothing of its own; it is linked into :cli and :ui"
---

# control

## 1. Responsibility

How something outside this process reaches the client running inside it. Two things, and from a
distance they are one thing:

* **the single-instance lock** — a loopback socket and a secret in the user's configuration
  directory, which is how a second launch hands its `.torrent` to the client that is already
  running rather than becoming a second one;
* **the MCP server** — the Model Context Protocol surface an agent drives, over the same eight
  task-shaped tools whichever process is holding the engine.

It owns no engine and starts nothing. Every entry point here is handed a `TorrentSet` that some
surface already built, which is what lets the same code answer an agent from inside the window and
from inside a headless process.

**Why it is a module and not part of a surface.** Until
[B-117](../backlog/B-117-one-client-for-the-window-and-the-agent.md) the MCP server lived in `:cli`
and the lock lived in `:ui`, which was right while each was used by one surface. After it, each is
used by both — the window has to *answer* MCP, the headless process has to *speak* the lock's
handshake — and `:ui` may not depend on `:cli` ([cli](cli.md) §1: the UI must be able to replace
that module). Two copies of a handshake is how the two halves of one protocol come to disagree.

## 2. API contracts

### The lock: one client per machine, and what a caller may be

A caller connects to the port named in `<config>/instance`, sends the secret on the next line, and
is hung up on if it is wrong. The client answers `kachok`. The caller then sends **one word** that
says what it is:

| Word | Then | Answer |
|---|---|---|
| `open` | one absolute path per line, until it closes | none; the paths appear in the window |
| `mcp` | JSON-RPC frames, one per line, until either end closes | `kachok/mcp`, then frames |

A client that is up but has no engine yet — a window between binding the lock in `main` and
building its `TorrentSet` on the composition — answers `kachok/no-engine` and closes. That is a
different answer from *nobody is listening*, and the difference is the point: the caller can say
which happened instead of guessing.

A word this client does not know is answered by hanging up, which is what an older client does with
a newer launch's vocabulary.

**Security.** The secret is 128 bits from `SecureRandom`, rewritten on every bind, in a file in the
user's own configuration directory; the socket is bound on the loopback address and no other. The
surface is therefore *anyone who can read this user's files*, which is where the torrents are. It is
the same sentence [cli](cli.md) §2 carries for the WebSocket, with the secret narrowing it from
"anyone who can run a program as this user".

**A socket and not a lock file**, because a file cannot tell a running process from a crashed one,
and every scheme for noticing that — a pid, a heartbeat — is a new way to be wrong. A bound port
disappears when the process does.

### The MCP server

The tool set, the resource and the rules of the transport are [cli](cli.md) §2, which is where a
reader looks for them: they are the contract of the `kachok mcp` command whichever engine is
underneath. What belongs here is the one thing that is not visible from the command line — the
server is constructed per connected agent, over a `TorrentSet` it does not own, and writes its
frames through a function the caller supplies. That is what makes a relayed session and a spawned
one the same code: stdout in one case, a socket in the other.

## 2a. Code anchors

| File | What is there |
|---|---|
| `control/src/main/kotlin/io/github/youndie/kachok/control/SingleInstance.kt` | the lock, both halves of its handshake, and `McpRelay` — the attached session a headless process pumps |
| `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt` | JSON-RPC 2.0, the eight tools, the one resource |
| `control/src/main/kotlin/io/github/youndie/kachok/control/Snapshot.kt` | the engine's state as `:wire`'s, shared by the socket and the MCP resource so the two cannot drift |
| `control/src/main/kotlin/io/github/youndie/kachok/control/ConfigDirectory.kt` | where this user's kachok keeps things, on each platform |
| `control/src/test/kotlin/io/github/youndie/kachok/control/SingleInstanceTest.kt` | a stale file, a stranger on the port, a wrong secret |
| `control/src/test/kotlin/io/github/youndie/kachok/control/mcp/McpServerTest.kt` | the tools against a real download on `:swarm` |
| `cli/src/test/kotlin/io/github/youndie/kachok/cli/mcp/AttachTest.kt` | the two halves as two real things: what the window holds is what the agent lists |

## 3. How it is built

Nothing here constructs an engine, a dispatcher or a socket for peers. `McpServer` takes the
`TorrentSet`, the scope, the directory a torrent goes to when a call names none, the dispatchers and
a `write`; `SingleInstance` takes a configuration directory. Everything that decides how this client
behaves on the network was decided by whoever built the set.

`SingleInstance.agents` is the seam for the window: a `@Volatile` slot the surface fills once it has
an engine, and clears before it closes it. A connection that arrives while it is empty is told so.

## 4. Dependencies

| Kind | Name | What for |
|---|---|---|
| Module | [engine](engine.md) | the `TorrentSet` every surface hands over |
| Module | wire | `Snapshot` and the state types the resource serialises |
| Library | `kotlinx-serialization-json` | JSON-RPC frames, and the snapshot resource |
| Library | `kotlinx-coroutines-core` | tool calls run on the engine's scope |

## 5. Infrastructure and deploy

None of its own. It is a library linked into `:cli` and `:ui`, and ships inside whatever they ship.

## 6. Local setup

```bash
./gradlew :control:test
```

The MCP tools are exercised against `:swarm` — a tracker and a seeding peer on loopback — so the
claims are measured against a real download rather than a mock of one.

## 7. Configuration

None. The configuration directory is a parameter everywhere it is used, with the platform's real
answer as its default, so a test never writes into the developer's own.

## 8. Quirks

* **A connected agent gets a thread, and it is not the accept thread.** Handing three paths over
  takes microseconds, so the lock used to answer a caller on the thread that accepted it. An MCP
  session lasts as long as the agent does and `wait_for_completion` blocks for up to an hour, so a
  second agent would have waited for the first to go away.
* **The read timeout is cleared for `mcp` and not for `open`.** Two seconds is right for a launch
  handing over a path and wrong for a session that sits idle between tool calls. Zero means "until
  the other end closes", which is what the pipe an attached session stands in for does.
* **The mode word is new, and an older client does not know it — it reads it as a path.** Before
  B-117 everything after the acknowledgement was a path, so a client that has been running across
  an upgrade takes `open` or `mcp` for one and tries to open a torrent by that name, which fails
  where the file does not exist. The newer side is not left hanging: `kachok mcp` waits two seconds
  for `kachok/mcp`, does not get it, and builds an engine of its own. A window that has been up
  across an upgrade is the only place this happens, and restarting it is the whole fix.
* **`agents` is cleared before the set is closed, not after.** An agent connecting in between is
  told there is no engine, which is true, rather than handed one that is being torn down.
