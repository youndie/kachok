---
id: B-61
title: "The title bar the design draws, which is not the operating system's"
status: done
priority: P2
size: S
stage: phase-2-ui
epic: feature-ui
blocked_by: [B-56]
---

# B-61 — The title bar the design draws, which is not the operating system's

`docs/design/screens/main-window.png` draws a title bar in the design's own colours — `#161D1B`
with a hairline under it, traffic lights at 10 dp, the name centred in `#BEC9C6`. That is a custom
frame whether or not the design says so, and no OS chrome produces it. The window golden was cropped
to the 731 px underneath it and [B-49](B-49-details-panel.md) recorded that as a deviation.

- **The decision and its reason.** AppFrame (`io.github.youndie:appframe-desktop`), the portfolio's
  own library for exactly this. The controls stay the host's — traffic lights on macOS,
  minimise/maximise/close on Windows, the GTK button layout asked of the desktop on Linux — so the
  window is still native where it counts and the design's where the design cared.
- Rejected: drawing the bar by hand. Everything hard about an undecorated window is what the library
  is: transparency latched before the window is shown, the rounded-corner clip, `WindowPlacement`
  that respects the dock, macOS' refusal to fullscreen a borderless window.
- **`TitleBarStyle.MacOs` already was the design**, except two numbers: 10 dp of control padding
  and a 6 dp corner radius, both measured off the reference. The style is one value shared by the
  application and the golden, so the picture cannot drift from the window.
- **The repository is declared with `exclusiveContent`.** AppFrame is on reposilite's *releases* and
  `ru.workinprogress.sborka.settings` declares only the snapshot server and `mavenCentral()` after
  it; a plain `maven(...)` here would land after both and pay two round trips that are required to
  miss. `exclusiveContent` makes the group resolvable only from there, whatever the order is.

## What this cost, and what is left

- **The theme has to wrap `AppFrame`, not its content**, and it did not. The bar is composed inside
  the window from `surfaceVariant`, so with `KachokTheme` one level lower it came out of the default
  *light* scheme while everything under it was dark. **The golden could not catch it**: a golden
  cannot open a window, so it renders the same bar inside the theme and drew the right thing while
  the application drew the wrong one. Found by running it.
- **The title is `onSurfaceVariant` where the design has `#BEC9C6`.** `AppFrame` does not forward
  `color`/`contentColor` to the `TitleBar` it draws, though `TitleBar` takes both. One parameter,
  in another repository; not changed from here.

- AC: the window's title bar is the design's, and the golden covers all 1200 × 760 of the reference.
  **Automated:** the golden `main_window.png`, verified by `:ui:viddikVerify` in `make check`. Its
  traffic lights land at 10–22, 30–42 and 50–62 and its title's ink at 580–620, which is the
  reference's own geometry to the pixel.
- Anchors: `ui/src/desktopMain/kotlin/io/github/youndie/kachok/ui/App.kt`, `settings.gradle.kts`,
  `gradle/libs.versions.toml`.
