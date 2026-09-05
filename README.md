# kachok

A BitTorrent client in Kotlin. The engine — bencode, the peer wire protocol, piece selection,
choking, storage — is common Kotlin from the first commit; the I/O behind it is whatever the
platform does best. On the desktop that platform is JDK 25: one virtual thread per peer on a
blocking socket, a pool of 16 KiB direct buffers that travel from the socket to the disk without a
copy, one writer doing gathering writes, compact object headers, an ahead-of-time cache.

**Status: phase 1, milestone 0.** The build compiles, lints and tests on JDK 25; the engine has
three value classes; the CLI prints that it has no commands yet. What comes next, in order, is in
[backlog.md](backlog.md). Why it is built this way — and where the research changed the original
brief — is in [docs/research/research-architecture.md](docs/research/research-architecture.md).

## Phases

1. **Headless JVM client** (this phase) — download, seed, resume, from the command line.
2. **Compose Multiplatform UI** — desktop, and the browser question the research already raises.
3. **Android and iOS** — the same engine, four platform implementations each.

## Build

```bash
./gradlew build                     # compile, ktlint, tests; JDK 25 is resolved by the toolchain
./gradlew build -Pkachok.release    # the same with the release compiler flags
./gradlew :cli:run --args="download example.torrent"
```

## Documentation

[docs/README.md](docs/README.md) is the entry point: research, one document per module, the
backlog, and the checks that keep them honest (`make check`).

## Licence

MIT.
