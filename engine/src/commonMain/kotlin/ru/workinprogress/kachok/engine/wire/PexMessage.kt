package ru.workinprogress.kachok.engine.wire

import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.bencode.BencodeException
import ru.workinprogress.kachok.engine.peer.CompactPeers
import ru.workinprogress.kachok.engine.peer.PeerAddress

/**
 * BEP 11's `ut_pex` payload: who this peer has met since it last said, and who it has lost.
 *
 * A **delta**, not a list — which is the one thing about it that is easy to get wrong, because a
 * message that repeats the whole swarm every minute is still well formed and still parses. Each
 * side keeps what it last told the other and sends the difference.
 *
 * IPv6 travels in `added6` / `dropped6`, which this **reads and does not write** (BEP 7, B-38):
 * a peer learned that way is dialled like any other, and this client advertises only the peers it
 * can pack into the IPv4 form. Sending `added6` needs this client to know its own IPv6 reachability,
 * which is a different question from being able to read somebody else's answer to it.
 */
public class PexMessage(
    public val added: List<PeerAddress> = emptyList(),
    public val dropped: List<PeerAddress> = emptyList(),
    /** One byte per added peer, in the same order. `0x02` means the peer is a seed. */
    public val addedFlags: List<Int> = emptyList(),
) {
    public val isEmpty: Boolean get() = added.isEmpty() && dropped.isEmpty()

    public fun encode(): ByteArray {
        val fields = LinkedHashMap<BString, ru.workinprogress.kachok.engine.bencode.BValue>()
        fields[BString("added")] = BString(CompactPeers.encode(added))
        if (addedFlags.isNotEmpty()) {
            fields[BString("added.f")] = BString(ByteArray(addedFlags.size) { addedFlags[it].toByte() })
        }
        fields[BString("dropped")] = BString(CompactPeers.encode(dropped))
        return Bencode.encode(BDictionary(fields))
    }

    override fun toString(): String = "PexMessage(+${added.size}, -${dropped.size})"

    public companion object {
        /** BEP 11: at most one a minute, and no more peers than this in one message. */
        public const val MAX_PER_MESSAGE: Int = 50

        /** `added.f`: this peer is a seed. */
        public const val FLAG_SEED: Int = 0x02

        /**
         * Reads a payload, refusing only what is not a dictionary.
         *
         * Every key is optional and a missing one means an empty list, because a peer with nothing
         * to add and something to drop sends only `dropped` — and a client that treated an absent
         * key as malformed would drop the connection over the commonest message there is.
         */
        public fun decode(payload: ByteArray): PexMessage {
            val root =
                try {
                    Bencode.decode(payload) as? BDictionary
                        ?: throw WireException("a ut_pex message is a dictionary")
                } catch (malformed: BencodeException) {
                    throw WireException("a ut_pex message is not bencode: ${malformed.message}")
                }
            val added = peers(root["added"]) + peers6(root["added6"])
            val flags = ((root["added.f"] as? BString)?.bytes ?: ByteArray(0)).map { it.toInt() and 0xFF }
            return PexMessage(
                added = added,
                dropped = peers(root["dropped"]) + peers6(root["dropped6"]),
                // Trimmed to the peers it describes: a sender that disagrees with itself about how
                // many it added should not make the flags of the peer after it mean something else.
                addedFlags = flags.take(added.size),
            )
        }

        private fun peers(value: Any?): List<PeerAddress> =
            (value as? BString)?.let { CompactPeers.decode(it.bytes) } ?: emptyList()

        private fun peers6(value: Any?): List<PeerAddress> =
            (value as? BString)?.let { CompactPeers.decode6(it.bytes) } ?: emptyList()
    }
}
