---
id: B-163
title: "The AOT checks look for a line the VM prints before it refuses the cache"
status: done
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

**Done.** Both checks now pass only on `Using AOT-linked classes: true` and fail on any line
matching `[error …][aot`. The line was read off both logs on the Linux box (Ubuntu's `openjdk-25`
25.0.4.1, x64), from the `:cli:aotCache` image run with `-Xlog:aot=info`, once as built and once
with `annotations-23.0.0.jar` touched. Both logs print `Opened AOT cache` **and** `Mapped static
region #0`/`#1`. The refused one then prints the timestamp warning, three `[error  ][aot]` lines and
unmaps the regions; only the mapped one goes on to the heap data, the module graph and `Using
AOT-linked classes: true`, its last line (research §1.3h).

- **Positive control, run on the Linux box.** A temporary line touching that jar right after the
  training run, in the task and in the script's container; neither is committed.

  | | untouched | one jar touched |
  |---|---|---|
  | `:cli:aotCache`, before (`Opened AOT cache`) | — | **passes** |
  | `:cli:aotCache`, after | passes | fails: `the VM did not map it`, the three errors |
  | `:cli:aotCache`, mapped line only / error only | — | each fails alone |
  | `verify_runtime_image.sh`, before | — | **passes**: `Unable to map shared spaces`, then `==> ok` |
  | `verify_runtime_image.sh`, after | passes, exit 0 | fails, exit 1 |

- **`verify_runtime_image.sh` exited 1 on Linux even when it passed.** The containers run as
  root, so on Linux the runtime, the cache and the downloads they leave in `$work` are root's, and
  the `rm -rf` in the exit trap failed after `==> ok` was printed. Docker Desktop maps the owner,
  which is why it never showed on the Mac. The cleanup now removes those files through a container.
  This is in the item only because without it the exit code could not tell the two cases apart.
- Not changed: `scripts/aot_startup.py` (B-157's ruler) already reads `"[error" not in text`
  beside `Opened`, and B-157's AC still names `Opened AOT cache` — that is the window's item.

- AC: `:cli:aotCache` and `verify_runtime_image.sh` fail on an image with one jar touched after
  training, and pass on the untouched one — **met**, the table above.
