---
id: B-90
title: "An .msi did not upgrade the installed client"
status: done
priority: P1
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: []
---

# B-90 — An `.msi` did not upgrade the installed client

Installing a newer build over an installed one left the old one running. Reported from a real
Windows machine on 2026-09-07, and the cause is in the package rather than in the installer.

## Read out of the `.msi`, not guessed

`kachok-0.1.0.msi`, queried through the Windows Installer database with no installation:

```
ProductCode    {BF6F8C43-56B8-38FA-B451-081086A1B259}
ProductVersion 0.1.0
UpgradeCode    {F5D8042F-8065-4A63-BB60-DD440A0F1411}

Upgrade table
  {F5D8042F-…}  VersionMin —       VersionMax 0.1.0   attrs 257  → JP_UPGRADABLE_FOUND
  {F5D8042F-…}  VersionMin 0.1.0   VersionMax —       attrs 1    → JP_DOWNGRADABLE_FOUND
```

Attributes 257 is `MigrateFeatures` + `VersionMinInclusive`, and **`VersionMaxInclusive` is not
set** — so the first row matches an installed version *strictly below* `0.1.0` and the second one
*strictly above* it. An installed `0.1.0` matches neither. On top of that `jpackage` derives
`ProductCode` from the name and the version, so two builds at `0.1.0` are byte-for-byte the same
product identity: Windows sees the same product at the same version already installed and performs
a maintenance repair, which by MSI's file-versioning rules leaves the existing files alone.

**Nothing was installed to find this out.** The database is readable from the file.

## Done

**The version's third component is the commit count.** There is no version-independent way to make
an MSI replace an installed one — that is MSI's rule, not `jpackage`'s — so the package version has
to change when the package changes. `0.1.<commits>` on Windows and Linux, `1.0.<commits>` on macOS,
where the leading zero is refused ([B-82](B-82-an-installer-per-platform.md)).

Verified by building again: `ProductVersion 0.1.119`, a **different** `ProductCode`, and an
`Upgrade` table whose first row now matches everything below `0.1.119` — including the `0.1.0` on
the machine that reported this.

**Two traps came with it, and both are guarded rather than remembered.**

* `actions/checkout` fetches **one** commit by default, so on CI the count would have been `1` for
  every build on every branch — a fleet of packages that all refuse to upgrade each other.
  `fetch-depth: 0` in `distributions.yml`.
* A tree with no git at all — a source tarball, or the copy this project rsyncs to the Windows box —
  counts zero commits. Packaging then fails with a sentence saying so, rather than producing a
  package that silently cannot be installed over anything. `-Pkachok.builds=<n>` is how somebody
  packaging from a tarball says which build it is. Verified by running `:ui:packageMsi` on the
  git-less copy: it stops.

A rebase that drops commits lowers the count, and the build after one will not install over its
predecessor. Written down rather than defended against: the fix is to install it deliberately, and
the alternative — a timestamp — is a version nobody can read.

- AC: an `.msi` built from a later commit installs over an earlier one and replaces the files; a
  build that cannot produce an increasing version refuses to package rather than producing one that
  cannot upgrade.
- Anchors: [`ui/build.gradle.kts`](../../ui/build.gradle.kts),
  [`.github/workflows/distributions.yml`](../../.github/workflows/distributions.yml).

**Automated:** `:ui:versionCanGoUp`, which every `package*` task depends on. Not automated: the
installation itself — it changes the machine it runs on, and what can be checked without doing that
is the package, which is what the numbers above are.
