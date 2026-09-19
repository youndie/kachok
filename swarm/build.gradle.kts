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
dependencies {
    api(projects.engine)
    implementation(wip.kotlinx.coroutines.core)
    // The harness got knobs of its own — a seed that holds part of the torrent, a seed with a rate
    // — and a harness that lies is worse than no harness: every suite above it would go green on a
    // stand that was not posing the question it claims to pose (B-123).
    testImplementation(kotlin("test"))
    testImplementation(wip.kotlinx.coroutines.test)
}
