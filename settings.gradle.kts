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
    id("ru.workinprogress.sborka.settings") version "0.2.0.29"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "kachok"

// Phase 1: the engine (multiplatform, JVM target only for now) and the headless CLI.
// Phase 2 adds `:ui` (Compose Multiplatform, desktop + wasmJs); phase 3 adds the Android and iOS
// targets to `:engine`. See docs/research/research-architecture.md §4.
include(":engine")
include(":cli")

// Phase 2's desktop UI. It reads the engine's one `StateFlow` and sends commands through its one
// channel — the seam research D7 asked phase 1 to leave, now with something on the other side.
include(":ui")
