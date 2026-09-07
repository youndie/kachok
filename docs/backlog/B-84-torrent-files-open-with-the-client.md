---
id: B-84
title: "A .torrent opens with the client, on all three platforms"
status: done
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

**Correction to this item as filed: `fileAssociation` is not on `nativeDistributions`.** It is on
`AbstractPlatformSettings`, which `macOS`, `windows` and `linux` extend and `JvmApplicationDistributions`
does not — so there is no place to declare it once. Declaring it in one block associates the
extension on one operating system and, silently, on none of the others.

Compose's `fileAssociation(mimeType, extension, description)` exists in the plugin this project uses
(1.12.0). Declaring it and building an app image on macOS on 2026-09-06 put this in
`kachok.app/Contents/Info.plist`:

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
- Anchors: [`ui/build.gradle.kts`](../../ui/build.gradle.kts),
  [`ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/SingleInstance.kt`](../../ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/SingleInstance.kt),
  [`ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`](../../ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt).

## Done

### The registration, measured on each platform rather than assumed

| Platform | What carries it | Read on 2026-09-06 |
|---|---|---|
| macOS | the app bundle | `CFBundleDocumentTypes` → extensions `["torrent"]`, MIME `application/x-bittorrent`, role `Editor` |
| Windows | the `.msi`'s `Registry` table | `.torrent` → a ProgId whose `shell\open\command` is `"<launcher>" "%1" %*` — queried out of the MSI database read-only, without installing it |
| Linux | the `.deb` | a `.desktop` with `MimeType=application/x-bittorrent`, a mime XML, and `xdg-mime install` in `postinst` |

### `jpackage` writes a `.desktop` that cannot open a file

Its own `template.desktop` — read out of `jdk.jpackage`'s resources in the JDK on the build machine
— is `Exec=APPLICATION_LAUNCHER`, with **no field code**, while substituting `DESKTOP_MIMES` on the
line below. By the freedesktop specification an `Exec` without `%f`, `%F`, `%u` or `%U` is never
given the file. The association would have been there, the icon would have been right, and
double-clicking a `.torrent` would have opened an empty client — the exact failure this item was
filed about, arriving through the fix for it.

Compose passes `--resource-dir` at a directory it clears inside its own task action, so a
replacement template has nowhere to go. `:ui:patchDesktopEntry` unpacks the `.deb`, adds the `%f`
and rebuilds it, and then **checks that the line changed** — a `sed` that matches nothing exits
successfully, which is the whole failure mode. Verified by pointing that `sed` at a pattern nothing
matches: the task fails and prints the entry.

### One client, however many double-clicks

Every platform starts a new process for the second document. `SingleInstance` makes the *socket* the
lock rather than a file: a lock file cannot tell a running process from a crashed one, and every way
of noticing that — a pid, a heartbeat — is a new way to be wrong, while a bound port disappears with
the process that held it. The port is written into `<config>/instance` with 128 random bits beside
it, and a caller that does not send them back is hung up on; without that, any local process could
hand this client a path to open.

The second launch's paths reach the window through the same door a dropped file uses, one at a time,
waiting for the previous dialog to be answered.

**macOS still needed code.** A document opened there arrives as an Apple Event and never appears in
`argv`, so `Desktop.setOpenFileHandler` feeds the same channel — otherwise the platform whose
registration was easiest to get right would have been the one that looked broken.

Not covered, as filed: `magnet:` as a URL scheme, and being the *default* handler rather than an
offered one. Also not covered: the registry rows are read out of the MSI, not out of a machine that
installed it — installing on the build host is a change to somebody's computer, not a test.

**Automated:** `ui/src/desktopTest/.../session/SingleInstanceTest.kt` — the second launch hands over
and does not become a client, every path arrives, a lock left by a dead client is taken over, a
stranger on the port is not mistaken for the client, and a caller with the wrong secret is refused ·
`:ui:patchDesktopEntry`, which fails the build if the `.desktop` it produced still cannot open a
file.
