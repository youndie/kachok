---
id: B-82
title: "An installer per platform: macOS and Linux ship, Windows needs a WiX decision"
status: question
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
- Anchors: [`ui/build.gradle.kts`](../../ui/build.gradle.kts),
  [`docs/services/ui.md`](../services/ui.md) §5.

## Done — two platforms of three, and the third is the owner's decision

**No format was declared at all.** `targetFormats` was never called, so
`packageDistributionForCurrentOS` ran, reported `BUILD SUCCESSFUL` in under a second and wrote
nothing. That is worse than the app-image-only shipping this item was filed about: a task that
succeeds and produces no file is one nobody thinks to check. `TargetFormat.Dmg, Msi, Deb` now, one
per platform.

**The version had two homes and now has one.** `packageVersion` was the literal `"0.1.0"` beside
`version=0.1.0-SNAPSHOT` in `gradle.properties`; it is now derived from the project's, minus the
qualifier `jpackage` will not take.

| Platform | Command | Produced | Verified |
|---|---|---|---|
| macOS 27.0 | `LOCAL=1 ./gradlew :ui:packageDistributionForCurrentOS` | `kachok-1.0.0.dmg`, 71 MB | 2026-09-06 |
| Ubuntu 24.04 | `~/.claude/bin/wsl-run './gradlew :ui:packageDeb'` | `kachok_0.1.0_amd64.deb`, 57 MB | 2026-09-06 |
| Windows 11 | `gradlew.bat :ui:packageMsi` | — | **blocked, see below** |

The `.deb` carries the icon and a `kachok-kachok.desktop`, checked with `dpkg-deb -c`; the `.app`
carries `kachok.icns` and reports `CFBundleShortVersionString 1.0.0`. `fakeroot` had to be installed
on the Linux machine — `jpackage` skips the DEB bundler without it, with a message that names the
missing program, which is the good kind of failure.

**macOS says 1.0.0 while the project says 0.1.0, and that is on purpose.** Apple's
`CFBundleShortVersionString` must start at 1 or higher, so `0.1.0` cannot be packaged there at all —
the failure this item was filed for. `macOS { packageVersion = "1.0.0" }` overrides it for that
platform only; Linux and Windows carry the project's own number. The gap is written into
`ui/build.gradle.kts` beside the line that causes it.

### The Windows installer needs a decision nobody but the owner can take

`jpackage` builds an MSI through WiX, and there is no longer a version of WiX that is both free of
an agreement and installable without administrator rights. Measured on the build machine on
2026-09-06:

| Route | What it costs |
|---|---|
| WiX v7 (`winget install WiXToolset.WiXCLI`) | installs cleanly, then refuses every command: `WIX7015: You must accept the Open Source Maintenance Fee (OSMF) EULA`. Accepting a licence is the owner's signature, not a build step. |
| WiX v3.14 (`winget install WiXToolset.WiXToolset`) | needs the `NetFx3` Windows feature, which the installer could not enable: `Failed to enable [NetFx3] feature: 5` — elevation. |
| WiX v5.0.2 | **nothing.** Corrected below — this line first said it needed a .NET SDK, which was wrong. |
| Ship the app image | what happens today. It is also why `.torrent` cannot be double-clicked ([B-84](B-84-torrent-files-open-with-the-client.md)). |

**The route that costs nothing was missed because `winget` does not list it.** `winget search wix`
offers 3.14 and 7.0 and nothing between, and from that it looked as though v4 and v5 were the
`dotnet tool` line — which would need a .NET SDK the machine does not have. They are not, from
v5.0.1 onward: every release since carries a plain `wix-cli-x64.msi`, the same standalone installer
v7 uses. Read off the project's own releases on 2026-09-06:

| Version | Released | Standalone `wix-cli-x64.msi` | Maintenance fee |
|---|---|---|---|
| 3.14.1 | — | no, its own installer | none, and needs `NetFx3` |
| 4.0.6 | 2024-10-05 | no | none |
| **5.0.2** | **2024-10-05** | **yes** | **none** |
| 6.0.0 | 2025-04-08 | yes | first release carrying the OSMF notice |
| 7.0.0 | 2026-04-06 | yes | OSMF, and `wix.exe` refuses every command until it is accepted |

`jpackage` on JDK 25 speaks both dialects, which is the other half of why this is a free choice
rather than a forced one: `jdk.jpackage.internal.WixToolset$WixToolsetType` has `Wix3` and `Wix4`,
it looks for `candle.exe`/`light.exe` and for `wix.exe`, it searches `C:\Program Files\WiX Toolset
v*\bin` along with `%USERPROFILE%\.dotnet\tools`, and it ships `wix3-to-wix4-conv.xsl` to convert
its own sources between them. Read out of the module image on the build machine rather than
remembered.

**So the decision is smaller than this item first said**: v5.0.2's MSI needs neither elevation nor a
licence, and `wix extension add -g WixToolset.Util.wixext WixToolset.UI.wixext` is the only step
after it. It is a version behind the maintained line, and that is the trade — a newer WiX means the
fee, an older one means WiX 3 and `NetFx3`. **Waiting on the owner: which WiX goes on that
machine.**

The machine was left as it was found: WiX v7 was installed, proved unusable without the EULA, and
uninstalled. **Automated:** none — a packaging run is minutes long and produces a 57–71 MB file, so
it is a documented command rather than a gate; what `./gradlew build` does check is that the build
script configures without error.
