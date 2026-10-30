import org.jetbrains.compose.desktop.application.dsl.TargetFormat

/**
 * The heap the desktop application gets, and the heap its tests get.
 *
 * 64m rather than the engine's 128m, asked for after watching the status bar on Windows. Research
 * §1.2d's own 64m row is about the *headless* client — it trades 35 MB of resident memory for twice
 * the total pause over a gigabyte — and says nothing about a window, whose largest surfaces are
 * Skiko's and are not on the heap at all. What says something about a window is the test task
 * below, which now runs in this same number.
 */
val maxHeap = "64m"

/**
 * Commits on this branch, which is the only number here that is guaranteed to go up.
 *
 * Through `providers.exec` rather than a plain call, so Gradle treats it as the build input it is
 * and the configuration cache does not hand every later build the first one's answer. Zero when
 * there is no git — a source tarball builds, and every one of its packages says `.0`, which is no
 * worse than the single fixed number this replaced.
 */
val builds: String =
    providers.gradleProperty("kachok.builds").orNull
        ?: providers
            .exec {
                commandLine("git", "rev-list", "--count", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput
            .asText
            .map { it.trim() }
            .orElse("0")
            .get()
            .ifBlank { "0" }

plugins {
    alias(wip.plugins.kotlinMultiplatform)
    alias(wip.plugins.composeMultiplatform)
    alias(wip.plugins.composeCompiler)
    alias(wip.plugins.ksp)
    alias(libs.plugins.viddik)
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

kotlin {
    // One target for now, and named `desktop` because viddik's Gradle plugin reads the target's
    // name to decide which `ksp*` configuration its processor goes on — `jvm("desktop")` gives
    // `kspDesktopTest`, an unnamed `jvm()` gives `kspJvmTest`, and a wrong guess does not fail the
    // build: KSP reports SKIPPED and the screenshot task passes with no tests in it.
    jvm("desktop")

    sourceSets {
        // **The screens, their mapping functions and the theme are common**, which is what B-41
        // needs to be true before it can begin: a target added later gets `actual`s rather than a
        // rewrite, the way the engine has always been written
        // ([B-80](../docs/backlog/B-80-the-ui-moves-to-commonmain.md)).
        val commonMain by getting {
            dependencies {
                implementation(projects.engine)
                implementation(wip.kotlinx.coroutines.core)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                // The second kit (B-160): `ui/theme/oldge/` draws the window in oldge-ui.
                implementation(libs.oldge.core)
                // The Material kit's colours from a seed (B-161), named only in `ui/theme/material/`.
                implementation(libs.materialkolor)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(projects.engine)
                // The single-instance lock, and — since B-117 — the MCP server the window
                // answers on it, so an agent and the person drive one engine.
                implementation(projects.control)
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

// **The JDK's own classes, for ProGuard, from a JDK that ships no `jmods`.**
//
// ProGuard has to read the platform's classes to know what a library extends and calls, and
// Compose gives it `<java.home>/jmods/*.jmod` — which the JDK on CI does not have. Temurin 25 is
// built to link run-time images without JMODs (JEP 493), so `jlink` works and the directory Compose
// reads is simply absent: on all three runners ProGuard started with no library at all, could not
// find `java.lang.Object` as the superclass of anything, and refused (B-155). The JDKs it worked on
// — Ubuntu's `openjdk-25` and JetBrains Runtime — both still ship `jmods`.
//
// So where there are none, the classes come out of the image the JDK runs from, `lib/modules`,
// through its own `jimage`, one directory per module, and a generated rules file names each as a
// library. Where there are `jmods` the rules file says so and names nothing, because Compose
// already does and the same classes twice is a page of duplicate-definition notes.
val jdkHome = File(compose.desktop.application.javaHome)
val jdkHasJmods = File(jdkHome, "jmods").isDirectory
val jdkClasses = layout.buildDirectory.dir("proguard/jdk-classes")

val extractJdkClasses by tasks.registering(Exec::class) {
    description = "Extracts the JDK's classes for ProGuard when the JDK has no jmods."
    // Into a local, so the predicate closes over a boolean and not over the script.
    val hasJmods = jdkHasJmods
    onlyIf { !hasJmods }
    val out = jdkClasses.get().asFile
    outputs.dir(out)
    doFirst { out.deleteRecursively() }
    val jimage = File(jdkHome, if (File(jdkHome, "bin/jimage.exe").exists()) "bin/jimage.exe" else "bin/jimage")
    commandLine(jimage.path, "extract", "--dir", out.path, File(jdkHome, "lib/modules").path)
}

val jdkLibraryRules by tasks.registering {
    description = "Names the JDK's classes as ProGuard libraries, or says Compose already does."
    dependsOn(extractJdkClasses)
    val out = jdkClasses.get().asFile
    val rules = layout.buildDirectory.file("proguard/jdk-library.pro")
    val home = jdkHome.path
    val hasJmods = jdkHasJmods
    inputs.property("javaHome", home)
    inputs.property("hasJmods", hasJmods)
    outputs.file(rules)
    doLast {
        val modules =
            out
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }
        check(hasJmods || modules.any { it.name == "java.base" }) { "jimage extracted no java.base from $home" }
        rules.get().asFile.writeText(
            if (hasJmods) {
                "# $home has jmods, which Compose already passes as libraries.\n"
            } else {
                "# The classes of $home, which has no jmods, extracted by jimage.\n" +
                    modules.joinToString("") { "-libraryjars '${it.invariantSeparatorsPath}'(!module-info.class)\n" }
            },
        )
    }
}

// The desktop application, with the JVM flags the research settled on: the same three the headless
// client pins in `cli/build.gradle.kts`. A UI does not get to run a different VM from the one every
// measurement was taken on, and `-Xmx128m` is the budget the whole engine is designed against.
compose.desktop {
    application {
        mainClass = "io.github.youndie.kachok.ui.AppKt"
        // `MaxDirectMemorySize` for the same reason as the headless client: the block pool is
        // direct memory, its ceiling would otherwise be `-Xmx` by accident, and a window sized
        // for 250 peers needs 68 MB of it (B-98).
        jvmArgs +=
            listOf(
                "-XX:+UseG1GC",
                "-XX:+UseCompactObjectHeaders",
                "-Xmx$maxHeap",
                "-XX:MaxDirectMemorySize=192m",
            )

        // **What ships is the release variant: the classes through ProGuard** (B-155).
        //
        // 30 MB of jars were every class of every dependency, reached or not; through the shrinker
        // the same jars are under half that. Compose's defaults are kept — optimisation on,
        // obfuscation off, so a stack trace somebody sends still names the code it came from. The
        // rules of this project's own are in the file, each with the failure that put it there.
        //
        // **A shrinker removes what nothing *names*, and a check has to run what ships.** So every
        // task below that runs or packages the image is a `Release` one: `checkDistributable`
        // asking the old image while the installers carried the shrunk one would be B-78 again,
        // with a class ProGuard took out in place of a module `jlink` left out.
        buildTypes.release.proguard {
            configurationFiles.from(
                project.file("proguard-rules.pro"),
                jdkLibraryRules.map { it.outputs.files.singleFile },
            )
        }

        // The app image `createDistributable` writes, and what it is called inside it.
        //
        // `jpackage` cannot cross-compile: a Windows image is built on Windows and a macOS one on
        // macOS, which is why this is named here rather than assumed from the host. The name is
        // the product's, not the module's — the default is the Gradle project's, and an executable
        // called `ui.exe` is one nobody recognises in a task list.
        nativeDistributions {
            packageName = "kachok"
            description = "A BitTorrent client"
            vendor = "workinprogress"

            // **One version, from `gradle.properties`, plus a number that always goes up.**
            //
            // It was a literal here once, which was a second place the number lived; `jpackage`
            // will not take `-SNAPSHOT`, which is why somebody typed it twice.
            //
            // The third component is the commit count, and that is not decoration. **An `.msi`
            // whose `ProductVersion` has not changed does not upgrade the installed one** — read
            // out of the package itself: jpackage writes an `Upgrade` table that looks for an
            // installed version *strictly below* this one, and derives `ProductCode` from the name
            // and version, so two builds at `0.1.0` are the same product at the same version and
            // Windows does a repair instead of replacing the files. That is what "it did not
            // update" was. `.deb` and macOS bundles compare versions the same way.
            //
            // Monotonic on a linear history and on merges; a rebase that drops commits lowers it,
            // and the next build after one has to be allowed to go backwards or it will not
            // install. Worth knowing rather than worth defending against.
            packageVersion = "${project.version.toString().substringBefore("-").substringBeforeLast(".")}.$builds"

            // **One format per platform, because `packageDistributionForCurrentOS` with none
            // configured is a task that succeeds and produces nothing.** That is what it did: the
            // build's last step was `createDistributable`, an app *image* — a directory somebody
            // zips — which registers nothing with the operating system, and is why a `.torrent`
            // cannot be double-clicked ([B-84](../docs/backlog/B-84-torrent-files-open-with-the-client.md))
            // and an autostart entry has nowhere stable to point
            // ([B-83](../docs/backlog/B-83-autostart-and-its-setting.md)).
            //
            // `jpackage` cannot cross-compile, so each of these is produced on its own platform and
            // the host picks from this list. One each, deliberately: `.pkg` beside `.dmg` and
            // `.exe` beside `.msi` are two installers for one platform, and two is the number that
            // makes somebody ask which one they want. `.rpm` is not here because the build machine
            // has no `rpmbuild` and an untested format is worse than an absent one.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // **The runtime is cut down by `jlink`, and what is not named here is not in it.**
            //
            // Shipped once without them: the packaged application died on the first announce with
            // `NoClassDefFoundError: java/net/http/HttpClient`. Nothing caught it because
            // `:ui:run` and every test use the *full* JDK — a trimmed runtime only exists inside
            // `createDistributable`, and nothing runs what that produces (B-78).
            //
            // These three and no more: what `:ui:suggestRuntimeModules` reports, which is `jdeps`
            // over the jars, matched against the CLI's own list in research §1.3b.
            //
            // `jdk.crypto.ec` was added here on the reasoning that a JCA provider is loaded by name
            // and so cannot be found by static analysis, and that `https://` trackers need ECDHE.
            // The first half is true and the second is not, on this JDK: measured in §1.3e, an
            // image without that module completes a TLS 1.3 handshake against an EC certificate,
            // because the provider now lives in `java.base`. Adding a module against a guess is how
            // a trimmed runtime stops being trimmed.
            modules("java.instrument", "java.net.http", "jdk.unsupported")

            // **Three files for one drawing.** Each platform reads exactly one format and ignores
            // the others, so this is not a choice between them — a missing `.ico` is a Windows
            // build with the stock Java coffee cup on it.
            //
            // All three are generated by `scripts/make_icon.py` from the numbers in B-86, at every
            // size each container wants, rather than resampled from one export: the mark is five
            // rounded rectangles and a ring, and it is drawn at 16 px rather than shrunk to it.
            // `make check` runs that script with `--check`, so an icon edited by hand is a red
            // build rather than a silent divergence from the geometry it claims to be.
            val icons = project.layout.projectDirectory.dir("src/desktopMain/resources/icon")

            // **What a `.torrent` is for, said once per platform.** `fileAssociation` is on the
            // *platform* settings and not on `nativeDistributions`, so there is no place to say it
            // once — declaring it in one block associates the extension on one operating system and
            // silently on none of the others (B-84).
            //
            // The three of them take it to three different places: macOS writes
            // `CFBundleDocumentTypes` into the bundle's `Info.plist`, Windows writes registry keys
            // from the `.msi`, and Linux ships a `.desktop` file and a mime XML in the `.deb`. Only
            // the first of those works from an app image; the other two are the installer's doing,
            // which is why this item waited on B-82.
            val torrentFile = { it: org.jetbrains.compose.desktop.application.dsl.AbstractPlatformSettings ->
                it.fileAssociation("application/x-bittorrent", "torrent", "BitTorrent metainfo")
            }
            macOS {
                iconFile.set(icons.file("icon.icns"))
                torrentFile(this)

                // **macOS will not package a version starting with zero, and says so late.**
                //
                //     Bundler Mac Application Image skipped because of a configuration problem:
                //     The first number in an app-version cannot be zero or negative.
                //
                // `CFBundleShortVersionString` is Apple's namespace, not the project's, and its
                // first component must be at least 1 — so `0.1.0` cannot be a macOS build at all,
                // and every distribution anyone has produced so far was a Windows one (B-82).
                //
                // The bundle therefore says 1.0.0 while the project says 0.1.0, and that gap is
                // real: the number in a macOS *Get Info* panel is not this project's version. What
                // the project is at is on the settings screen, which is somewhere a person can read
                // it and Apple has no opinion about.
                packageVersion = "1.0.$builds"
            }
            windows {
                iconFile.set(icons.file("icon.ico"))
                torrentFile(this)

                // Without this every `.msi` is a *separate product*: installing 0.2.0 leaves 0.1.0
                // in place and two entries in the control panel. The value is arbitrary and must
                // never change again — it is the identity Windows upgrades along.
                upgradeUuid = "f5d8042f-8065-4a63-bb60-dd440a0f1411"
                menuGroup = "kachok"
                // A client is not a machine-wide service, and a per-user install is the one that
                // does not ask for the administrator password.
                perUserInstall = true
                dirChooser = true
            }
            linux {
                iconFile.set(icons.file("icon.png"))
                torrentFile(this)
                // `.deb` refuses to build without a maintainer, and the default jpackage invents
                // is the build user's login at the build host's name.
                debMaintainer = "youndie@users.noreply.github.com"
                menuGroup = "Network"
                appCategory = "Network"
            }
        }
    }
}

// **The `.desktop` entry jpackage writes cannot open a file, and that is jpackage's bug.**
//
// Its `template.desktop` — read out of `jdk.jpackage`'s own resources on the build machine — is
// `Exec=APPLICATION_LAUNCHER` with no field code, while the same template substitutes
// `DESKTOP_MIMES` right underneath it. By the freedesktop specification an `Exec` with no `%f`,
// `%F`, `%u` or `%U` is never given the file: the association would be there, the icon would be
// right, and double-clicking a `.torrent` would open an empty client. Nothing about that looks
// broken from the packaging side, which is why it is worth a task (B-84).
//
// Compose passes `--resource-dir` at a directory it clears inside its own task action, so there is
// no supported hook for a replacement template; the package is unpacked and rebuilt instead. The
// substitution is checked afterwards rather than trusted — a `sed` that matches nothing exits
// successfully, which is the failure this task exists to prevent.
val patchDesktopEntry by tasks.registering(Exec::class) {
    onlyIf { System.getProperty("os.name").orEmpty().let { !it.startsWith("Mac") && !it.startsWith("Windows") } }
    description = "Adds the %f jpackage leaves out, so a .torrent opens with the client on Linux."
    // **The directory, not the file name.** This used to compose the name out of the project's
    // version — a second place the package's version lived, and the moment `packageVersion` gained
    // a build number the two disagreed and CI failed with `no package at …kachok_0.1.0_amd64.deb`.
    // The directory holds exactly one `.deb`; the script insists on that rather than guessing which.
    val debDir = layout.buildDirectory.dir("compose/binaries/main-release/deb")
    commandLine(
        "sh",
        "-c",
        """
        set -eu
        dir="${'$'}1"
        count=${'$'}(find "${'$'}dir" -maxdepth 1 -name '*.deb' | wc -l)
        [ "${'$'}count" = "1" ] || { echo "expected one .deb in ${'$'}dir, found ${'$'}count"; exit 1; }
        deb=${'$'}(find "${'$'}dir" -maxdepth 1 -name '*.deb')
        work=${'$'}(mktemp -d)
        trap 'rm -rf "${'$'}work"' EXIT
        dpkg-deb -R "${'$'}deb" "${'$'}work"
        # `-type f`, and the first line has to say so: the trimmed runtime ships a *directory*
        # called `legal/java.desktop`, which a name-only match picks first and sed refuses.
        entry=${'$'}(find "${'$'}work" -type f -name '*.desktop' -exec grep -l '^\[Desktop Entry\]' {} + | head -1)
        [ -n "${'$'}entry" ] || { echo "the package carries no .desktop entry"; exit 1; }
        if grep -q '^Exec=.* %f${'$'}' "${'$'}entry"; then exit 0; fi
        sed -i 's|^\(Exec=.*\)${'$'}|\1 %f|' "${'$'}entry"
        grep -q '^Exec=.* %f${'$'}' "${'$'}entry" || { echo "the Exec line was not patched:"; cat "${'$'}entry"; exit 1; }
        fakeroot dpkg-deb -b "${'$'}work" "${'$'}deb" >/dev/null
        echo "the .desktop entry now passes the file it was opened with"
        """.trimIndent(),
        "sh",
        debDir.get().asFile.absolutePath,
    )
}

// The release `.deb` is the one that ships (B-155), so it is the one patched; `packageDeb` still
// builds the unshrunk variant for comparison and is left as jpackage wrote it.
tasks.matching { it.name == "packageReleaseDeb" }.configureEach { finalizedBy(patchDesktopEntry) }

// **The one thing that runs what `createReleaseDistributable` produced** — the image that ships.
//
// A `jlink` runtime is the only place a missing module can exist, and until this task there was
// nothing between "the image was linked" and "somebody downloaded it": `:ui:run` and every test use
// the full JDK. It shipped once without `java.net.http` (B-78, research §1.3e).
//
// The launcher is the check's entry point because it is the only executable in the image — `jlink`
// strips `runtime/bin`, so there is no `java` to run anything else with. That is also what makes
// this worth having: the runtime, the classpath and the JVM flags are the ones a person gets.
val preflightReport = layout.buildDirectory.file("compose/preflight.txt")

val distributionLauncher =
    layout.buildDirectory.file(
        when {
            System.getProperty("os.name").orEmpty().startsWith("Mac") -> {
                "compose/binaries/main-release/app/kachok.app/Contents/MacOS/kachok"
            }

            System.getProperty("os.name").orEmpty().startsWith("Windows") -> {
                "compose/binaries/main-release/app/kachok/kachok.exe"
            }

            else -> {
                "compose/binaries/main-release/app/kachok/bin/kachok"
            }
        },
    )

val checkDistributable by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the packaged application's own launcher against its trimmed runtime."
    dependsOn(tasks.named("createReleaseDistributable"))
    inputs.file(distributionLauncher)
    outputs.file(preflightReport)

    commandLine(
        distributionLauncher.get().asFile.absolutePath,
        "--preflight",
        preflightReport.get().asFile.absolutePath,
    )
    // The report is read either way. A packaged launcher that fails has already written which check
    // failed and why, and Gradle's own message for a non-zero exit is the exit code.
    isIgnoreExitValue = true
    val result = executionResult
    val report = preflightReport
    doLast {
        // The launcher prints its own report as it goes, on every platform — including Windows,
        // where a GUI subsystem executable was expected to swallow it and does not. This is the
        // second copy, and it is only worth having when the run failed: a build that stops with
        // "exit value 1" scrolled past the lines that say which check it was.
        val file = report.get().asFile
        if (result.get().exitValue != 0 && file.exists()) logger.error(file.readText().trimEnd())
        result.get().assertNormalExitValue()
    }
}

tasks.named("check") { dependsOn(checkDistributable) }

// **The shipped window, downloading** (B-155).
//
// The preflight exits before a window exists, so it cannot see what a shrinker breaks in one: a
// composable, a renderer, the MCP server's serialisation. `DistributionDownloadTest` starts the
// release image's launcher, waits for its engine on the single-instance lock and has its MCP
// server download a torrent from a seed on loopback, then compares the bytes.
//
// A test task of its own rather than part of `desktopTest`: it needs the built image and a display,
// neither of which the ordinary suite may assume. Not on `check` for the second reason — the Linux CI
// job has no display — and run by `distributions.yml` on each of the three runners instead, which is
// where an image that ships is built.
val desktopTests = tasks.named<Test>("desktopTest")
desktopTests.configure { filter { excludeTestsMatching("*.DistributionDownloadTest") } }

val checkDownload by tasks.registering(Test::class) {
    group = "verification"
    description = "Starts the packaged window and has it download a torrent over MCP from a local seed."
    dependsOn(tasks.named("createReleaseDistributable"))
    testClassesDirs = desktopTests.get().testClassesDirs
    classpath = desktopTests.get().classpath
    filter { includeTestsMatching("*.DistributionDownloadTest") }
    systemProperty("kachok.launcher", distributionLauncher.get().asFile.absolutePath)
    providers.gradleProperty("kachok.jvmOptions").orNull?.let { systemProperty("kachok.jvmOptions", it) }
    inputs.dir(layout.buildDirectory.dir("compose/binaries/main-release/app"))
}

// **The `.deb` carries an AOT cache trained on its own files** (B-157, research §1.3h).
//
// The cache is two thirds of the window's start — 936 → 388 ms on an installed copy — and it is
// refused by any copy of the jars other than the one it was recorded with: the VM compares each
// jar's modification time, and `packageReleaseDeb` copies the jars again, so a cache trained on
// `createReleaseDistributable` is a warning and a slow start on every installed machine. So it is
// trained *after* packaging, on the package: unpacked with `dpkg-deb -R`, which keeps the times the
// package will put on the disk, recorded by the same download through the window `checkDownload`
// makes, assembled by the image's own launcher (the image has no `java` for the one-step form),
// named in `kachok.cfg`, and packed back into the same file.
//
// **What proves it is the installed copy, not this directory.** The package is unpacked once more
// into a different place — `dpkg` keeps the times and the relocation is what the VM's path check
// allows — and its launcher's `--preflight` is run with `-Xlog:aot`: the build fails unless the VM
// says `Using AOT-linked classes: true` and no `aot` error, which is the line only a *mapped* cache
// prints (B-163). `distributions.yml` then installs the package for real and asks again.
//
// It needs a display, like `checkDownload`; `-Pkachok.aot=false` packages without the cache.
val debAot = providers.gradleProperty("kachok.aot").map { it != "false" }.getOrElse(true)
val debAotWork = layout.buildDirectory.dir("aot/deb")

val unpackReleaseDeb by tasks.registering(Exec::class) {
    description = "Unpacks the release .deb, keeping its files' times, for the AOT cache to be trained on."
    val linux = System.getProperty("os.name").orEmpty().let { !it.startsWith("Mac") && !it.startsWith("Windows") }
    val wanted = debAot
    onlyIf { linux && wanted }
    mustRunAfter(patchDesktopEntry)
    val debDir = layout.buildDirectory.dir("compose/binaries/main-release/deb")
    commandLine(
        "sh",
        "-c",
        """
        set -eu
        deb=${'$'}(find "${'$'}1" -maxdepth 1 -name '*.deb')
        [ "${'$'}(echo "${'$'}deb" | wc -l)" = "1" ] && [ -n "${'$'}deb" ] || { echo "expected one .deb in ${'$'}1"; exit 1; }
        rm -rf "${'$'}2" && mkdir -p "${'$'}2"
        dpkg-deb -R "${'$'}deb" "${'$'}2/root"
        """.trimIndent(),
        "sh",
        debDir.get().asFile.absolutePath,
        debAotWork.get().asFile.absolutePath,
    )
}

val recordDebAotConfiguration by tasks.registering(Test::class) {
    description = "Records what the packaged window loads while it downloads a torrent, from the .deb's own files."
    dependsOn(unpackReleaseDeb)
    val linux = System.getProperty("os.name").orEmpty().let { !it.startsWith("Mac") && !it.startsWith("Windows") }
    val wanted = debAot
    onlyIf { linux && wanted }
    testClassesDirs = desktopTests.get().testClassesDirs
    classpath = desktopTests.get().classpath
    filter { includeTestsMatching("*.DistributionDownloadTest") }
    val work = debAotWork.get().asFile
    systemProperty("kachok.launcher", File(work, "root/opt/kachok/bin/kachok").path)
    systemProperty(
        "kachok.jvmOptions",
        "-XX:AOTMode=record -XX:AOTConfiguration=${File(work, "kachok.aotconf").path}",
    )
    outputs.upToDateWhen { false }
}

val addAotCacheToDeb by tasks.registering(Exec::class) {
    description = "Assembles the AOT cache in the unpacked .deb, names it in kachok.cfg, repacks, and proves it maps."
    dependsOn(recordDebAotConfiguration)
    val linux = System.getProperty("os.name").orEmpty().let { !it.startsWith("Mac") && !it.startsWith("Windows") }
    val wanted = debAot
    onlyIf { linux && wanted }
    val debDir = layout.buildDirectory.dir("compose/binaries/main-release/deb")
    commandLine(
        "sh",
        "-c",
        """
        set -eu
        deb=${'$'}(find "${'$'}1" -maxdepth 1 -name '*.deb')
        work="${'$'}2"
        app="${'$'}work/root/opt/kachok/lib/app"
        [ -s "${'$'}work/kachok.aotconf" ] || { echo "the training run wrote no AOT configuration"; exit 1; }
        home=${'$'}(mktemp -d)
        trap 'rm -rf "${'$'}home"' EXIT
        # Assembled by the package's own launcher: the image has no `java`, and the cache must be the
        # one this runtime makes. In create mode the VM writes the cache and exits; no window opens.
        JAVA_TOOL_OPTIONS="-Duser.home=${'$'}home -XX:AOTMode=create -XX:AOTConfiguration=${'$'}work/kachok.aotconf -XX:AOTCache=${'$'}app/kachok.aot"             "${'$'}work/root/opt/kachok/bin/kachok" > "${'$'}work/create.log" 2>&1 || { cat "${'$'}work/create.log"; exit 1; }
        [ -s "${'$'}app/kachok.aot" ] || { cat "${'$'}work/create.log"; echo "no AOT cache was assembled"; exit 1; }
        grep -q 'AOTCache=' "${'$'}app/kachok.cfg" || sed -i '/^\[JavaOptions\]/a java-options=-XX:AOTCache=${'$'}APPDIR/kachok.aot' "${'$'}app/kachok.cfg"
        grep -q '^java-options=-XX:AOTCache=\${'$'}APPDIR/kachok.aot${'$'}' "${'$'}app/kachok.cfg" || { echo "kachok.cfg does not name the cache:"; cat "${'$'}app/kachok.cfg"; exit 1; }
        fakeroot dpkg-deb -b "${'$'}work/root" "${'$'}deb" > /dev/null
        # The installed copy: unpacked again somewhere else, the way dpkg would put it on a disk.
        rm -rf "${'$'}work/installed" && mkdir -p "${'$'}work/installed"
        dpkg-deb -x "${'$'}deb" "${'$'}work/installed"
        JAVA_TOOL_OPTIONS="-Duser.home=${'$'}home -Xlog:aot=info:file=${'$'}work/aot.log"             "${'$'}work/installed/opt/kachok/bin/kachok" --preflight "${'$'}work/preflight.txt" > /dev/null 2>&1 || true
        if grep -E '\[error *\]\[aot' "${'$'}work/aot.log" || ! grep -q 'Using AOT-linked classes: true' "${'$'}work/aot.log"; then
            echo "the packaged AOT cache does not map in an installed copy:"; cat "${'$'}work/aot.log"; exit 1
        fi
        echo "the .deb carries an AOT cache of ${'$'}(du -m "${'$'}app/kachok.aot" | cut -f1) MB, and an installed copy maps it"
        """.trimIndent(),
        "sh",
        debDir.get().asFile.absolutePath,
        debAotWork.get().asFile.absolutePath,
    )
}

tasks.matching { it.name == "packageReleaseDeb" }.configureEach { finalizedBy(addAotCacheToDeb) }

// **A package whose version cannot go up is a package that cannot be installed over the last one.**
//
// That is not a style rule: an `.msi` at an unchanged `ProductVersion` is the same product at the
// same version, and Windows repairs it instead of replacing the files — which is what "it did not
// update" was. So `builds` falling back to zero, which happens when there is no git, must not
// quietly produce a package that behaves that way. It is a *packaging* failure and not a build one:
// compiling in a source tarball with no history is fine, and `-Pkachok.builds=<n>` is how somebody
// who means to package from one says which build it is.
val versionCanGoUp by tasks.registering {
    // Captured into a local, so the action closes over a string rather than over the script — a
    // task that reaches back into the build file cannot be serialised into the configuration cache.
    val number = builds
    doFirst {
        check(number != "0") {
            "kachok cannot package version …$number: this tree has no git history to count commits " +
                "in, and a package whose version never changes cannot be installed over the one " +
                "before it. Pass -Pkachok.builds=<number> to say which build this is."
        }
    }
}

tasks.matching { it.name.startsWith("package") }.configureEach { dependsOn(versionCanGoUp) }

// **The window's tests run in the heap the window ships with.**
//
// The number was 128m, chosen because it is the engine's budget (research §1.2d) — and nothing ever
// ran the *UI* in it: Gradle gives a test JVM its own default, which is a gigabyte or more, so
// lowering the shipped heap was a change nothing could disagree with until somebody's window died.
// Now the two are one constant and 255 tests, including a real download driven through `Client`,
// are the evidence for whatever it says.
tasks.withType<Test>().configureEach {
    maxHeapSize = maxHeap
    jvmArgs("-XX:+UseG1GC", "-XX:+UseCompactObjectHeaders")
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
