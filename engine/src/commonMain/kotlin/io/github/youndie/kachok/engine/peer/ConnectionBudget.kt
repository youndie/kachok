package io.github.youndie.kachok.engine.peer

/**
 * Connections and dials the whole client may have at once, shared by every torrent
 * ([B-152](../../../../../../../../docs/backlog/B-152-one-cap-on-connections-for-the-whole-client.md)).
 *
 * Two numbers, because they cost a router two different things. A connection is a socket held for
 * as long as the peer is useful. A dial in flight is a half-open entry in the router's NAT table for
 * up to the connect timeout, and on a public swarm most of them never answer — so a torrent short of
 * peers that dials its whole deficit at once is a burst of hundreds of those, every time.
 *
 * A dial holds a connection slot from the moment it starts: a dial that succeeds is a connection,
 * and counting it only then would let a burst of dials overshoot the cap by however many answer.
 */
public interface ConnectionBudget {
    /** Takes a dial slot and a connection slot if both are free, and says whether it did. */
    public fun tryDial(): Boolean

    /** A dial [tryDial] allowed has ended: its dial slot goes back, and its connection slot too unless it [connected]. */
    public fun dialEnded(connected: Boolean)

    /** Takes a connection slot for a peer that dialled us, if one is free. */
    public fun tryAccept(): Boolean

    /** A connection that held a slot has closed. */
    public fun closed()
}
