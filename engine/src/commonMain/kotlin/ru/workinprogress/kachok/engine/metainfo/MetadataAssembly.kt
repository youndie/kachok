package ru.workinprogress.kachok.engine.metainfo

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode
import ru.workinprogress.kachok.engine.bencode.BencodeException
import ru.workinprogress.kachok.engine.platform.sha1
import ru.workinprogress.kachok.engine.wire.MetadataMessage

/**
 * The info dictionary, arriving from strangers 16 KiB at a time (BEP 9).
 *
 * **The info hash is the only thing that makes this safe.** Everything here comes from peers who
 * were asked for it by an identifier and have every opportunity to answer with something else; a
 * client that assembled the blocks and parsed them would be parsing whatever it was given. So the
 * bytes are hashed *before* they are looked at, and a mismatch throws the whole assembly away
 * rather than any part of it — there is no way to tell which peer sent the bad block, and keeping
 * the ones that hashed correctly is not a thing a single SHA-1 over the whole can say.
 */
public class MetadataAssembly(
    public val infoHash: InfoHash,
    public val totalSize: Int,
) {
    init {
        require(totalSize > 0) { "metadata of $totalSize bytes cannot be assembled" }
    }

    public val blockCount: Int = MetadataMessage.blockCount(totalSize)

    private val bytes = ByteArray(totalSize)
    private val have = BooleanArray(blockCount)

    public val isComplete: Boolean get() = have.all { it }

    public val received: Int get() = have.count { it }

    /** The blocks nobody has been asked for yet, or that were asked for and refused. */
    public fun missing(): List<Int> = (0 until blockCount).filter { !have[it] }

    /**
     * Takes one block, and says whether it was one this assembly wanted.
     *
     * A block of the wrong length for its index is refused rather than padded: BEP 9 fixes every
     * block at 16 KiB except the last, so a short one anywhere else is a peer that is confused or
     * trying something.
     */
    public fun accept(
        piece: Int,
        block: ByteArray,
    ): Boolean {
        if (piece !in 0 until blockCount || have[piece]) return false
        val from = piece * MetadataMessage.BLOCK_SIZE
        val expected = minOf(MetadataMessage.BLOCK_SIZE, totalSize - from)
        if (block.size != expected) return false
        block.copyInto(bytes, from)
        have[piece] = true
        return true
    }

    /**
     * Hashes what arrived and parses it, or throws.
     *
     * The parse goes through the same [MetainfoParser] a `.torrent` file does, on a dictionary
     * built here — so a magnet download and a file download differ in where the bytes came from
     * and in nothing after that.
     */
    public fun finish(
        trackers: List<String>,
        displayName: String?,
    ): Metainfo {
        check(isComplete) { "the metadata is $received of $blockCount blocks" }
        val digest = sha1(bytes, 0, bytes.size)
        if (!digest.contentEquals(infoHash.bytes)) {
            throw MetainfoException(
                "the metadata's SHA-1 is not the info hash the magnet link asked for; " +
                    "$totalSize bytes were discarded",
            )
        }
        val info =
            try {
                Bencode.decode(bytes) as? BDictionary
                    ?: throw MetainfoException("the metadata is not a dictionary")
            } catch (malformed: BencodeException) {
                // Unreachable through a correct hash — the bytes are the ones the hash names — and
                // kept because "unreachable" is a claim about somebody else's SHA-1.
                throw MetainfoException("the metadata is not bencode: ${malformed.message}")
            }
        val fields = LinkedHashMap<BString, ru.workinprogress.kachok.engine.bencode.BValue>()
        if (trackers.isNotEmpty()) fields[BString("announce")] = BString(trackers.first())
        if (trackers.size > 1) {
            fields[BString("announce-list")] =
                ru.workinprogress.kachok.engine.bencode.BList(
                    trackers.map {
                        ru.workinprogress.kachok.engine.bencode
                            .BList(listOf(BString(it)))
                    },
                )
        }
        fields[BString("info")] = info
        val parsed = MetainfoParser.parse(Bencode.encode(BDictionary(fields)))
        check(parsed.infoHash.bytes.contentEquals(infoHash.bytes)) {
            "the parser computed a different info hash from the same bytes"
        }
        return parsed
    }

    public companion object {
        /**
         * A sanity cap on what a peer may claim `metadata_size` is.
         *
         * The number arrives in a handshake from a stranger and this class allocates it. Four
         * megabytes is a torrent of about two hundred thousand pieces, far past anything in
         * circulation, and small enough that a peer claiming more is refused rather than obeyed.
         */
        public const val MAX_SIZE: Int = 4 * 1024 * 1024

        public fun isPlausibleSize(size: Int): Boolean = size in 1..MAX_SIZE
    }
}
