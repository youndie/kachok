pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

plugins {
    // Lets Gradle fetch the JDK the toolchain asks for instead of demanding it be installed first.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Repositories with content filters, the shared `wip` catalog, the `.editorconfig` check.
    id("ru.workinprogress.sborka.settings") version "0.2.0.30"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

/**
 * AppFrame, which is published to reposilite's **releases** and nowhere else.
 *
 * `ru.workinprogress.sborka.settings` declares the *snapshot* server for `io.github.youndie.*` and
 * `mavenCentral()` after it; a plain `maven(...)` here would land after both, and every request for
 * this one coordinate would pay two round trips that are required to miss — the thing sborka's own
 * repository order exists to prevent.
 *
 * `exclusiveContent` avoids the question instead of answering it: the group is resolvable **only**
 * from here, so no other repository is asked for it whatever the declaration order is.
 *
 * `includeGroup` and not a regex: `io.github.youndie.viddik` is a different group and stays on
 * Maven Central, where it is.
 */
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven("https://reposilite.kotlin.website/releases") { name = "wip-releases" }
            }
            filter { includeGroup("io.github.youndie") }
        }
    }
}

rootProject.name = "kachok"

// Phase 1: the engine (multiplatform, JVM target only for now) and the headless CLI.
// Phase 2 adds `:ui` (Compose Multiplatform, desktop + wasmJs); phase 3 adds the Android and iOS
// targets to `:engine`. See docs/research/research-architecture.md §4.
include(":engine")
include(":cli")

// Phase 2's desktop UI. It reads the engine's one `StateFlow` and sends commands through its one
// channel — the seam research D7 asked phase 1 to leave, now with something on the other side.
include(":ui")

// The contract between a client and the engine, as plain serialisable data: what a session looks
// like on the wire and what a surface can ask of it. Its own module because the browser build needs
// it and must not drag the engine — which has sockets in it — into a target that has none.
include(":wire")

// A tracker and a seeding peer on localhost, so that both surfaces are tested end to end against
// the same fake instead of against one each. Test-only: nothing publishes it.
include(":swarm")
