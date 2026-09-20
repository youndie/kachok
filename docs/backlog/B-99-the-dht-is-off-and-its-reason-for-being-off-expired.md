---
id: B-99
title: "The DHT is off by default, and the reason written beside the default has since come true"
status: done
priority: P2
size: S
stage: m9-swarm
epic: feature-download
blocked_by: []
---

# B-99 — The DHT is off by default, and the reason written beside the default has since come true

The DHT is off in all three places a default can live: `RuntimeOptions.dht = false` in the engine,
`--dht` opt-in in the CLI, and `Preferences.dht = false` in the window. The CLI's field carries the
reason, and the reason has an expiry date in it:

> Mainstream clients join the DHT by default and this one will too, once there is a torrent that
> needs it — a magnet link, which is [B-36](B-36-ut-metadata-and-magnets.md). Until then every
> torrent this client can open names a tracker, so joining would be contacting three public routers
> and announcing this machine's address to strangers for no gain.

[B-36](B-36-ut-metadata-and-magnets.md) is done and magnets have been downloadable since. The
condition the default was waiting on was met, the default was not revisited, and the sentence
explaining it now argues for the opposite of what it says. That is the whole of the defect: not
that off is wrong, but that nobody has decided it since it stopped being obviously right.

What is at stake in peers is not small. On a public torrent the DHT is an independent source of
addresses from the tracker, and it is the source that keeps working when a tracker is down or
rate-limits this client to fifty addresses ([B-97](B-97-the-announce-never-says-how-many-peers-it-wants.md)).
An owner comparing this client against one that joins by default is comparing two peer sources
against three.

Against that, the settings screen is explicit that this switch is not like the others —
*"a switch that announces this machine's address to strangers earns an explanation next to it, not
in a help page"* — and a default that silently starts contacting public routers on first run is a
different thing from a default pipeline depth. That is a product decision and not an engineering
one, which is why this is a question and not an open item.

- The decision to take, with the three answers it can have: **on by default**, with the existing
  paragraph shown where a first run will see it rather than only in settings; **off by default but
  offered**, so the first run asks once instead of leaving the switch to be discovered; or **off,
  deliberately**, in which case the CLI's comment is rewritten to give the reason that is true now
  and the window's status bar keeps saying *DHT off* as loudly as it does.
- Whatever is chosen, the test suite keeps it off. That half of the original reason has not
  expired: a suite that joins the DHT on every run is a suite that talks to strangers to test a
  parser.
- Not covered: the routing table's persistence across restarts, which is a separate cost of joining
  and its own item if joining becomes the default.
- Not covered: a private torrent, which never joins either way (BEP 27) and is already handled at
  the door in `Session.start`.

- AC: the owner decides, the decision is written into the research with its date, and all three
  defaults agree with it. If the answer is "off", the comment in `Arguments.kt` no longer names a
  condition that has already been met.
- Anchors: `cli/src/main/kotlin/io/github/youndie/kachok/cli/Arguments.kt`,
  `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/runtime/TorrentRuntime.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/session/SettingsFrom.kt`,
  `docs/research/research-architecture.md`.

**Answered by the owner 2026-09-17: on.** And by then it was not a close question — run 4 of
[B-98](B-98-how-many-peers-does-this-client-meet.md) is the default configuration against a swarm of
526, and it held **one peer**, because `torrent.ubuntu.com` hands out exactly one per announce at
`numwant` unset, 50 and 200 alike. A client without the DHT does not get a small share of a public
swarm; it gets one address.

**The answer is "on" for the products and still "off" for the engine, and that split is the whole
of the implementation.** `SetOptions.dht` — the only switch that actually opens a socket, as it
turned out — stays `false`: ten of this repository's own tests build a `TorrentSet` with its
defaults, and a library that contacts three public bootstrap routers because it was *constructed*
is a different thing from a client that joins because somebody installed it. The CLI and the window
answer the second question with `true`. That is also the half of the original reason that has not
expired, and it is now asserted rather than assumed — `theEngineItselfStillJoinsNothingUnlessTold`.

`--dht` is gone and `--no-dht` takes its place. Three tests asserted the old default and were right
to until it was measured; each now asserts the new one and says what moved it. The settings row
keeps its paragraph: a switch that announces this machine's address to strangers earns its
explanation whichever way it is set, which is the rule the screen was written to and is not what
changed here.

**A dead switch found on the way and removed.** `RuntimeOptions.dht` was read by nothing. The DHT
belongs to the set, not to a torrent, so the field had been a plausible-looking no-op since
[B-54](B-54-many-torrents.md) split them — a caller could set it, see no error, and get no DHT.
