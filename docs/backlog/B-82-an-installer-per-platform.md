---
id: B-82
title: "An installer per platform, and the version that stops one"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-82 — An installer per platform, and the version that stops one

The build produces an **app image** — `createDistributable` — and that is all anybody has ever run:
a directory that is zipped and copied. It registers nothing with the operating system, which is why
[B-84](B-84-torrent-files-open-with-the-client.md) and
[B-83](B-83-autostart-and-its-setting.md) both come back to this item.

- **The decision this needs.** Which formats. `packageDistributionForCurrentOS` gives `.dmg` and
  `.pkg` on macOS, `.msi` and `.exe` on Windows, `.deb` and `.rpm` on Linux, and each has to be
  produced *on* its platform — jpackage cannot cross-compile, which is already why the Windows
  build happens over ssh.
- Rejected in advance: shipping only the app image and telling people to unzip it. It works, it is
  what happens today, and it is also why a `.torrent` cannot be double-clicked on Windows and why
  there is nowhere for an autostart entry to point that survives the folder being moved.
- Not covered: signing and notarisation. Unsigned is fine for a client somebody built themselves
  and is not fine for one that is downloaded; that is a separate question with a certificate
  attached.

## What is broken right now, measured

`packageVersion = "0.1.0"` **cannot be built on macOS at all**:

```
Bundler Mac Application Image skipped because of a configuration problem:
The first number in an app-version cannot be zero or negative.
```

Verified on 2026-09-06 by running `:ui:createDistributable` on macOS 27.0; the same string is
accepted by the Windows bundler, which is why every build so far has been a Windows one and nobody
noticed. `macOS { packageVersion = … }` overrides it per platform, and a version the *project* is
not at is its own small lie — so the decision is between an override and moving the project to
`1.0.0`.

- AC: each platform's installer is produced by one documented command on that platform; a macOS
  build is possible at all; the version a package reports is the version the project is at, or the
  difference is written down where somebody reading the build file will see it.
- Anchors: `ui/build.gradle.kts`, `docs/services/` (whichever document describes shipping).
