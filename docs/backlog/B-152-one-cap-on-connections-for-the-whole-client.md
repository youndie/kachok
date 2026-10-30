---
id: B-152
title: "One cap on connections and one on connection attempts in flight, for the whole client"
status: done
priority: P2
size: M
stage: m3-storage
epic: feature-download
blocked_by: [B-151]
---

# B-152 — One cap on connections for the whole client

Seen right after B-151: three downloads held 103 connections for 1.2 MiB/s, with 894 addresses known,
and the nine paused torrents beside them would each have taken up to 250 more the moment they were
resumed. What a home router pays for is less the rate than the state: a socket per connection, and
an entry in its NAT table per connection attempt — held for up to the connect timeout, and on a
public swarm most attempts never answer (research D14; B-118 measured 342 half-open sockets and 37
failed attempts a second on one machine).

- **The decision and its reason: two caps, both the client's.** `SetLimits.maxConnections` counts
  connections held, dials in flight included; `SetLimits.maxDialsInFlight` counts dials. The set's
  `SharedConnectionBudget` keeps both counts behind one lock and every session takes its slots from it:
  a dial takes a connection slot and a dial slot before it starts, a failed one gives both back, a
  successful one the dial slot, and the connection slot goes back when the connection closes. A peer
  that dials us takes a connection slot or is closed. Counting a dial as a connection from its start is
  what keeps a burst of dials from overshooting the cap by however many of them answer.
- **Shared out equally.** The set's loop divides `maxConnections` among the torrents that are
  transferring — downloading or seeding, not paused or queued — and sends each its share as
  `Command.Share`, never below 10. The session keeps the smaller of the share and its own `maxPeers`.
  Equally rather than by need, because need is what a share decides: a torrent with more peers than it
  can use would keep them, and one that has just started would get none.
- **A lower share closes the excess at once,** peers that are choking us first, then the slowest. A cap
  that only stopped new connections would take as long to bite as peers take to leave on their own,
  which on a good swarm is hours. They are closed as this client's decision, so they are neither
  counted as failures nor made to wait before they may be dialled again.
- **Defaults.** The engine's is no cap, as with every limit. A client a person runs starts with 200
  connections and 20 dials (`firstRunLimits`), and a `limits.properties` written before this — which
  has neither key — is read as a client that never chose, and gets the same.
- **Every surface.** MCP `set_limits` takes `max_connections` and `max_dials`, and `network_status`
  says `connections: 103 of 200, dialling 12 of 20`. The wire's `Snapshot` carries the caps and the
  counts, `Request.SetLimits` changes them. The window's settings have *Connections, all torrents* and
  *Connection attempts at once*. All are kept in `limits.properties`.

- Not covered: a torrent's own `maxPeers` (the window's *Max peers*) stays as it was and still applies
  under the share. The dial cap is taken in turn by whichever session's tick comes first, which is
  fair over seconds rather than per dial.

- AC: dials and connections stop at the client's caps and come back as slots free. **Met.**
  **Automated:** `engine/src/jvmTest/.../runtime/SharedConnectionBudgetTest.kt`,
  `engine/src/commonTest/.../session/SessionTest.kt` — `theClientsConnectionCapStopsTheDialsTheSessionWouldMake`.
- AC: a smaller share closes the least useful connections at once. **Met.** **Automated:**
  `SessionTest` — `aSmallerShareClosesTheLeastUsefulConnections`.
- AC: set, read and kept through MCP; an older file gets the recommended caps. **Met.** **Automated:**
  `control/src/test/.../mcp/McpServerTest.kt` — `theClientsLimitsAreSetAndReadBack`,
  `control/src/test/.../store/StoredLimitsTest.kt` — `aFileWithoutTheConnectionCapsGetsTheRecommendedOnes`.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/peer/ConnectionBudget.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/SharedConnectionBudget.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentSet.kt`,
  `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/session/Session.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/store/StoredLimits.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/mcp/McpServer.kt`.
