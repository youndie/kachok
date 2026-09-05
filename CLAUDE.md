# kachok — instructions for coding agents

## How to start a session

1. Read [docs/research/research-architecture.md](docs/research/research-architecture.md) — it says
   why the engine is built the way it is, which claims were verified and against what, and where
   the design departs from the original brief. A task that is not read against it will "do the
   obvious thing", which here is frequently wrong (heap buffers, per-peer timers, `ScopedValue` in
   coroutines, `force()` per piece).
2. Read [backlog.md](backlog.md) and the item you are working on in `docs/backlog/`. Items are in
   dependency order; an item's `blocked_by` is a fact, not a preference.
3. Read the service document of the module you are touching — `docs/services/engine.md` or
   `docs/services/cli.md` — and the feature document the item's `epic` names, if it is on your
   branch.

## The two rules of the documentation

- **`main` describes what exists. An open pull request describes what will be.** A feature
  document for behaviour that is not built is `status: draft` on a branch; on `main`,
  `python3 scripts/docs_check.py --on-main` fails on a draft.
- **Verified and assumed are kept apart.** A number you have not measured is a hypothesis and says
  so; a fact carries where it was read. When the implementation contradicts the research, amend the
  research *at the point of divergence* — "this used to say X; it cannot, because Y; the working
  replacement is Z" — do not delete the old reasoning.

## Code

- Kotlin 2.4, JDK 25, Gradle 9.7; conventions from `ru.workinprogress.sborka` (`explicitApi`,
  warnings as errors, ktlint with the portfolio's `.editorconfig`). `./gradlew build` is the gate.
- `:engine` is multiplatform with a single `jvm()` target. Do not add a target without an
  implementation behind it. Common code sees I/O through interfaces; platform primitives are
  `expect`/`actual`.
- The hot path allocates nothing: `piece`/`request` are parsed in place, blocks live in pooled
  direct `ByteBuffer`s, ids are value classes, bitfields and availability are primitive arrays.
- One root dispatcher on virtual threads; hashing on `limitedParallelism(cores)` of it; one
  writer coroutine; one timer per session. No `Dispatchers.IO` in engine code.
- No `ScopedValue.get()` after a suspension point. Session context is a `CoroutineContext`
  element.
- Language: English in code, comments and documents.

## Traps this repository has already fallen into

- **A `@Test` with an expression body can be silently skipped.** ktlint rewrites a single-statement
  test into `fun x() = runBlocking { … }`; if the last expression returns a value the method is
  non-void and JUnit ignores it. Write `fun x(): Unit = runBlocking { … }`. The `sborka.test` guard
  catches it — "declares 5 @Test and JUnit ran 1" — which is the only reason it was noticed.
- **A single-threaded test dispatcher hides every data race.** The engine runs on a
  virtual-thread-per-task executor, which is as parallel as the machine. State that looks confined
  in `runTest` is shared in production; the session buys confinement back with
  `limitedParallelism(1)` and nothing that blocks may run under it.
- **Iterating a collection across a suspension point races.** `map.values.forEach { suspendingSend(it) }`
  lets another coroutine mutate the map at the suspension. Iterate `values.toList()`.
- **`catch (Exception)` around a suspending call swallows `CancellationException`.** Rethrow it
  first, always, or the coroutine cannot be cancelled.
- **Never write bencode fixtures by hand.** Three of them have had wrong length prefixes. Build
  them with `Bencode.encode`, or generate them with an independent encoder when the parser itself
  is under test.
- **A test that acquires from a pool and then filters is a leak.** `blocksOf(…).take(2)` acquires
  four buffers and uses two. Ask for what you need.

## Before you finish

```bash
./gradlew build
make check
python3 scripts/backlog_index.py      # after editing any backlog item
```

Update the backlog item's status, and if you learned something the research did not know, write
it into the research with where you verified it.

## Commits and pull requests

Conventional Commits, in English, no tool signatures: `feat(engine): parse piece messages in
place`. Branch names likewise: `feat/peer-wire-codec`.
