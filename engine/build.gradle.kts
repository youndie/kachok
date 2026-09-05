import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
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
