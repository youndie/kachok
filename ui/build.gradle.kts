import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

            // **One version, from `gradle.properties`.** It was written out here as a literal and
            // was already a second place the number lives; `jpackage` will not take `-SNAPSHOT`,
            // which is the whole reason somebody typed it twice.
            packageVersion = project.version.toString().substringBefore("-")

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
                packageVersion = "1.0.0"
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
    val deb =
        layout.buildDirectory.file(
            "compose/binaries/main/deb/kachok_${project.version.toString().substringBefore("-")}_amd64.deb",
        )
    commandLine(
        "sh",
        "-c",
        """
        set -eu
        deb="${'$'}1"
        [ -f "${'$'}deb" ] || { echo "no package at ${'$'}deb"; exit 1; }
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
        deb.get().asFile.absolutePath,
    )
}

tasks.matching { it.name == "packageDeb" }.configureEach { finalizedBy(patchDesktopEntry) }

// **The one thing that runs what `createDistributable` produced.**
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
                "compose/binaries/main/app/kachok.app/Contents/MacOS/kachok"
            }

            System.getProperty("os.name").orEmpty().startsWith("Windows") -> {
                "compose/binaries/main/app/kachok/kachok.exe"
            }

            else -> {
                "compose/binaries/main/app/kachok/bin/kachok"
            }
        },
    )

val checkDistributable by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the packaged application's own launcher against its trimmed runtime."
    dependsOn(tasks.named("createDistributable"))
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
