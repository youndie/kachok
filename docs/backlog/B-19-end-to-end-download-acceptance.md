---
id: B-19
title: "Download a real public torrent end to end, and record the numbers"
status: open
priority: P0
size: S
stage: m4-download
epic: feature-download
blocked_by: [B-18]
---

# B-19 — Download a real public torrent end to end, and record the numbers

Everything before this item is tested against fakes and local peers. A real swarm has clients that
send `bitfield` late, `have` before the handshake finishes, keep-alives on odd schedules, and
requests larger than 16 KiB; this item is where the engine meets them.

- **The decision and its reason.** One publicly distributed, freely licensed torrent (a Linux
  distribution image is the usual choice), downloaded from a clean directory with `-Xlog:gc` and a
  JFR recording on; the resulting throughput, peak heap, carrier count and pool occupancy are
  written into the research as the first measured numbers, replacing the brief's estimates.
- Rejected: declaring M4 done on the fake-peer tests. A protocol implementation that has only met
  its own fakes has met nothing.
- Not covered: seeding back — the client announces `stopped` at the end of this test.

- AC: the download completes with a matching hash; the numbers are in the research with the
  torrent named, the machine described, and the date; anything that misbehaved is a quirk in the
  feature document or a new backlog item.
- Anchors: `cli/src/main/kotlin/ru/workinprogress/kachok/cli/`, `docs/research/research-architecture.md`.
