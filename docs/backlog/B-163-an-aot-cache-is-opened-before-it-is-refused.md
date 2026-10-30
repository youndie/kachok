---
id: B-163
title: "The AOT checks look for a line the VM prints before it refuses the cache"
status: open
priority: P2
size: S
stage: m7-measure
epic: feature-cli
---

# B-163 — An AOT cache is opened before it is refused

`:cli:aotCache` (B-28) fails the build unless `-Xlog:aot=info` contains `Opened AOT cache`, and
`scripts/verify_runtime_image.sh` greps for the same line. Measured for B-157 on 2026-10-30
(research §1.3h): the VM prints that line **first** and then refuses a cache whose jars changed —

    [info ][aot] Opened AOT cache /tmp/b157/kachok.aot.
    [warning][aot] … timestamp has changed
    [error  ][aot] Unable to map shared spaces

— so both checks pass on exactly the silent slow start they exist to catch (research Risk 3).

- **The decision and its reason.** Pass only on a line that a *mapped* cache prints and a refused
  one does not, and fail on any `[error` from `aot`. Which line that is gets read off both logs on
  this JDK, not guessed: the positive control is the refusal above, reproduced by touching one jar
  of the image after training.
- Rejected: checking the start-up time instead. It is the effect, and it varies more than the
  difference on a loaded machine.
- Not covered: the window's cache (B-157).

- AC: `:cli:aotCache` and `verify_runtime_image.sh` fail on an image with one jar touched after
  training, and pass on the untouched one.
- Anchors: `cli/build.gradle.kts`, `scripts/verify_runtime_image.sh`.
