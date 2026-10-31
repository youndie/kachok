---
id: B-177
title: "The oldge kit draws the banner's button and the details panel's tabs with its own components"
status: wip
priority: P3
size: S
stage: phase-2-ui
blocked_by: [B-176]
---

# B-177 — The oldge kit draws the banner button and the details tabs

After B-176 two controls in the oldge window are still the Material design's, drawn by the screens:
the degraded banner's *Show it* (a bordered box with red text) and the details panel's tabs (text
with an accent underline). The owner asked for both, 2026-10-31.

- **The decision and its reason.** Both become kit members, as the toolbar's controls did:
  `BannerButton` and `Tabs`. The Material kit draws what the screens draw now, moved and not
  rewritten, so its goldens do not move. The oldge kit uses the library's components: *Show it* is
  `OldgeButton`, *Secondary*, and the tabs are `OldgeTabs`, the design system's property-sheet tabs.
- **The banner stays this window's red band.** oldge-ui has a banner, `OldgeBanner`, but it is the
  yellow information strip — "a state that wants attention without blocking". A failed session is
  an error, and its banner keeps the error colours. Only the button changes. The library's README
  says a banner's actions are chrome buttons, and *Secondary* is that.
- **Sizes.** The banner is 32 dp and the library's smallest button is 36, so the button is set to
  26 dp from outside, the way the toolbar's search bar was. The tab strip's height becomes the
  kit's: oldge-ui's current tab stands 44 dp, against the Material strip's 32.

- AC: under the oldge kit *Show it* is an oldge-ui button and the details tabs are `OldgeTabs`;
  the Material goldens are byte-identical; the oldge goldens show both.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/main/DegradedBanner.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/details/DetailsPanel.kt`.
