---
id: B-159
title: "The screens draw through a kit of their own, and Material becomes one implementation of it"
status: done
priority: P3
size: L
stage: phase-2-ui
---

# B-159 — The screens draw through a kit, not through Material

The window is to offer a choice of interface: the current look, and
[oldge-ui](https://github.com/youndie/oldge-ui) (B-160), with more to come. Today there is nothing
to choose between, because the screens reach into Material directly — 31 reads of
`MaterialTheme.colorScheme`, typography roles, `KachokShapes`, the `warningColors` extension and
55 uses of `KachokPalette` (`ui/theme/`).

**The screens use Material's tokens and almost none of its components.** The only Material 3
components imported anywhere in `ui/src/*Main` are `Text` and, once in `desktopMain`, `Surface`;
the toggle, the dialog button, the fields, the table, the toolbar and the status bar are drawn on
`foundation`, because the M3 versions are the wrong size for a desktop (`settings/Settings.kt`
says so of the text field: a label slot, a supporting line and 56 dp in a 30 dp row). So what is called "Material" here is the design's own components painted with a
Material colour scheme. That makes the seam cheaper than it sounds — and it also means a kit that
only swaps colours would make oldge-ui look like a recoloured version of this design, which is not
what oldge-ui is.

- **The decision and its reason.** Two layers behind one `KachokKit` provided at the root:
  **semantic tokens** — the roles the screens actually ask for (three text levels, accent,
  warning, error, hairlines, surfaces, shapes, type) rather than Material's names for them — and
  **components** the screens call by role (button, toggle, field, menu, dialog, progress, table
  row, toolbar, banner). A kit implements both. Tokens alone cannot express a glossy chrome orb, and
  components alone leave every screen reading colours behind the kit's back.
- The current look moves behind it unchanged and is the first kit, so this item ships no visible
  change: the twelve viddik goldens are the proof, and must not move.
- Rejected: a theme switch inside `MaterialTheme` — a second `ColorScheme`. It is a recolouring,
  and the only kit it can describe is this one.
- Rejected: an `expect`/`actual` or an interface per screen. The screens are one design; what varies
  is how a role is drawn, not what the screen is.
- Not covered: the setting that chooses a kit (B-160 adds it, when there is a second kit to
  choose), colour choices within a kit (B-161, B-162).
- Overlaps B-80, which is moving the same files into `commonMain`: whichever lands second rebases
  over every screen, so the two are not worked at once.

- AC: no file under `ui/src/*/kotlin/**/ui/` other than the Material kit imports
  `androidx.compose.material3` or reads `KachokPalette`; the twelve goldens pass unchanged; a grep
  check in `make check` keeps it that way.
- Anchors: `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/Theme.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/theme/Colors.kt`,
  `ui/src/commonMain/kotlin/io/github/youndie/kachok/ui/settings/Settings.kt`,
  `docs/design/design-tokens.md`.

## Done — 2026-10-30

**What exists.** `ui/theme/Kit.kt`: `KachokKit` with `KachokColors` (39 roles by purpose — the
three text levels, `accent`, grounds, lines, the row and card tints, the warning family),
`KachokType` (`headline`, `section`, `action`), a `Root` the kit's library needs, `Text` and the
hover hint's `Tooltip`. `KachokTheme(kit = MaterialKit)` provides it under the interface scale, and
`KachokTheme.colors`/`.type` replace every `MaterialTheme.colorScheme`, `MaterialTheme.typography`,
`MaterialTheme.warningColors` and `KachokPalette` read in the screens. `ui/theme/material/` is the
Material kit: the hand-written dark scheme, the palette, the warning role and the M3 typography
and shapes, moved there unchanged. `make check` refuses any `*.kt` in `commonMain`/`desktopMain`
outside that directory that names `androidx.compose.material3`, `KachokPalette` or
`KachokDarkColors` — run against a planted import, it named the file; against the tree, nothing.

**Narrower than the decision above, on purpose.** The decision put the components behind the kit
too — button, toggle, field, menu, dialog, progress, table row, toolbar, banner. They are not: they
are already drawn on `foundation` and now read only the kit's tokens, so under a second kit they
recolour correctly and keep this design's *shape*. Which of them a second kit has to draw itself is
something only the second kit can answer, and guessing an interface for nine components before it
exists is the abstraction this repository's conventions warn against. **B-160 moves each component
it needs into the kit** — that is now part of its scope. What *is* behind the kit is what no
`foundation` drawing can do without a library: text (whose default style and colour the kit's root
sets) and the shadowed hint surface.

**The acceptance criterion's scope, narrowed.** The guard covers the main source sets, not the
tests: `ThemeSheet` (the `theme - roles-and-type` golden) and `RowColorsTest` are tests *of* the
Material kit and name it on purpose.

**Verified.** The twelve viddik goldens pass unchanged on macOS (`:ui:viddikVerify`, 12 of 12) —
the refactor drew the same pixels, which is the whole claim. `:ui:check` on the Linux box: 300
tests in 48 classes, no failures, the packaged image's preflight included.

**Found for B-160.** `AppFrame` draws the title bar from `MaterialTheme.colorScheme` itself
(`docs/services/ui.md` §8), so under a non-Material kit the bar would keep Material's colours
unless the kit's `Root` still provides a `MaterialTheme` built from its own tokens — or the bar
becomes the kit's, which is the `OldgeWindowBar` question B-160 already asks. The three type
styles that are not roles (`MonoFigure`, `RowName`, `ChromeText` and the rest in `theme/Type.kt`)
name this design's families directly; a kit with other fonts replaces them, which B-160's Cyrillic
question will force anyway.

