---
id: B-109
title: "`kachok download --seed` closes the set before it seeds, so nobody can reach it"
status: open
priority: P2
size: S
stage: m5-seeding
epic: feature-download
blocked_by: [B-110]
---

# B-109 — `kachok download --seed` closes the set before it seeds, so nobody can reach it

`Download.run` waits for the session to settle inside a `try` whose `finally` runs
`renderer.cancel()` and **`set.close()`** — and only *after* that does it reach the branch that
prints `seeding; stop with Ctrl-C` and waits. `TorrentSet.close()` releases the port mapping,
closes local discovery, **closes the listener**, closes the DHT transport, and closes every
runtime it holds. So the `--seed` that follows is a session whose set is gone: no listener, so a
peer dialling this client gets `connection refused`; runtimes closed, so there is nothing left to
serve a block from. The flag's whole promise — *keep seeding after the download completes* — is
not kept, and nothing says so: the process sits at `seeding; stop with Ctrl-C` looking exactly like
a seeder.

**Found by [B-108](B-108-an-mcp-server-for-agents.md)'s acceptance**, not by a test. A `kachok
download … --seed` was the seeder on one machine and an MCP-driven client the leecher; the leecher's
only known peer was the seeder and every dial was refused, and the magnet fetch failed for the same
reason. The tracker log showed the seeder announcing — a seeder that announces and refuses is the
worst kind, because it fills other clients' peer lists with an address that answers nothing.

- **The decision and its reason.** Move `set.close()` out of that `finally` and to the end of the
  command, after the seeding wait — so a set is closed when the *process* is done with it, not when
  the *download* is. The `finally` exists for the interrupt path, and that path already stops the
  runtime explicitly on the next lines; the set can be closed there too, in the same place, rather
  than for every path at once.
- **Then a test that would have caught it**, through the real path: a download with `--seed`
  finishes, and a second client on loopback connects to its listening port and gets a handshake
  back — the thing that is impossible today. `DownloadTest` already runs a real swarm; the missing
  half is dialling *into* the client after it says `complete`.
- Rejected: leaving the set open on every path and relying on process exit. A set holds a port
  mapping that has to be released ([B-103](B-103-upnp-and-nat-pmp-port-mapping.md)); an interrupt
  must still close it deliberately.
- Not covered: the window, which keeps its set for the process's lifetime and does not have this
  defect; and the MCP server, whose set lives until stdin closes — which is why it could stand in
  as the seeder B-108's acceptance needed.
- Blocked by [B-110](B-110-this-client-never-uploads-a-block.md): even with the set left open, a
  connection this client accepts has no storage to serve from, so the acceptance here cannot pass
  until that one does. Found in the same run, one layer down.

- AC: `kachok download <t> --dir <d> --seed` on a torrent already complete in `<d>` prints
  `seeding`, and a second client on the same machine, told the first's address by a tracker,
  connects, handshakes and downloads the file from it — counted by the second client finishing,
  not by the first one's log. The interrupt path still releases the mapping and stops cleanly.
- Anchors: `cli/src/main/kotlin/io/github/youndie/kachok/cli/Download.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `cli/src/test/kotlin/io/github/youndie/kachok/cli/DownloadTest.kt`.
