---
id: B-116
title: "A `.torrent` whose name is not ASCII cannot be opened on Windows: the launcher hands the path over as question marks"
status: open
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
---

# B-116 — A `.torrent` whose name is not ASCII cannot be opened on Windows: the launcher hands the path over as question marks

Found in the owner's `%APPDATA%\kachok\startup-error.txt`, which `main` writes for exactly this
case — a failure before the window, which a `jpackage` launcher otherwise answers with a message
box that names nothing:

```
kachok could not start
os Windows 11 10.0
java 25.0.4.1
launcher C:\Users\youndie\AppData\Local\kachok\kachok.exe

java.nio.file.InvalidPathException: Illegal char <?> at index 27:
C:\Users\youndie\Downloads\???????????? ??????! … [rutracker-2532777].torrent
	at java.base/sun.nio.fs.WindowsPathParser.normalize(…)
	at io.github.youndie.kachok.ui.AppKt.run(App.kt:190)
```

Every non-ASCII character of the file's name is a `?` by the time `Path.of` sees it, so the path
is illegal and the application does not start at all — double-clicking a torrent with a Russian
name is a window that never opens and an error file somebody has to go and find. The name in the
report is a torrent from a tracker whose titles are Russian, which is most of them for this owner.

**What is known.** The characters are lost before this client's code runs: `?` is what a
`WideCharToMultiByte` conversion writes for characters the active code page has no room for, which
is what happens when a wide command line is handed to a byte `main`. The `jpackage` launcher gives
the JVM its arguments; the JVM decodes them with `sun.jnu.encoding`, which on this machine is a
Windows code page and not UTF-8. Nothing in `kachok.cfg` sets either property.

**What is not known, and has to be measured before anything is changed.** Whether the characters
survive as far as the JVM at all. If the launcher already flattened them, no JVM property can
bring them back and the answer is a different door — reading the real command line through
`GetCommandLineW`, or taking the path through the single-instance channel the way macOS takes an
Apple Event ([B-84](B-84-torrent-files-open-with-the-client.md)). If they survive and only the
decoding is wrong, `-Dsun.jnu.encoding=UTF-8` in the packaged options is the whole fix.

- **First, the measurement.** A packaged build, a file whose name is Cyrillic, opened by
  double-click, with the arguments logged as code points before anything parses them; and the same
  through `kachok.exe <path>` from a console. Two readings, because the console and the shell
  hand a launcher its arguments by different routes.
- Rejected in advance: catching `InvalidPathException` and carrying on without the torrent. It
  would turn "the window does not open" into "the window opens and ignores what you asked for",
  which is worse for the same defect.
- Not covered: torrent *contents* whose file names are not ASCII, which go through the metainfo
  parser and not through `argv`.

- AC: on Windows, a `.torrent` whose name is Cyrillic opens the client and its add dialog, by
  double-click and from a console; the same file on macOS and Linux still does. Whatever the
  measurement finds is written into the item before the fix is chosen.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`,
  `ui/build.gradle.kts`.
