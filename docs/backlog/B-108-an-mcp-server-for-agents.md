---
id: B-108
title: "An MCP server, so an agent can drive the client"
status: done
priority: P3
size: M
stage: phase-3-server
---

# B-108 — An MCP server, so an agent can drive the client

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

**Decided 2026-09-17 by the owner: the first — yes, now, over stdio.** The question is closed and
the item is work.

## Iteration 1 — 2026-09-17: built, driven by an agent, and what that found

**The server.** `kachok mcp`: JSON-RPC 2.0 on stdio, one frame per line, stdout for frames and
nothing else. Eight task-shaped tools over the same `TorrentSet` the WebSocket drives —
`add_torrent` (path or magnet), `list_torrents`, `torrent_status`, `wait_for_completion`,
`pause_torrent`, `resume_torrent`, `remove_torrent`, `set_file_priority` — and one resource,
`kachok://snapshot`, which is byte-for-byte the socket's payload because the snapshot builder was
lifted out of `Backend` and shared. The refusals are sentences with `isError`; an unknown method is
an error frame, and so is a line that is not JSON — never silence. Three tools that change state
wait up to two seconds for the session to confirm, because a tool that answered "paused" the moment
the command was *sent* was answering about the future, and the agent's very next call reads the
state. That one was a flaky test before it was a rule.

**Tested three ways, each catching what the others cannot.**

- *In-process, against a real swarm* (`McpServerTest`, the same `LocalSwarm` the socket is tested
  on): `add_torrent` then `wait_for_completion` answers "has finished" with the bytes on disk
  matching the seed — the acceptance's two calls, exercised. Plus refusals in words, a tier moved
  through the tool and read back in the status, the resource decoding as the wire's own `Snapshot`,
  removal with data, and the error frames.
- *Through the real process, by an independent client* — a Python JSON-RPC client written from
  the transport's rules and sharing nothing with kachok, spawning `cli mcp` and reading its stdout
  line by line. It would have died on the first non-JSON line. `initialize`, `tools/list`,
  `resources/read`, seven calls including three refusals, EOF, exit 0.
- *By Claude Code itself*, on the mac, with a one-shot `--mcp-config` (no standing configuration
  touched) and the prompt the acceptance names: *add this magnet and tell me when it has finished*.
  The session called `add_torrent` with the magnet, relayed its refusal in words when the swarm
  did not answer, fell back to the file as told, called `wait_for_completion`, then
  `torrent_status`, and reported every figure the tools gave it — save location included. **The
  agent flow the item is about works end to end.** On the second run, with a proper seeder, the
  magnet resolved: metadata came across BEP 9 from another kachok.

**What the third run found, and it is not this item's.** The download never finished. The seeder —
another `kachok mcp` holding the complete file — showed `128/128, uploaded 0`; the leecher showed
`1 connected, 16 requests out, 0 bytes`. Traced to the code: no live connection is ever given the
storage to serve from, and a request it cannot serve is dropped without a word. **This client has
never uploaded a block to a real peer**, on any surface, and no test asserted it had. Filed as
[B-110](B-110-this-client-never-uploads-a-block.md) at P1, with the trace and the table; it
re-frames M9's peer counts, all taken by a client the swarm had every reason to choke. A second
defect on the way — `download --seed` closes its set before it seeds — is
[B-109](B-109-download-seed-closes-the-set-before-it-seeds.md).

**Why this is `done` and not `wip`.** The item is the surface, and the surface is proven: a real
download completes through the tools when the other end serves (the in-process test), the
transport is correct under an implementation that shares nothing with it, and an agent drives it as
the acceptance describes. What did not complete in the agent's run was the *other kachok's*
upload, which is a defect this acceptance discovered rather than a gap in this server — the tools
reported it truthfully, which is what they are for. The claim "and does" holds against any peer
that serves; against kachok itself it waits on B-110.
