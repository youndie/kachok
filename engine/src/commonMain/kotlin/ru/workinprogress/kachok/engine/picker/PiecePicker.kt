package ru.workinprogress.kachok.engine.picker

import ru.workinprogress.kachok.engine.PieceIndex
import ru.workinprogress.kachok.engine.metainfo.Metainfo
import ru.workinprogress.kachok.engine.peer.PeerAddress
import ru.workinprogress.kachok.engine.wire.PeerWire
import kotlin.random.Random

/** A request that was never answered, freed for somebody else. */
public class ExpiredRequest(
    public val peer: PeerAddress,
    public val piece: PieceIndex,
    public val block: Int,
)

/** One block to ask a peer for. */
public class BlockRequest(
    public val piece: PieceIndex,
    public val begin: Int,
    public val length: Int,
) {
    override fun toString(): String = "Request(${piece.value}, $begin, $length)"
}

/**
 * Which block to ask which peer for.
 *
 * Three rules, in this order, and each of them is doing a different job:
 *
 * 1. **Strict priority for started pieces.** A piece in flight holds `pieceLength / 16 KiB` pooled
 *    buffers, so finishing one frees memory as much as it makes progress. BEP 3 asks for this and
 *    the buffer pool insists on it.
 * 2. **Rarest first.** A piece only one peer has is the piece the swarm is about to lose. Ties go
 *    to the lowest index, except for the very first piece of a torrent, which is chosen at random:
 *    every client starting at piece 0 makes piece 0 the only piece anyone has.
 * 3. **Endgame.** When every block is either had or already asked for, ask several peers for the
 *    stragglers and cancel the losers. Without it a download ends at the speed of its slowest peer.
 *
 * The number of pieces started at once is bounded ([maxStartedPieces]) because that bound *is* the
 * memory the download uses.
 *
 * This class is pure: it decides, it does not send. The session sends, and tells it what happened.
 */
public class PiecePicker(
    private val metainfo: Metainfo,
    private val maxStartedPieces: Int = DEFAULT_MAX_STARTED,
    private val random: Random = Random.Default,
) {
    private val have = Bitfield(metainfo.pieceCount)
    private val availability = IntArray(metainfo.pieceCount)
    private val peers = HashMap<PeerAddress, Bitfield>()
    private val started = LinkedHashMap<Int, PieceProgress>()

    /**
     * The same information as `started.keys`, as bits.
     *
     * Because `index in started` on a `Map<Int, _>` boxes the index, and the scan below asks it
     * once per piece per request. Boxing is what the profile of the Debian download found at the
     * top of the allocation samples (research §1.2c) — `java.lang.Integer`, from here and from the
     * candidate list this scan used to build.
     */
    private val isStarted = BooleanArray(metainfo.pieceCount)

    /** When the caller says it is. The picker has no clock of its own and wants none. */
    private var now: Long = 0L

    /** Pieces this client has verified. */
    public val completed: Bitfield get() = have

    public val isComplete: Boolean get() = have.isComplete

    /**
     * True when there is work outstanding and nothing left to ask for a first time; the picker
     * then allows the same block to be asked of several peers.
     *
     * **Availability is part of the condition**, and leaving it out was the first version's bug: a
     * piece no connected peer has can never be requested, so a swarm that holds two pieces out of
     * ten would never have reached endgame at all. What endgame waits for is the last *reachable*
     * blocks, not the last blocks.
     */
    public val isEndgame: Boolean
        get() =
            started.values.any { it.hasMissing() } &&
                (0 until metainfo.pieceCount).none { index ->
                    !have[index] && availability[index] > 0 && (started[index]?.hasUnrequested() ?: true)
                }

    public fun addPeer(peer: PeerAddress) {
        peers.getOrPut(peer) { Bitfield(metainfo.pieceCount) }
    }

    public fun removePeer(peer: PeerAddress) {
        val bitfield = peers.remove(peer) ?: return
        (0 until metainfo.pieceCount).forEach { if (bitfield[it]) availability[it]-- }
        started.values.forEach { it.forget(peer) }
    }

    /** A `bitfield` message: the peer's whole set, replacing whatever was assumed before. */
    public fun setBitfield(
        peer: PeerAddress,
        bits: ByteArray,
    ) {
        val bitfield = Bitfield.fromBytes(bits, metainfo.pieceCount)
        removePeer(peer)
        peers[peer] = bitfield
        (0 until metainfo.pieceCount).forEach { if (bitfield[it]) availability[it]++ }
    }

    /** A `have` message. */
    public fun addHave(
        peer: PeerAddress,
        piece: PieceIndex,
    ) {
        val bitfield = peers.getOrPut(peer) { Bitfield(metainfo.pieceCount) }
        if (bitfield[piece.value]) return
        bitfield.set(piece.value)
        availability[piece.value]++
    }

    /** How many peers have this piece. Zero means nobody connected can serve it. */
    public fun availabilityOf(piece: PieceIndex): Int = availability[piece.value]

    /** Whether this peer claims the whole torrent — BEP 11's `added.f` seed flag. */
    public fun isSeed(peer: PeerAddress): Boolean = peers[peer]?.isComplete == true

    /** True if there is anything this peer could give us. Drives `interested`/`not interested`. */
    public fun isInteresting(peer: PeerAddress): Boolean {
        val bitfield = peers[peer] ?: return false
        return (0 until metainfo.pieceCount).any { bitfield[it] && !have[it] }
    }

    /**
     * Up to [count] blocks to ask [peer] for right now, marked as asked.
     *
     * Returns fewer — or none — when the peer has nothing wanted, when the started-piece bound is
     * reached and this peer can contribute to none of them, or when everything is already asked
     * for and the download is not yet in endgame.
     */
    public fun next(
        peer: PeerAddress,
        count: Int,
        nowMillis: Long = 0L,
    ): List<BlockRequest> {
        if (count <= 0) return emptyList()
        this.now = nowMillis
        val bitfield = peers[peer] ?: return emptyList()
        val requests = ArrayList<BlockRequest>(count)

        fillFromStarted(peer, bitfield, requests, count)
        while (requests.size < count && started.size < maxStartedPieces) {
            val index = rarestUnstarted(bitfield) ?: break
            begin(index)
            fillFrom(peer, index, requests, count)
        }
        if (requests.size < count && isEndgame) fillFromEndgame(peer, bitfield, requests, count)
        return requests
    }

    /**
     * Blocks of one named piece, whatever the ordering rules would have chosen.
     *
     * For BEP 6's `allowed fast`, which is the one case where *which* piece is not this class's
     * decision: the peer named it, and the alternative to asking for it is asking for nothing at
     * all, because the peer is choking us. Everything else — rarest first, strict priority,
     * endgame — is untouched and stays [next]'s business.
     */
    public fun nextFrom(
        peer: PeerAddress,
        piece: PieceIndex,
        count: Int,
        nowMillis: Long = 0L,
    ): List<BlockRequest> {
        if (count <= 0 || have[piece.value]) return emptyList()
        val bitfield = peers[peer] ?: return emptyList()
        if (!bitfield[piece.value]) return emptyList()
        this.now = nowMillis
        val requests = ArrayList<BlockRequest>(count)
        // A piece begun this way counts against the same bound as any other: the blocks it holds
        // are the same pooled buffers.
        if (piece.value !in started) {
            if (started.size >= maxStartedPieces) return emptyList()
            begin(piece.value)
        }
        fillFrom(peer, piece.value, requests, count)
        return requests
    }

    /**
     * A block arrived. Returns the **other** peers it was asked of, so the session can cancel it
     * with them — the endgame's other half.
     */
    public fun blockReceived(
        from: PeerAddress,
        piece: PieceIndex,
        begin: Int,
    ): List<PeerAddress> {
        val progress = started[piece.value] ?: return emptyList()
        return progress.received(begin / PeerWire.BLOCK_SIZE, from)
    }

    /**
     * Frees every block asked before [beforeMillis] and says whom it was asked of.
     *
     * **Without this a real download stops.** A peer that takes a request and answers nothing —
     * because it went away without closing, or is snubbing us — holds that block for ever, and
     * once every block of every started piece is held that way the picker has nothing to give
     * anyone and no new piece may begin. Measured against the Debian swarm in B-19: the download
     * stalled at 960 pieces of 3020 with twenty-five connections all waiting on requests nobody
     * was going to answer.
     */
    public fun expireRequests(beforeMillis: Long): List<ExpiredRequest> {
        val expired = mutableListOf<ExpiredRequest>()
        started.forEach { (index, progress) -> progress.expire(beforeMillis, index, expired) }
        return expired
    }

    /**
     * BEP 6: this peer said it will not answer one particular request, so the block is free now.
     *
     * The difference from [expireRequests] is thirty seconds. Without the fast extension the only
     * way to learn that a request died is to wait out the timeout, and a choke kills every
     * outstanding request at once; with it the picker is told, one block at a time, and can give
     * that block to somebody else on the next pass.
     */
    public fun requestRejected(
        peer: PeerAddress,
        piece: PieceIndex,
        begin: Int,
    ) {
        started[piece.value]?.forgetBlock(peer, begin / PeerWire.BLOCK_SIZE)
    }

    /** A peer choked us or went away: its outstanding requests are gone and may be asked again. */
    public fun requestsDropped(peer: PeerAddress) {
        started.values.forEach { it.forget(peer) }
    }

    /**
     * Throw away every piece this picker thinks it has, and every piece it has begun.
     *
     * For a re-check, which then [restore]s the answer the disk gave. The peer table and the
     * availability counts are deliberately kept: which peers hold what has not changed because this
     * client checked its own files, and the caller is responsible for having closed the connections
     * first — a `forget` with requests still in flight would hand out blocks somebody is already
     * sending.
     */
    public fun forget() {
        started.clear()
        isStarted.fill(false)
        have.clear()
    }

    /**
     * Seeds the picker with what a start-up check found on the disk.
     *
     * Only before anything else happens, or straight after a [forget]: a picker that has already
     * handed out requests would be told it has pieces those requests are for.
     */
    public fun restore(verified: Bitfield) {
        require(verified.size == metainfo.pieceCount) {
            "a bitfield for ${verified.size} pieces cannot restore a torrent of ${metainfo.pieceCount}"
        }
        check(started.isEmpty() && have.cardinality == 0) { "the picker is already in use" }
        (0 until metainfo.pieceCount).forEach { if (verified[it]) have.set(it) }
    }

    /** The writer verified a piece. */
    public fun pieceVerified(piece: PieceIndex) {
        finish(piece.value)
        have.set(piece.value)
    }

    /** The writer found a bad hash: every block of the piece has to come again. */
    public fun pieceFailed(piece: PieceIndex) {
        finish(piece.value)
    }

    /**
     * The two places a piece enters or leaves [started], and the only two.
     *
     * `isStarted` is a second copy of the map's key set and would be worth nothing if it could
     * drift from it; keeping both mutations behind one pair of calls is what stops that.
     */
    private fun begin(index: Int) {
        started[index] = PieceProgress(blockCount(index))
        isStarted[index] = true
    }

    private fun finish(index: Int) {
        started.remove(index)
        isStarted[index] = false
    }

    private fun fillFromStarted(
        peer: PeerAddress,
        bitfield: Bitfield,
        into: MutableList<BlockRequest>,
        count: Int,
    ) {
        started.keys.toList().forEach { index ->
            if (into.size >= count) return
            if (bitfield[index] && !have[index]) fillFrom(peer, index, into, count)
        }
    }

    private fun fillFrom(
        peer: PeerAddress,
        index: Int,
        into: MutableList<BlockRequest>,
        count: Int,
    ) {
        val progress = started[index] ?: return
        while (into.size < count) {
            val block = progress.takeUnrequested(peer, now) ?: return
            into += request(index, block)
        }
    }

    private fun fillFromEndgame(
        peer: PeerAddress,
        bitfield: Bitfield,
        into: MutableList<BlockRequest>,
        count: Int,
    ) {
        started.forEach { (index, progress) ->
            if (into.size >= count) return
            if (!bitfield[index] || have[index]) return@forEach
            while (into.size < count) {
                val block = progress.takeForEndgame(peer, now) ?: break
                into += request(index, block)
            }
        }
    }

    private fun request(
        index: Int,
        block: Int,
    ): BlockRequest {
        val begin = block * PeerWire.BLOCK_SIZE
        val pieceLength = metainfo.pieceLengthAt(PieceIndex(index))
        return BlockRequest(PieceIndex(index), begin, minOf(PeerWire.BLOCK_SIZE, pieceLength - begin))
    }

    private fun blockCount(index: Int): Int {
        val pieceLength = metainfo.pieceLengthAt(PieceIndex(index))
        return (pieceLength + PeerWire.BLOCK_SIZE - 1) / PeerWire.BLOCK_SIZE
    }

    /**
     * The rarest piece this peer has that is neither had nor started, or — for the first piece of
     * the torrent — one of its pieces at random. In one pass, and without allocating.
     *
     * It used to build a `List<Int>` of every candidate and take the minimum of it, which is a
     * boxed integer per piece per request — nothing measurable on the 3 020-piece torrent the
     * profile ran on, and a hundred thousand of them per decision on a large one (B-43). The rules
     * are unchanged: rarest first, ties to the lowest index, and the very first piece of a torrent
     * chosen at random.
     *
     * The random case is a reservoir sample rather than a second pass: keeping the *n*-th
     * candidate with probability 1/n leaves every candidate equally likely, which is what the
     * list-and-index version did with a list.
     */
    private fun rarestUnstarted(bitfield: Bitfield): Int? {
        val chooseAtRandom = have.cardinality == 0 && started.isEmpty()
        var best = -1
        var rarest = Int.MAX_VALUE
        var seen = 0
        for (index in 0 until metainfo.pieceCount) {
            if (!bitfield[index] || have[index] || isStarted[index]) continue
            seen++
            if (chooseAtRandom) {
                if (random.nextInt(seen) == 0) best = index
                continue
            }
            val availableFrom = availability[index]
            if (availableFrom < rarest) {
                rarest = availableFrom
                best = index
            }
        }
        return if (best < 0) null else best
    }

    /** Which blocks of one started piece have been asked of whom. */
    private class PieceProgress(
        val blocks: Int,
    ) {
        /** Per block: which peers were asked, and when. The time is what makes expiry possible. */
        private val askedOf = arrayOfNulls<MutableMap<PeerAddress, Long>>(blocks)
        private val received = BooleanArray(blocks)

        fun hasUnrequested(): Boolean = (0 until blocks).any { !received[it] && askedOf[it].isNullOrEmpty() }

        fun hasMissing(): Boolean = (0 until blocks).any { !received[it] }

        /** The first block nobody has been asked for, now asked of [peer]. */
        fun takeUnrequested(
            peer: PeerAddress,
            at: Long,
        ): Int? {
            val block =
                (0 until blocks).firstOrNull { !received[it] && askedOf[it].isNullOrEmpty() }
                    ?: return null
            askedOf[block] = mutableMapOf(peer to at)
            return block
        }

        /** A block still missing that this peer has not already been asked for. */
        fun takeForEndgame(
            peer: PeerAddress,
            at: Long,
        ): Int? {
            val block =
                (0 until blocks).firstOrNull { !received[it] && peer !in (askedOf[it] ?: emptyMap()) }
                    ?: return null
            val asked = askedOf[block] ?: mutableMapOf<PeerAddress, Long>().also { askedOf[block] = it }
            asked[peer] = at
            return block
        }

        /** Marks a block arrived and reports the other peers it was asked of. */
        fun received(
            block: Int,
            from: PeerAddress,
        ): List<PeerAddress> {
            if (block !in 0 until blocks || received[block]) return emptyList()
            received[block] = true
            val others = askedOf[block].orEmpty().keys.filter { it != from }
            askedOf[block] = null
            return others
        }

        /** One block un-asked, rather than all of this peer's. */
        fun forgetBlock(
            peer: PeerAddress,
            block: Int,
        ) {
            if (block !in 0 until blocks) return
            val asked = askedOf[block] ?: return
            asked.remove(peer)
            if (asked.isEmpty()) askedOf[block] = null
        }

        fun forget(peer: PeerAddress) {
            (0 until blocks).forEach { block ->
                val asked = askedOf[block] ?: return@forEach
                asked.remove(peer)
                if (asked.isEmpty()) askedOf[block] = null
            }
        }

        /** Frees every block asked before [before] and reports who was asked and never delivered. */
        fun expire(
            before: Long,
            index: Int,
            into: MutableList<ExpiredRequest>,
        ) {
            (0 until blocks).forEach { block ->
                val asked = askedOf[block] ?: return@forEach
                asked.entries
                    .filter { it.value < before }
                    .forEach { (peer, _) ->
                        asked.remove(peer)
                        into += ExpiredRequest(peer, PieceIndex(index), block)
                    }
                if (asked.isEmpty()) askedOf[block] = null
            }
        }
    }

    public companion object {
        /**
         * Started pieces at once. Each one holds its blocks in pooled buffers until it completes,
         * so this number times `pieceLength / 16 KiB` is the pool's working set. The default is a
         * placeholder until [B-26] measures it.
         */
        public const val DEFAULT_MAX_STARTED: Int = 8
    }
}
