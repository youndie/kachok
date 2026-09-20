plugins {
    alias(wip.plugins.kotlinJvm)
    id("io.github.youndie.sborka.base")
    id("io.github.youndie.sborka.test")
    id("io.github.youndie.sborka.lint")
}

// A local swarm, for tests.
//
// Not published and not shipped: nothing in `:engine`, `:cli` or `:ui` depends on it outside a test
// source set. It exists because two surfaces now need the same end-to-end — a tracker that names a
// peer, a peer that serves the bytes — and a fake BitTorrent seed written twice is two fakes that
// disagree about the protocol in different places.
// The comparison harness of B-125, and NOT part of `build` — the same decision `:cli`'s
// `collectorBench` records: it takes minutes, it measures this machine as much as this code, and a
// number produced on a shared CI runner would be worse than no number. What `build` runs is
// `Measure.verify`, which returns no timing to print.
//
//   ./gradlew :swarm:measure -Pscenario=picker-order -Pruns=4
tasks.register<JavaExec>("measure") {
    group = "verification"
    description = "Runs one scenario's two variants interleaved and reports the ratio between them"
    mainClass.set("io.github.youndie.kachok.swarm.measure.MeasureMain")
    classpath = sourceSets["main"].runtimeClasspath
    args =
        listOfNotNull(
            (project.findProperty("scenario") as String?)?.let { listOf("--scenario", it) },
            (project.findProperty("runs") as String?)?.let { listOf("--runs", it) },
            (project.findProperty("scales") as String?)?.let { listOf("--scales", it) },
        ).flatten()
    // A sixfold stand holds sixty mebibytes of payload in the seed and four clients' worth of
    // buffers beside it; the default heap is not the measurement's to be limited by.
    maxHeapSize = "2g"
}

dependencies {
    api(projects.engine)
    implementation(wip.kotlinx.coroutines.core)
    // The harness got knobs of its own — a seed that holds part of the torrent, a seed with a rate
    // — and a harness that lies is worse than no harness: every suite above it would go green on a
    // stand that was not posing the question it claims to pose (B-123).
    testImplementation(kotlin("test"))
    testImplementation(wip.kotlinx.coroutines.test)
}
