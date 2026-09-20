---
id: B-116
title: "A `.torrent` whose name is not ASCII cannot be opened on Windows: the launcher hands the path over as question marks"
status: wip
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

## The measurement, taken 2026-09-20 — and it refutes the fix this item assumed

A file whose name is nine Cyrillic characters, built **on the Windows machine from code points** so
that nothing between here and there could flatten it on the way in, and a probe that prints its
arguments as code points before anything parses them. Through a `jpackage` launcher built for the
purpose, and through a plain `java` in the same console:

```
sun.jnu.encoding=Cp1252   file.encoding=UTF-8   native.encoding=Cp1252
raw: C:\Users\youndie\probe\????????? [test].torrent
Path.of threw: Illegal char <?> at index 23
```

Three findings, and two of them were not in the item:

1. **`-Dsun.jnu.encoding=UTF-8` does nothing.** The property still reads `Cp1252` in the same
   process that was given it: it is read before the command line is, and cannot be set from it. The
   item's *"if they survive and only the decoding is wrong, that is the whole fix"* is **wrong** —
   there is no property that brings the characters back.
2. **The loss is inside the JVM's own launcher, not `jpackage`'s.** A plain `java` flattens them
   exactly as the packaged launcher does, so this was never about how the application is packaged.
3. **Everything except `argv` is intact.** `Files.list` on that directory returns the real code
   points, `Files.exists` is true, the size is right. The file API speaks UTF-16 to Windows; only
   the one string handed to `main` went through a code page that has no room for Cyrillic.

## What was chosen, and what it was measured against

`GetCommandLineW` plus `CommandLineToArgvW`, read through the foreign-function API — no library of
this project's own, and nothing to package. On the same machine, through a `jpackage` launcher:

```
argv[0] as the JVM decoded it: C:\Users\youndie\probe\????????? [test].torrent
wide argc: 2
  [1] code points: … 041A 0438 0440 0438 043B 043B 0438 0446 0430 …
  Path.of: ok=true
```

The application's arguments are taken as the **tail** of that list rather than by working out where
the JVM's own end: a packaged `kachok.exe` passes only the application's, a development
`java -cp … MainKt` passes several of its own first, and under both the ones the JVM handed over are
the last *n*. A wide line shorter than what the JVM reported is left alone — that is not the same
launch, and guessing would be inventing a mapping.

## What is left, and it needs a person

The syscall and the tail rule are proved on Windows, through a real `jpackage` launcher, with the
readings above. **What has not been run there is this branch's own Kotlin**: building a packaged
kachok needs a Windows build host, and double-clicking it needs somebody at the screen. The rule
around the call is held by `WideArgumentsTest` wherever the suite runs; the call itself is a
transcription of the probe that was run.

- AC: on Windows, a `.torrent` whose name is Cyrillic opens the client and its add dialog, by
  double-click and from a console; the same file on macOS and Linux still does. Whatever the
  measurement finds is written into the item before the fix is chosen. **The measurement is written
  up above and it changed the fix. The double-click is the one step left** — an `.msi` from the
  `distributions` workflow on this commit, installed, and a Russian torrent double-clicked.
  **Automated:** `ui/src/desktopTest/.../session/WideArgumentsTest.kt` —
  `theApplicationsArgumentsAreTheTailOfTheRealCommandLine`,
  `aPackagedLauncherPassesOnlyWhatTheApplicationWasGiven`,
  `nothingIsReadWhereTheArgumentsAreAlreadyRight`, `aLineThatCannotBeTheSameLaunchIsLeftAlone`,
  `aFailedReadIsNotAFailedStart`.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`,
  `ui/build.gradle.kts`.
