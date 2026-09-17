package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.io.EngineDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * B-103: asking the router must not be something a person waits through.
 *
 * On a router that does not answer NAT-PMP — which is the one on the network this was written on,
 * measured — the mapper spends two attempts of two seconds before giving up. Done on the way to
 * opening a set, that is four seconds of a client that has not yet dialled anybody, every start.
 * So it is launched, and this is the test that says so.
 */
class PortMappingStartupTest {
    private val dispatchers = EngineDispatchers()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var set: TorrentSet? = null

    @AfterTest
    fun clean() {
        set?.close()
        scope.cancel()
        dispatchers.close()
    }

    @Test
    fun openingASetDoesNotWaitForTheRouter() {
        val started = System.nanoTime()
        val open = TorrentSet(dispatchers = dispatchers, scope = scope, options = SetOptions(port = 0))
        set = open
        val elapsed = (System.nanoTime() - started) / 1_000_000

        // The mapper's own budget is two attempts of two seconds. Anything near that here means
        // the ask is on the opening path rather than beside it.
        assertTrue(
            elapsed < 1_000,
            "opening the set took ${elapsed}ms; the router ask is being waited on",
        )
        // And it reports something rather than nothing, whichever way the ask goes: a status line
        // that is empty until a router replies is one nobody can read while it matters.
        assertTrue(open.portMapping.isNotBlank(), "the set says nothing about its port mapping")
    }

    /**
     * Closing a set that never mapped anything must not sit on the router either.
     *
     * The release is best effort and it is on the shutdown path, where a person is waiting for a
     * window to go away.
     */
    @Test
    fun closingASetThatMappedNothingIsImmediate() {
        val open = TorrentSet(dispatchers = dispatchers, scope = scope, options = SetOptions(port = 0))
        val started = System.nanoTime()
        open.close()
        set = null
        val elapsed = (System.nanoTime() - started) / 1_000_000

        assertTrue(elapsed < 1_000, "closing took ${elapsed}ms; the release is being waited on")
    }
}
