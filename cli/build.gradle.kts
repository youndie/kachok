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

/**
 * The JVM flags the research settled on — every one of them measured, in research §1.2d, by
 * `./gradlew :cli:collectorBench`.
 *
 * One list, read by `run`, by `installDist`'s start scripts and by the run-time image's launcher,
 * so that there is no way to measure a VM the distribution would not run.
 */
val kachokJvmFlags =
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

/**
 * The JDK modules the client needs, read out of the jars with `jdeps` rather than remembered
 * (research §1.3b), plus one that is there on purpose.
 *
 * A module set is the part of a run-time image that fails late: everything links, and the missing
 * class turns up the first time a code path runs — on a machine with no JDK to fall back on.
 * `jdeps --print-module-deps` names four; `jdk.jfr` is the fifth and is a decision, because
 * `-XX:StartFlightRecording` on an image without it does not warn and does not degrade, it refuses
 * to start the VM ("Module jdk.jfr not found" from the boot layer). 824 KB to keep the
 * distribution profilable.
 */
val kachokModules =
    listOf(
        "java.base",
        // kotlinx-coroutines' debug agent; loaded only if enabled, which is exactly why leaving it
        // out would be found by a user and not by a build.
        "java.instrument",
        "java.net.http",
        // `sun.misc.Unsafe`, reached from the Kotlin runtime.
        "jdk.unsupported",
        "jdk.jfr",
    )

application {
    mainClass.set("ru.workinprogress.kachok.cli.MainKt")
    applicationDefaultJvmArgs = kachokJvmFlags
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

/**
 * The phase-1 distribution (research D10): a `jlink`ed run-time image, the jars beside it, and a
 * launcher that fixes the flags — no `jpackage`, because the application is not modular and a
 * headless CLI needs no installer.
 *
 * `./gradlew :cli:runtimeImage` builds it into `cli/build/kachok`. The image is for the machine
 * that built it; `scripts/verify_runtime_image.sh` builds the Linux one in a container and runs a
 * real download through it on a machine with no JDK at all, which is the only way to find out
 * whether the module set is complete.
 */
abstract class BuildRuntimeImage : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:InputFiles
    abstract val jars: ConfigurableFileCollection

    @get:Input
    abstract val javaHome: Property<String>

    @get:Input
    abstract val modules: ListProperty<String>

    @get:Input
    abstract val jvmFlags: ListProperty<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun build() {
        val root = destination.get().asFile
        // jlink refuses to write into a directory that exists, and a stale image is worse than
        // none: it would keep answering after the module set changed.
        root.deleteRecursively()
        root.mkdirs()

        exec.exec {
            executable = File(javaHome.get(), "bin/jlink").path
            args(
                "--add-modules",
                modules.get().joinToString(","),
                "--strip-debug",
                "--no-man-pages",
                "--no-header-files",
                "--compress",
                "zip-6",
                "--output",
                File(root, "runtime").path,
            )
        }

        val lib = File(root, "lib").also { it.mkdirs() }
        jars.files.filter { it.isFile && it.name.endsWith(".jar") }.forEach { jar ->
            jar.copyTo(File(lib, jar.name), overwrite = true)
        }

        val bin = File(root, "bin").also { it.mkdirs() }
        val launcher = File(bin, "kachok")
        launcher.writeText(
            """
            #!/bin/sh
            # Generated by :cli:runtimeImage. The flags come from cli/build.gradle.kts, which is
            # also what `./gradlew :cli:run` uses — one place to change them, and no way to measure
            # a VM this launcher would not run.
            set -e
            here=$(cd "$(dirname "$0")/.." && pwd)
            exec "${'$'}here/runtime/bin/java" ${jvmFlags.get().joinToString(" ")} \
              -cp "${'$'}here/lib/*" ${mainClass.get()} "${'$'}@"
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true)

        // Read by scripts/verify_runtime_image.sh, so that the container builds the same image
        // this task does rather than a second copy of the same list.
        File(root, "image.properties").writeText(
            "modules=${modules.get().joinToString(",")}\n" +
                "flags=${jvmFlags.get().joinToString(" ")}\n" +
                "mainClass=${mainClass.get()}\n",
        )
    }
}

tasks.register<BuildRuntimeImage>("runtimeImage") {
    group = "distribution"
    description = "A jlinked run-time image, the jars and a launcher: the phase-1 distribution"
    jars.from(tasks.named("jar"), configurations.named("runtimeClasspath"))
    javaHome.set(
        javaToolchains
            .launcherFor { languageVersion.set(java.toolchain.languageVersion.get()) }
            .map { it.metadata.installationPath.asFile.path },
    )
    modules.set(kachokModules)
    jvmFlags.set(kachokJvmFlags)
    mainClass.set(application.mainClass)
    destination.set(layout.buildDirectory.dir("kachok"))
}

// The swarm `scripts/verify_runtime_image.sh` points the containerised client at.
tasks.register<JavaExec>("swarmHost") {
    group = "verification"
    description = "Serves one torrent to anything that can reach this machine, until killed"
    mainClass.set("ru.workinprogress.kachok.cli.SwarmHost")
    classpath = sourceSets["test"].runtimeClasspath
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(java.toolchain.languageVersion.get())
        },
    )
    args = (project.findProperty("swarmArgs") as String?)?.split(" ") ?: emptyList()
}
