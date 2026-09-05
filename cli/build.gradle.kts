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
        // An explicit, sorted class path rather than `lib/*`: an AOT cache is refused unless the
        // class path matches the one it was trained on, and a wildcard's expansion order is the
        // JVM's business rather than a promise (JEP 483).
        val classPath =
            lib
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted()
                .joinToString(":") { "\$here/lib/" + it }
        launcher.writeText(
            """
            #!/bin/sh
            # Generated by :cli:runtimeImage. The flags come from cli/build.gradle.kts, which is
            # also what `./gradlew :cli:run` uses — one place to change them, and no way to measure
            # a VM this launcher would not run.
            set -e
            here=$(cd "$(dirname "$0")/.." && pwd)
            # Written by :cli:aotCache, and absent until it has run. Asked for rather than passed
            # unconditionally: -XX:AOTCache pointed at a missing file is a warning on every start.
            cache=""
            if [ -f "${'$'}here/kachok.aot" ]; then cache="-XX:AOTCache=${'$'}here/kachok.aot"; fi
            # The training run and the smoke run go through this launcher rather than around it,
            # so that the VM the cache is built for is the VM that ships.
            exec "${'$'}here/runtime/bin/java" ${jvmFlags.get().joinToString(" ")} ${'$'}cache ${'$'}KACHOK_JVM_OPTS \
              -cp "$classPath" ${mainClass.get()} "${'$'}@"
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

/**
 * The AOT cache of research §1.2, built by a real download and then proved to be used.
 *
 * The proof is the point. A cache the VM refuses — a flag changed, a jar renamed, a different
 * collector — is not an error and not a slower error: it is a warning line and a completely normal
 * start, so a distribution that merely *has* a cache has no idea whether anyone benefits from it.
 * This task fails unless `-Xlog:aot=info` says the cache was opened.
 *
 * The training run is a whole download from a seed this task starts, not a start-up and exit: what
 * the cache is worth depends on which classes were loaded while it was recorded, and the wire, the
 * picker, the hasher and the writer are only loaded by a download that happens.
 */
abstract class BuildAotCache : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:InputDirectory
    abstract val image: DirectoryProperty

    @get:InputFiles
    abstract val swarmClasspath: ConfigurableFileCollection

    @get:Input
    abstract val javaHome: Property<String>

    @get:OutputFile
    abstract val cache: RegularFileProperty

    @TaskAction
    fun build() {
        val root = image.get().asFile
        val launcher = File(root, "bin/kachok")
        val cacheFile = cache.get().asFile
        cacheFile.delete()

        val work =
            File(root, "training").also {
                it.deleteRecursively()
                it.mkdirs()
            }
        val swarm = startSwarm(work)
        try {
            val torrent = awaitTorrent(work)
            run(launcher, "-XX:AOTCacheOutput=${cacheFile.path}", torrent, File(work, "train-out"))
        } finally {
            swarm.destroy()
            swarm.waitFor()
        }
        if (!cacheFile.isFile) throw GradleException("the training run produced no AOT cache")

        // The same launcher again, now that the cache exists, asking the VM whether it used it.
        val log = File(work, "aot.log")
        val torrent = File(work, "fixture.torrent")
        val swarmAgain = startSwarm(File(root, "training-check").also { it.mkdirs() })
        try {
            val second = awaitTorrent(File(root, "training-check"))
            run(launcher, "-Xlog:aot=info:file=${log.path}", second, File(work, "check-out"))
        } finally {
            swarmAgain.destroy()
            swarmAgain.waitFor()
        }
        val text = if (log.isFile) log.readText() else ""
        if (!text.contains("Opened AOT cache")) {
            throw GradleException(
                "the cache was built but the VM did not open it, which is a silent slow start " +
                    "rather than an error — research Risk 3. -Xlog:aot=info said:\n$text",
            )
        }
        logger.lifecycle("AOT cache ${cacheFile.length() / 1024} KB, and the VM opened it")
        File(root, "training").deleteRecursively()
        File(root, "training-check").deleteRecursively()
        if (torrent.exists()) torrent.delete()
    }

    private fun startSwarm(work: File): Process {
        work.mkdirs()
        return ProcessBuilder(
            listOf(
                File(javaHome.get(), "bin/java").path,
                "-cp",
                swarmClasspath.asPath,
                "ru.workinprogress.kachok.cli.SwarmHost",
                "--dir",
                work.path,
                "--megabytes",
                "8",
                "--bind",
                "127.0.0.1",
            ),
        ).redirectErrorStream(true).redirectOutput(File(work, "swarm.log")).start()
    }

    private fun awaitTorrent(work: File): File {
        val torrent = File(work, "fixture.torrent")
        val deadline = System.nanoTime() + SWARM_TIMEOUT_SECONDS * NANOS_PER_SECOND
        while (System.nanoTime() < deadline) {
            if (torrent.isFile) return torrent
            Thread.sleep(POLL_MILLIS)
        }
        throw GradleException(
            "the training swarm never came up: ${File(work, "swarm.log").takeIf { it.isFile }?.readText()}",
        )
    }

    private fun run(
        launcher: File,
        jvmOption: String,
        torrent: File,
        out: File,
    ) {
        exec.exec {
            commandLine(launcher.path, "download", torrent.path, "--dir", out.path)
            environment("KACHOK_JVM_OPTS", jvmOption)
        }
    }

    private companion object {
        const val SWARM_TIMEOUT_SECONDS = 60L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val POLL_MILLIS = 200L
    }
}

tasks.register<BuildAotCache>("aotCache") {
    group = "distribution"
    description = "Trains the distribution's AOT cache on a real download and proves the VM opens it"
    dependsOn(tasks.named("runtimeImage"))
    image.set(layout.buildDirectory.dir("kachok"))
    cache.set(layout.buildDirectory.file("kachok/kachok.aot"))
    swarmClasspath.from(sourceSets["test"].runtimeClasspath)
    javaHome.set(
        javaToolchains
            .launcherFor { languageVersion.set(java.toolchain.languageVersion.get()) }
            .map { it.metadata.installationPath.asFile.path },
    )
}
