---
id: B-53
title: "The feature document the phase 2 epic names"
status: open
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-52]
---

# B-53 — The feature document the phase 2 epic names

Every item of this stage carries `epic: feature-ui`, and there is no `docs/features/feature-ui.md`
for it to point at. Noticed closing [B-47](B-47-torrent-row-and-states.md): its acceptance criteria
had no BDD scenario to attach an `**Automated:**` line to, because the document those scenarios
would live in does not exist.

- **The decision and its reason.** Write it *after* [B-52](B-52-ui-on-the-real-engine.md) rather
  than now. `main` describes what exists: until the window runs a real session, every scenario in
  such a document would be `target`, and a feature document that is entirely target is a plan
  wearing the wrong template. B-52 has since closed, so the behaviour is there to write about —
  and it brought three scenarios with it: the rates the surface derives, the *paused* state that is
  still planned, and a tracker's refusal that is not a degraded session. The screens are meanwhile documented where they belong — the design
  tokens in `docs/design/design-tokens.md`, each screen's decisions in its own backlog item.
- Rejected: writing it now as `status: draft` on a branch. The branch would have to stay open
  across five items, and the rule this repository runs on is that an open pull request describes
  one change.
- Not covered: `docs/screens/`. Whether phase 2 needs that layer as well as the design document is
  a question for the same moment, and the answer may be no — the mockup is the screen inventory.

- AC: `docs/features/feature-ui.md` exists with scenarios written against behaviour observed in the
  running app, and `python3 scripts/coverage_map.py --check` passes with it in the map.
- Anchors: `docs/design/design-tokens.md`, `ui/src/desktopMain/kotlin/ru/workinprogress/kachok/ui/`.
