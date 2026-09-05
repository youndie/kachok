# docs — kachok

A BitTorrent client in Kotlin: a multiplatform engine whose hot path is socket → direct buffer →
disk, and a headless JDK 25 client on top of it (phase 1). The documentation is layered; links run
top to bottom.

```
[ Research (why the architecture is what it is) ]
                     │
[ Feature (business + BDD) ]
                     │
                     ▼
           [ Service / module (ownership, how it is built, quirks) ]
```

| Layer | Directory | Answers | Source of truth |
|---|---|---|---|
| Research | `research/` | *why* it is built this way; what is verified, what is a hypothesis | the JDK, the artefacts and the specifications each fact names |
| Feature | `features/` | *what* the client does; BDD scenarios as acceptance criteria | this repository |
| Service | `services/` | what each Gradle module owns, how it is built, its quirks | this repository |

There is no `screens/` layer in phase 1 — the client is a command line, and its surface is
described in the `cli` service document and the CLI feature — and no `api/` layer, because the
client exposes no HTTP API. Phase 2 adds `screens/` with the Compose UI. A missing directory is a
valid answer; a renamed one is not — the checkers look for these names.

**Backlog** — [backlog.md](../backlog.md): the index and the decisions; the items themselves are
one file each in [`backlog/`](backlog/), cited as
[B-06](backlog/B-06-peer-wire-codec.md).

## The two rules

> **`main` describes what exists. An open pull request describes what will be.**

A feature document for behaviour that is not built is `status: draft` and lives in an open branch.
On `main`, the research says why the engine is designed as it is, the service documents describe
the modules that exist, and the backlog says what comes next. Nothing on `main` claims the client
downloads anything until it does.

> **What was verified is separated from what was assumed, explicitly.**

Every fact in the research names where it was read — a JDK class file, a JEP, a Maven metadata
listing, a BEP section, a build log. Anything else is labelled a hypothesis with the backlog item
that settles it.

## Conventions

- **`id`** in the frontmatter is unique and equals the filename.
- Cross-layer links are ids in the frontmatter and ordinary markdown links in the body.
- One document, one entity. A feature touching both modules is **one** file with two entries in
  `involved_services`.
- BDD scenarios quote the protocol: message ids, byte layouts and limits come from the BEP texts
  cited in the research, and once code exists, from the code.
- **The primary consumer is a coding agent.** Every document carries code anchors — paths into
  the module, the key file, the target directory — so that a reader reaches the code in one hop.
  Do not duplicate what lives in code (flag lists, config fields); give the path.
- Language: English, in documents and in code. Identifiers, flags and BEP terms verbatim.

## Templates

`templates/` holds a copy of the document templates, so the format travels with the repository.
Sections marked `<!-- optional -->` can be deleted.

## Checks

```bash
pip install pyyaml
make check                                   # the gate: exactly what CI runs
make report                                  # BDD coverage and code anchors, read by a person
make fix                                     # regenerate the backlog index, append missing map lines
```

`code_anchors.py` will report the target directories named in the service documents as missing
until their backlog items land; that is the report doing its job, and it is not a gate.

## Coverage map

The list below is **checked** against the files on disk: a document missing here, or an entry with
no file behind it, fails `coverage_map.py`. The grouping and the descriptions are written by a
person — the machine only guards the membership.

### Research (1)

- [x] [research-architecture](research/research-architecture.md) — what JDK 25 actually ships, the AOT cache measured, the protocol from the BEPs, twelve decisions and where they depart from the brief

### Services (2/2)

- [x] [engine](services/engine.md) — the multiplatform engine: one dispatcher on virtual threads, a pool of 16 KiB direct buffers, one writer; today three value classes and the layout the backlog builds
- [x] [cli](services/cli.md) — the headless client: argument parsing, the JVM flags, the phase-1 distribution; today a skeleton that exits with code 2
