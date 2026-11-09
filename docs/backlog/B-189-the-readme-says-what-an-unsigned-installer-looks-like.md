---
id: B-189
title: "The README says what an unsigned installer looks like on first start, platform by platform"
status: open
priority: P2
size: S
stage: phase-2-ui
blocked_by: [B-187]
---

# B-189 — The README says what an unsigned installer looks like

None of the three installers is signed by anybody a downloading machine trusts.
[B-82](B-82-an-installer-per-platform.md) set signing aside on purpose — "unsigned is fine for a
client somebody built themselves and is not fine for one that is downloaded" — and nothing has
signed them since; the `.dmg`'s bundle carries the ad hoc signature jpackage gives it
([B-171](B-171-the-dmg-carries-an-aot-cache-trained-on-its-own-files.md):23). Nobody here has seen
what a stranger sees, because a package built on the machine that opens it carries no
mark-of-the-web or quarantine flag. Once [B-187](B-187-a-tag-publishes-the-installers-as-a-github-release.md)
puts the files on a release page, every download does.

- **What is expected — a hypothesis for each, to be replaced by what is observed:**
  * Windows 11, `.msi`: SmartScreen's *Windows protected your PC*, with *Run anyway* only behind
    *More info*, and *Unknown publisher*. No UAC prompt, since the install is per-user
    (`ui/build.gradle.kts:253`).
  * macOS, `.dmg` (arm64): Gatekeeper refuses to open the app on first start, and on recent macOS
    the way through is *System Settings → Privacy & Security → Open Anyway* rather than the old
    Control-click.
  * Ubuntu, `.deb`: no warning; `sudo apt install ./kachok_<version>_amd64.deb`.
- **The decision and its reason.** A short *First start* paragraph in the README's *Install*
  section, one line per platform, with the exact words the dialog shows and the clicks through it.
  A person who meets a red warning with no explanation next to the download link closes it, and
  #27's user is exactly the person who would.
- Rejected: telling people to strip the quarantine flag with `xattr` or to turn SmartScreen off.
  Both work, and both teach a reader to switch off a protection for every other download.
- Not covered: whether to sign at all — that is the owner's question,
  [B-190](B-190-sign-the-installers-or-ship-them-unsigned.md); this paragraph is what stays true
  until it is answered, and what changes when it is.

- AC (**by hand**): each of the three files, downloaded from a release page through a browser — not
  copied from the build — opened on Windows 11, on a current macOS and on Ubuntu; the README says
  what each showed and how to go on, and the date and the version it was observed on.
- Anchors: `README.md`.
