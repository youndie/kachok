---
id: B-120
title: "A test asserts the order of paths handed over by two separate launches, which nothing guarantees"
status: done
priority: P2
size: XS
stage: phase-3-server
blocked_by: []
---

# B-120 — Two launches have no order between them, and a test insists they do

`SingleInstanceTest.everyPathHandedOverArrives` makes two hand-overs and asserts one total order
over what arrives:

```kotlin
assertNull(claim("/srv/one.torrent", "/srv/two.torrent"))
assertNull(claim("/srv/three.torrent"))
val arrived = listOf(await(first.opened), await(first.opened), await(first.opened))
assertEquals(listOf(one, two, three), arrived)
```

Nothing in `SingleInstance` provides that order. `handOverTo` writes its paths, flushes and closes
— it does not wait to be told they were read — and since
[B-117](B-117-one-client-for-the-window-and-the-agent.md) the client answers **each connection on
its own thread**, so that an MCP session, which lasts as long as the agent, cannot make the next
launch queue behind it. Two connections are therefore read by two threads that race, and which one
reaches `opened.trySend` first is the scheduler's business.

It failed on CI in the release build of [#31](https://github.com/youndie/kachok/pull/31):

```
expected: <[/srv/one.torrent, /srv/two.torrent, /srv/three.torrent]>
but was:  <[/srv/three.torrent, /srv/one.torrent, /srv/two.torrent]>
```

Every path present, `one` still before `two`, and the third launch's single path in front —
exactly the shape the threading predicts. Reproduced locally at **1 failure in 15 runs**. Like
[B-119](B-119-the-outcome-outruns-the-buffers.md) it landed on an unrelated commit, which is the
second time in one day that a flaky test sent someone reading the wrong diff.

- **The decision: assert the order that exists, and await each launch before making the next.**
  One launch's own paths are ordered — `kachok a.torrent b.torrent` must open `a` first, and it
  does, because those paths travel down one connection that one thread reads in order. Across
  launches there is no order to assert: which of two processes lands first is not something either
  of them decides, and on a real double-click neither the user nor the client can say.
- **Rejected: serialising hand-overs in the client.** It would restore the total order and undo
  B-117's reason for the thread — an agent's MCP session would again hold the next launch behind
  it. The ordering is worth nothing and the cost is a launch that hangs.
- **Rejected: asserting set equality.** It would go green and stop checking the property that does
  matter, that one launch's paths keep their order.
- **Not covered: the hand-over is still fire-and-forget.** `handOverTo` returns once its write is
  flushed, so a launch cannot know its paths were opened, only that they were sent. That is the
  right trade for a process about to exit, and a different question from this one.

- AC: the old assertion reproduces at about 1 failure in 15 local runs; the replacement is green
  over the same 15 and cannot express the ordering that races.
- Anchors: `control/src/test/kotlin/io/github/youndie/kachok/control/SingleInstanceTest.kt`,
  `control/src/main/kotlin/io/github/youndie/kachok/control/SingleInstance.kt`
