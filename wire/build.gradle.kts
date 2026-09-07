plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

kotlin {
    // JVM now, because the backend is a JVM process and so is every test. `wasmJs` joins it when
    // the UI does (B-80); nothing here is JVM-specific, which is the point of the module existing
    // separately from `:engine` — the engine has sockets in it and a browser has none.
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(wip.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(wip.kotlinx.serialization.json)
        }
    }
}
