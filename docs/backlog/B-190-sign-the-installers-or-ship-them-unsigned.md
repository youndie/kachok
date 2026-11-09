---
id: B-190
title: "Decide whether the installers are signed, or ship unsigned with the README's explanation"
status: dropped
priority: P3
size: M
stage: phase-2-ui
---

# B-190 — Sign the installers, or ship them unsigned

The installers are unsigned ([B-82](B-82-an-installer-per-platform.md) left signing out on
purpose), and [B-189](B-189-the-readme-says-what-an-unsigned-installer-looks-like.md) writes down
what that costs a stranger on first start. Whether to pay to remove the warning is not a code
decision: each way of signing comes with an account, a yearly cost or a third party's conditions,
and a certificate whose secret has to live in this repository's Actions secrets.

What is already read: research §1.3j's row on unsigned installers
(`docs/research/research-architecture.md:897`) describes the route Conveyor takes — self-signed,
with a script the user runs — and [B-158](B-158-conveyor-as-the-packager.md) was deferred, so that
route is not on the table.

## Question — for the owner

1. **Unsigned, explained.** B-189's paragraph is the answer; this item is dropped with that reason.
   Nothing is paid and no secret is added.
2. **Windows only.** The `.msi` is what the outside user installs (#27). A code-signing certificate
   or a signing service, `signtool` in the `windows-latest` job of `distributions.yml` before the
   upload. Hypothesis, from Microsoft's documentation and not observed here: a new certificate still
   meets SmartScreen until it has built reputation, so the first releases may look the same.
   Free signing programmes for open-source projects exist and want an OSI licence first
   ([B-185](B-185-the-readme-says-mit-and-the-repository-has-no-licence.md)).
3. **Windows and macOS.** Option 2, plus an Apple Developer ID, `codesign` with the hardened runtime
   and `notarytool` on `macos-latest`, in place of the ad hoc signature jpackage gives the bundle
   today (B-171).

- AC (when answered): the answer recorded here; for 2 or 3 this item becomes `open` with a size and
  the account it needs, or `dropped` with the reason.
- Anchors: `.github/workflows/distributions.yml`, `ui/build.gradle.kts`.

## Answer — 2026-11-09: unsigned, explained

The owner chose **1**. Nothing is paid and no signing secret is added; the installers ship as
jpackage makes them, unsigned on Windows and with the ad hoc signature on macOS (B-171), and
[B-189](B-189-the-readme-says-what-an-unsigned-installer-looks-like.md)'s README paragraph — what
SmartScreen and Gatekeeper show on first start, and how to get past each — is the answer a stranger
gets. Dropped with that reason.

- Look again if the warning turns out to stop people installing — an issue saying so is the
  evidence — or if a free signing programme for open-source projects becomes worth its conditions:
  the OSI licence those programmes ask for is there since
  [B-185](B-185-the-readme-says-mit-and-the-repository-has-no-licence.md) (MIT).
