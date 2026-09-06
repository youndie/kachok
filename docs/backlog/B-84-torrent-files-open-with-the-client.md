---
id: B-84
title: "A .torrent opens with the client, on all three platforms"
status: open
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-82]
---

# B-84 — A `.torrent` opens with the client, on all three platforms

Double-clicking a `.torrent` does nothing today. The window takes a path in `argv[0]` and opens it —
that half exists — but nothing tells the operating system that this application is what a `.torrent`
is for.

## What each platform needs, and what was verified

Compose's `nativeDistributions { fileAssociation(mimeType, extension, description) }` exists in the
plugin this project uses (1.12.0). Declaring it and building an app image on macOS on 2026-09-06
put this in `kachok.app/Contents/Info.plist`:

```
CFBundleDocumentTypes → CFBundleTypeExtensions: ["torrent"]
                        CFBundleTypeMIMETypes:  application/x-bittorrent
                        CFBundleTypeRole:       Editor
```

So on **macOS the association travels in the bundle** and LaunchServices takes it from there. On
**Windows** it is the `.msi` that writes the registry keys and an app image registers nothing; on
**Linux** it is the `.deb`/`.rpm` that installs the `.desktop` file and the mime XML. Two of the
three therefore wait on [B-82](B-82-an-installer-per-platform.md).

## The half that is not packaging

**macOS does not pass a double-clicked document in `argv`.** It sends an Apple Event, which reaches
Java as `Desktop.setOpenFileHandler` — so the code that works on Windows and Linux receives nothing
on macOS, and a client that only reads `args.firstOrNull()` will look broken on exactly the platform
whose association was easiest to get right.

- **The decision this needs.** What opening a second one does while the first is running. Every
  platform starts a *new process* for the second document unless the application says otherwise, and
  two processes on one listening port and one download directory is [B-60](B-60-two-torrents-one-path.md)
  again, between processes this time, where no `TorrentSet` can see it.
- Rejected in advance: registering `magnet:` in the same item. It is a URL scheme rather than a
  file type, a different registration on all three platforms, and it deserves its own.
- Not covered: being the *default* handler rather than one of the offered ones. That is the user's
  choice to make and no installer should take it.

- AC: a `.torrent` double-clicked in a file manager opens this client's add dialog on Windows, macOS
  and Linux; doing it twice does not produce two clients fighting over one port.
- Anchors: `ui/build.gradle.kts`,
  `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/App.kt`.
