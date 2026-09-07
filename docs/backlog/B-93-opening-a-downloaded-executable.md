---
id: B-93
title: "Double-clicking a downloaded executable, and the warning Windows never gets to show"
status: open
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

- AC: what a double-click on an executable does is one written-down decision rather than whatever
  `Desktop.open` happens to do; if it runs, the file carries the mark that makes Windows ask first.
- Anchors: [`ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/OpenFile.kt`](../../ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/session/OpenFile.kt),
  [`engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/storage/FileSet.kt`](../../engine/src/jvmMain/kotlin/io/github/youndie/kachok/engine/storage/FileSet.kt).
