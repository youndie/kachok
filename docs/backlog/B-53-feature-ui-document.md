---
id: B-53
title: "The feature document the phase 2 epic names"
status: done
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-52]
---

# B-53 — The feature document the phase 2 epic names

Every item of this stage carried `epic: feature-ui` and there was no `docs/features/feature-ui.md`
for it to point at. Noticed closing [B-47](B-47-torrent-row-and-states.md): its acceptance criteria
had no BDD scenario to attach an `**Automated:**` line to, because the document those scenarios
would live in did not exist.

- **The decision and its reason.** Written *after* [B-52](B-52-ui-on-the-real-engine.md) rather
  than before. `main` describes what exists: until the window ran a real session, every scenario
  would have been `target`, and a feature document that is entirely target is a plan wearing the
  wrong template. Written now, all eight of its scenarios name a test that runs.
- **Its one rule is the one the stage turned out to be about:** *the window may not say more than
  the engine knows*. Every deviation recorded across B-47 to B-54 is a case of it — the derived
  rates, the planned tabs, the greyed *Add*, the paused state that does not exist — so the document
  states it once and the scenarios are the evidence.
- Rejected: writing it as `status: draft` on a branch across five items. The branch would have had
  to stay open the whole way, and the rule this repository runs on is that an open pull request
  describes one change.
- Not covered: `docs/screens/`. Phase 2 does not get that layer — the design document *is* the
  screen inventory, it is checked into the repository, and a second prose copy of it would be one
  more thing to keep in step with the mockup.

- AC: `docs/features/feature-ui.md` exists with scenarios written against behaviour observed in the
  running app, and `python3 scripts/coverage_map.py --check` passes with it in the map.
  **Automated:** the eight scenarios each name a test; `make check` runs `coverage_map.py --check`,
  and `bdd_report.py` counts 8 of 8 automated.
- Anchors: `docs/features/feature-ui.md`, `docs/design/design-tokens.md`,
  `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/`.
