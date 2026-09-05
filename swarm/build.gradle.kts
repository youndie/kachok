plugins {
    alias(libs.plugins.kotlinJvm)
    id("ru.workinprogress.sborka.base")
    id("ru.workinprogress.sborka.lint")
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
}
