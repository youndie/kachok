plugins {
    alias(libs.plugins.kotlinJvm)
    application
    id("ru.workinprogress.sborka.base")
    id("ru.workinprogress.sborka.test")
    id("ru.workinprogress.sborka.lint")
}

dependencies {
    implementation(projects.engine)
    implementation(wip.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    // The end-to-end test runs a tracker and a seeding peer of its own; both are the JDK's.
    testImplementation(wip.kotlinx.coroutines.test)
}

application {
    mainClass.set("ru.workinprogress.kachok.cli.MainKt")
    // The JVM flags the research settled on — every one of them measured, in research §1.2d, by
    // `./gradlew :cli:collectorBench`. They are here and not in a launcher script so that
    // `./gradlew :cli:run` measures the same VM a distribution would run.
    applicationDefaultJvmArgs =
        listOf(
            // Explicit, not inherited from whatever the JDK defaults to: the AOT cache is bound to
            // the collector that built it and a mismatch is refused in silence (research §1.2).
            // G1 over ZGC costs 4 ms of pause five times a gigabyte and saves 90 MB resident.
            "-XX:+UseG1GC",
            // Not measurable at this scale — the live set is 8 MB, so the header saving is under a
            // megabyte — but free, and phase 2's UI is where it starts to count.
            "-XX:+UseCompactObjectHeaders",
            // 128, not the brief's 256: the same 8 MB live set, 80 MB less resident memory, and
            // fewer total pause milliseconds than 256m. At 64m the total pause doubles.
            "-Xmx128m",
        )
}

// The collector comparison of B-27. Not part of `build`: it takes minutes, it measures this
// machine as much as this code, and a number produced on a shared CI runner would be worse than
// no number. `./gradlew :cli:collectorBench -PbenchArgs="--runs 5"` to vary it.
tasks.register<JavaExec>("collectorBench") {
    group = "verification"
    description = "Runs the same local-swarm download under G1 and ZGC, with and without compact object headers"
    mainClass.set("ru.workinprogress.kachok.cli.CollectorBench")
    classpath = sourceSets["test"].runtimeClasspath
    // The harness holds the whole payload in memory to serve it; the client under test is the one
    // whose heap is being measured, and it gets its own.
    jvmArgs("-Xmx3g")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(java.toolchain.languageVersion.get())
        },
    )
    args = (project.findProperty("benchArgs") as String?)?.split(" ") ?: emptyList()
}
