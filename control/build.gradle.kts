plugins {
    alias(wip.plugins.kotlinJvm)
    alias(wip.plugins.kotlinSerialization)
    id("io.github.youndie.sborka.base")
    id("io.github.youndie.sborka.test")
    id("io.github.youndie.sborka.lint")
}

// How something outside this process reaches the client running inside it (B-117).
//
// Two things live here, and they are one thing from a distance: the loopback socket that makes a
// second launch hand its torrent to the first, and the MCP server an agent drives. They moved out
// of the surfaces because after B-117 each surface needs both — the window has to answer MCP, and
// the headless process has to speak the lock's handshake — and `:ui` may not depend on `:cli`
// (docs/services/cli.md §1: the UI must be able to replace that module).
//
// `api` and not `implementation` for the two below: `McpServer` takes a `TorrentSet` and hands back
// `:wire`'s `Snapshot`, so a module that uses this one is holding those types already.
dependencies {
    api(projects.engine)
    api(projects.wire)
    implementation(wip.kotlinx.serialization.json)
    implementation(wip.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    // The same tracker and seeding peer both surfaces are tested against: the MCP tools are
    // asserted against a real download rather than a mock of one.
    testImplementation(projects.swarm)
    testImplementation(wip.kotlinx.coroutines.test)
}
