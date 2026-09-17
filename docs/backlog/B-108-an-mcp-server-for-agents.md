---
id: B-108
title: "An MCP server, so an agent can drive the client — wanted at all?"
status: question
priority: P3
size: M
stage: phase-3-server
---

# B-108 — An MCP server, so an agent can drive the client — wanted at all?

The client has two ways in: the command line, and — since `serve` — a WebSocket on loopback that
speaks `:wire`'s `Request`/`Reply` in JSON and pushes the whole state once a second
(`docs/services/cli.md` §2). Both were built for a *person* at one end: the first through a shell,
the second through the browser build of the window. Neither is something an agent can be pointed
at and told "download this and tell me when it is done" — an agent speaks the Model Context
Protocol, and there is no MCP surface here, nor a mention of one anywhere in the repository.

The owner raised it with a question mark, and the question mark is the item.

- **What it would be, if it is anything.** A third entry point, `kachok mcp`, speaking MCP over
  stdio — an agent runtime launches the process and owns both ends of the pipe — and translating
  each tool call onto the same `Backend` the WebSocket already drives. Tools: add a torrent by path
  or magnet, list torrents, read one torrent's state, pause, resume, remove (with or without data),
  set which files are wanted; one resource: the snapshot. **Nothing new in the engine**: the
  WebSocket proved the backend is already a command surface, and this is a second protocol adapter
  on it, not a second backend.
- **Why stdio and not the socket that exists.** The WebSocket is guarded by `--origin` against
  pages and by nothing against programs — "anyone who can run a program as this user can drive
  this socket, and that is the decision". An MCP over stdio is narrower: only the process that
  launched it holds the pipe. That is the right shape for a tool an agent runtime spawns, and it is
  the shape every MCP client expects by default.
- Rejected in advance: an MCP that exposes the browser protocol's messages one for one. An agent
  wants "add this and wait", not `Request.Add` then polling `Snapshot.sequence`; the tool set is a
  smaller, task-shaped surface over the same backend.
- Rejected in advance: building it into the desktop window. The window is a person's; the agent's
  process is the headless one, which is where `serve` already lives.
- Not covered: the always-on box of Phase 3, where the natural transport is MCP over HTTP against a
  running service rather than a spawned process. That is a different transport on the same tool
  set and belongs to that phase.
- Not covered: authentication of any kind. Stdio has none and needs none; HTTP would, and is not
  this item.

- AC, if it is wanted: a Claude Code session with the server configured is told "add this magnet
  and tell me when it has finished", and does — through the tools, with no shell. The tool list is
  the third contract in `docs/services/cli.md`, beside the command line and the WebSocket, and each
  tool's failure is a sentence the agent can relay (a magnet that will not parse, a path that does
  not exist, a torrent already held).
- Anchors: `cli/src/main/kotlin/io/github/youndie/kachok/cli/serve/Backend.kt`,
  `cli/src/main/kotlin/io/github/youndie/kachok/cli/Main.kt`,
  `wire/src/commonMain/kotlin/io/github/youndie/kachok/wire/Protocol.kt`,
  `docs/services/cli.md`.

## The question, 2026-09-17

Whether this is wanted, and for what. Three answers, and they are the owner's:

- **Yes, now, over stdio** — the shape above, size M, no engine change. Its value is the use case:
  a torrent handed to an agent from a conversation, and progress reported back into it. If that
  use exists for the owner, this is a small item.
- **No.** The WebSocket already lets any local program drive the client, and an MCP adapter over
  that socket is something an agent could be asked to write from `cli.md` in an afternoon, in
  whichever language the agent runtime prefers. The client need not ship what its consumer can
  generate.
- **Later, with Phase 3.** MCP's natural home in this project is the always-on service, over HTTP,
  where "tell me when it is done" means a thing that survives the conversation ending. Doing it
  over stdio first would be doing it twice.

Until one of these is chosen the item stays `question` and the loop does not pick it.
