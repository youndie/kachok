---
id: B-37
title: "v2 and hybrid torrents (BEP 52): SHA-256 piece layers"
status: question
priority: P3
size: L
stage: m8-extensions
epic: feature-metainfo
blocked_by: [B-04]
---

# B-37 — v2 and hybrid torrents (BEP 52): SHA-256 piece layers

Research Open question 3. v2 changes the hash function, the piece verification (a Merkle layer
per file) and the info hash (SHA-256, truncated on the wire), and keeps the 16 KiB block and the
wire messages.

- **The question.** Whether phase 1 needs more than "load a hybrid torrent through its v1
  dictionary". The hypothesis in the research is no; the first real hybrid torrent that fails to
  load is the data point that settles it.
- The shape if the answer is yes: a second `Hasher` (SHA-256 is intrinsified too, research §1.1),
  a per-file Merkle verifier behind the same `Storage`, and a dual handshake for hybrid swarms.
- Not covered until then: everything above.

- AC (when the question is answered): recorded in the research; this item becomes `open` with a
  size, or `dropped` with the reason.
- Anchors: `engine/src/commonMain/kotlin/io/github/youndie/kachok/engine/metainfo/`, `engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/hash/`.
