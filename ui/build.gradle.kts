plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viddik)
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
}

kotlin {
    // One target for now, and named `desktop` because viddik's Gradle plugin reads the target's
    // name to decide which `ksp*` configuration its processor goes on — `jvm("desktop")` gives
    // `kspDesktopTest`, an unnamed `jvm()` gives `kspJvmTest`, and a wrong guess does not fail the
    // build: KSP reports SKIPPED and the screenshot task passes with no tests in it.
    jvm("desktop")

    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(projects.engine)
                implementation(wip.kotlinx.coroutines.core)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.desktop.currentOs)
                implementation(libs.appframe)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(wip.kotlinx.coroutines.test)
                // The same tracker and seeding peer the headless client is tested against, so
                // "the list shows it progressing" is measured against a real download.
                implementation(projects.swarm)
                // What a golden cannot answer: whether the text a person needs is on the screen at
                // all, and whether it is still there a minute later.
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
    }
}

// The desktop application, with the JVM flags the research settled on: the same three the headless
// client pins in `cli/build.gradle.kts`. A UI does not get to run a different VM from the one every
// measurement was taken on, and `-Xmx128m` is the budget the whole engine is designed against.
compose.desktop {
    application {
        mainClass = "ru.workinprogress.kachok.ui.AppKt"
        jvmArgs += listOf("-XX:+UseG1GC", "-XX:+UseCompactObjectHeaders", "-Xmx128m")
    }
}

viddik {
    // **Only where the goldens were recorded.**
    //
    // A golden is a picture of one rasteriser's output. Recording on macOS and verifying on the
    // Linux build machine compares two renderers and calls the difference a regression — which is
    // what `check` did the first time this said `true` unconditionally.
    //
    // Not a silent skip, either: the goldens are a gate, and `make check` runs
    // `:ui:viddikVerify` on the mac where it means something. What is off here is the *duplicate*
    // that would run in the wrong place.
    verifyOnCheck.set(System.getProperty("os.name").orEmpty().startsWith("Mac"))
}
