---
id: B-185
title: "The README says MIT, and the repository has no licence file"
status: question
priority: P1
size: XS
stage: phase-2-ui
---

# B-185 — The README says MIT, and the repository has no licence

A person who downloads kachok — or reads its code — has no licence to do either. The README's last
section says one word, `MIT.` (`README.md:116-118` at `1a521f3`, there since the first commit
`92132df`, 2026-09-05), but there is no `LICENSE` file at the root, and GitHub agrees with the file
rather than with the sentence: `gh repo view youndie/kachok --json licenseInfo` reports
`"licenseInfo": null` (read 2026-11-09). Research §1.3j says the same at
`docs/research/research-architecture.md:898` — "This repository has no licence".

It is the first of the items that make kachok installable by somebody who is not its owner
([B-186](B-186-the-msi-makes-no-desktop-or-start-menu-shortcut.md),
[B-187](B-187-a-tag-publishes-the-installers-as-a-github-release.md),
[B-189](B-189-the-readme-says-what-an-unsigned-installer-looks-like.md)), and the one the others
should not be shipped without: a GitHub Release is an offer of binaries to strangers, and binaries
offered with no licence are, by default, all rights reserved.

- **Why it is a question.** A licence is the owner's choice, not a loop's, and the README's one word
  is not evidence that it was made — it may be a placeholder from the bootstrap.
- **What the binaries carry besides kachok's own code.** The installers bundle two of the owner's
  own libraries, appframe and oldge-ui; `gh repo view youndie/appframe` and `youndie/oldge-ui` also
  report `licenseInfo: null` (read 2026-11-09). What their published POMs declare is not checked
  here. Whatever is chosen for kachok, a release ships their code too, so the same question stands
  in those two repositories — as items there, not here.
- [B-158](B-158-conveyor-as-the-packager.md) asked for a licence for a different reason (Conveyor's
  free tier); it was deferred, and its decision names "a licence chosen for the repository for its
  own sake" as a reason to look again.
- Not covered: third-party notices for the bundled dependencies (a `NOTICE` or an about box), and
  a contributor agreement.

## Question — for the owner

1. **MIT, as the README already says.** A `LICENSE` file with the MIT text and the copyright line;
   nothing else changes.
2. **Another licence** (Apache-2.0 adds a patent grant; GPL-3.0 keeps forks open). The `LICENSE`
   file, and the README's last section changed to match.
3. **No licence, on purpose.** Then the README's `MIT.` is false and goes, and the README says the
   code is visible but not licensed — and B-187 publishes binaries under that.

- AC: `gh repo view youndie/kachok --json licenseInfo` names an SPDX id, and it is the one the
  README names; or, for answer 3, the README says there is none.
- Anchors: `README.md`, `docs/research/research-architecture.md`.
