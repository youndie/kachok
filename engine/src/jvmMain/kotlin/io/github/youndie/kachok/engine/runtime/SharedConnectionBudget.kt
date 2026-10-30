package io.github.youndie.kachok.engine.runtime

import io.github.youndie.kachok.engine.peer.ConnectionBudget

/**
 * The set's [ConnectionBudget]: two counters behind one lock, spent by every session at once (B-152).
 *
 * Zero for either cap is no cap. Lowering one below what is held takes nothing away by itself — the
 * set's share of connections per torrent is what closes the excess — it only stops new ones until
 * the count is under the cap again.
 */
public class SharedConnectionBudget(
    maxConnections: Int,
    maxDials: Int,
) : ConnectionBudget {
    @Volatile
    public var maxConnections: Int = maxConnections
        private set

    @Volatile
    public var maxDials: Int = maxDials
        private set

    /** Connections held, dials in flight included. */
    @Volatile
    public var connections: Int = 0
        private set

    @Volatile
    public var dialling: Int = 0
        private set

    @Synchronized
    override fun tryDial(): Boolean {
        if (maxDials in 1..dialling) return false
        if (maxConnections in 1..connections) return false
        dialling++
        connections++
        return true
    }

    @Synchronized
    override fun dialEnded(connected: Boolean) {
        dialling = (dialling - 1).coerceAtLeast(0)
        if (!connected) connections = (connections - 1).coerceAtLeast(0)
    }

    @Synchronized
    override fun tryAccept(): Boolean {
        if (maxConnections in 1..connections) return false
        connections++
        return true
    }

    @Synchronized
    override fun closed() {
        connections = (connections - 1).coerceAtLeast(0)
    }

    @Synchronized
    public fun retune(
        maxConnections: Int,
        maxDials: Int,
    ) {
        this.maxConnections = maxConnections
        this.maxDials = maxDials
    }
}
