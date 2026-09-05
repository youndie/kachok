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
}

application {
    mainClass.set("ru.workinprogress.kachok.cli.MainKt")
    // The JVM flags the research settled on. They are here and not in a launcher script so that
    // `./gradlew :cli:run` measures the same VM a distribution would run.
    applicationDefaultJvmArgs =
        listOf(
            "-XX:+UseCompactObjectHeaders",
            "-Xmx256m",
        )
}
