import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

kotlin {
    // Phase 1: JVM only. The logic lives in commonMain from the start so that the Android, iOS and
    // wasmJs targets of later phases add `actual`s, not rewrites — but a target is declared only
    // when there is an implementation behind it, because a target with no `actual` is a build that
    // fails, and a target with a stub `actual` is a build that lies.
    jvm {
        compilerOptions {
            // New code, no Kotlin-1.x consumers: default methods in interfaces, no DefaultImpls.
            // `-jvm-default` is the stable form of the old `-Xjvm-default=all` (Kotlin 2.2.0).
            jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
            // Release builds drop the null checks on parameters and on calls into Java. The engine
            // talks to the JDK through ByteBuffer and channels, where those checks are pure cost.
            if (providers.gradleProperty("kachok.release").isPresent) {
                freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(wip.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(wip.kotlinx.coroutines.test)
        }
    }
}

// The upload read path comparison of B-30. Not part of `build`: it takes minutes and it measures
// this machine's page cache as much as this code.
tasks.register<JavaExec>("uploadPathBench") {
    group = "verification"
    description = "Serves a file to local peers with transferTo and with a mapped segment, and compares them"
    mainClass.set("ru.workinprogress.kachok.engine.storage.UploadPathBench")
    val testCompilation =
        kotlin.targets
            .getByName("jvm")
            .compilations
            .getByName("test")
    classpath = files(testCompilation.runtimeDependencyFiles, testCompilation.output.allOutputs)
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(java.toolchain.languageVersion.get())
        },
    )
    args = (project.findProperty("benchArgs") as String?)?.split(" ") ?: emptyList()
}

// B-44's probe: what ends a write that is already blocked. Not part of `build` — it deliberately
// wedges sockets and then waits on them.
tasks.register<JavaExec>("blockedWriteProbe") {
    group = "verification"
    description = "Blocks writers on an unread socket and reports what stops them"
    mainClass.set("ru.workinprogress.kachok.engine.io.BlockedWriteProbe")
    val testCompilation =
        kotlin.targets
            .getByName("jvm")
            .compilations
            .getByName("test")
    classpath = files(testCompilation.runtimeDependencyFiles, testCompilation.output.allOutputs)
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(java.toolchain.languageVersion.get())
        },
    )
}

// Prints the probe's class path, so the same classes can be run under another kernel:
// `docker run … java -cp "$(./gradlew -q :engine:probeClasspath)" …`.
tasks.register("probeClasspath") {
    val testCompilation =
        kotlin.targets
            .getByName("jvm")
            .compilations
            .getByName("test")
    val entries = files(testCompilation.runtimeDependencyFiles, testCompilation.output.allOutputs)
    doLast { println(entries.joinToString(":")) }
}
