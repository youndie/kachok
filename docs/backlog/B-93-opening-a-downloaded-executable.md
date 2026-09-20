---
id: B-93
title: "Double-clicking a downloaded executable, and the warning Windows never gets to show"
status: done
priority: P2
size: M
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-93 — Double-clicking a downloaded executable, and the warning Windows never gets to show

Reported on 2026-09-07: double-clicking an `.exe` in the *Files* tab on Windows does not run it, and
says something about not finding the program.

**Not reproduced here, and the reason is worth writing down rather than working around.**
`java.awt.Desktop` needs a desktop session; an ssh session on the Windows box is headless and
reports `desktopSupported=false`, so the one call this is about cannot be made from where this
project can reach. What was done instead was to stop throwing away the evidence: the refusal now
carries the system's own message and the folder the file is in, so the next report says which of the
six branches of `openFile` produced it. That is a change to the diagnosis, not to the behaviour.

## The decision underneath it, which is not "make it work"

**A file this client wrote carries no Mark-of-the-Web.** Every browser writes a `Zone.Identifier`
alternate data stream onto a download, and that is what makes Windows put its "unknown publisher"
question in front of somebody who runs it. A torrent client that writes files without it and then
runs them on a double-click has removed a warning the operating system would otherwise have given —
about a binary that came from strangers, which is what a swarm is.

So the question is not whether `Desktop.open` can be made to launch an `.exe`. It is:

- **The decision this needs.** Whether this client runs executables at all, and on what terms. Three
  answers: refuse them with a reason and offer the folder instead; run them exactly as now and
  accept that Windows will not ask; or mark every downloaded file the way a browser does, and then
  run it — at which point Windows asks, and the person answers.
- **And whether the mark is for executables or for everything.** Browsers mark everything they
  download; it is one alternate data stream per file and it is what makes the rest of the system
  treat the file as what it is. Doing it only for `.exe` is a list of extensions that will be wrong.
- Rejected in advance: reaching around `Desktop.open` with `explorer.exe <path>` to make executables
  launch. It would work, and it would make this client the one place on the machine where a
  downloaded binary runs with no question asked.
- Not covered: macOS quarantine (`com.apple.quarantine`), which is the same idea and a different
  attribute, and Linux, which has no equivalent.

**This is not a hypothetical.** The *Files* tab already refuses to open a file that is not finished,
on the grounds that handing a player a truncated file is worse than saying no
([B-85](B-85-open-a-file-from-the-files-tab.md)). The same tab handing an operating system an
unmarked executable is the larger version of the same question.

## The decision, taken by the owner on 2026-09-20

**Mark everything this client downloads, the way a browser does, and then open it.** Not a list of
extensions, which the item already said would be wrong; not a refusal, which takes away something a
person asked for; and not the status quo, which would have left this client as the one place on the
machine where a binary from strangers runs with nothing asked.

Two facts were established before the question was put, so that the three options could be compared
rather than argued:

* The mark can be written on that machine **without a desktop session** — an NTFS alternate data
  stream is an ordinary write, which is what made this answerable at all when the reported symptom
  is not.
* `UserDefinedFileAttributeView`, which is the JDK's portable name for exactly that stream, writes
  and reads it on the **JDK this client ships** (Temurin 25.0.1 on the owner's box).

## Driven on Windows, with this build

The engine jar from this branch, a two-file torrent, `FileSet.open` into an empty directory, on the
Windows machine:

```
one.bin: marked=true [ZoneTransfer] | ZoneId=3
two.bin: marked=true [ZoneTransfer] | ZoneId=3
(first file deleted, reopening)
one.bin: marked=true
two.bin: marked=true
```

Every file this client creates carries the internet zone; reopening the set — which is what resuming
is — marks the one it had to create again and leaves the other alone.

**What this does not do, and the report that opened the item is still open on it.** The mark makes
Windows *ask* when an executable runs. Whether `Desktop.open` launches an `.exe` on that machine at
all was not reproduced here and still cannot be: `java.awt.Desktop` needs a desktop session and the
only way this project can reach that box is an ssh session, which is headless. If it turns out it
never launches, the answer above is still the right one — it is about what a file carries, not about
what opens it.

- AC: what a double-click on an executable does is one written-down decision rather than whatever
  `Desktop.open` happens to do; if it runs, the file carries the mark that makes Windows ask first.
  **Both met.**
  **Automated:** `engine/src/jvmTest/.../storage/MarkOfTheWebTest.kt` — `theMarkIsTheOneABrowserWrites`,
  `nothingIsMarkedWhereTheMarkMeansNothing`, `everyFileIsMarkedWhenItIsCreatedAndNotWhenItIsResumed`.
  The stream itself is an NTFS one and this suite runs on Linux, so what the tests hold is *which*
  files are marked and with what; that the write lands is the Windows run above.
- `OpenFile.kt` is unchanged, and that is the decision too: the tab goes on handing the file to the
  system, and the system now has what it needs to ask.
- Anchors: [`ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/OpenFile.kt`](../../ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/OpenFile.kt),
  [`engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/storage/FileSet.kt`](../../engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/storage/FileSet.kt).
