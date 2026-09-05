package ru.workinprogress.kachok.engine.picker

/**
 * Which pieces something has, packed into a `LongArray`.
 *
 * A `List<Boolean>` for a torrent with a hundred thousand pieces is a hundred thousand boxed
 * objects; this is 1 562 longs. The brief's primitive-arrays rule, in the place where it matters
 * most — one of these exists per connected peer.
 *
 * The wire order is BEP 3's: "The first byte of the bitfield corresponds to indices 0 - 7 from
 * high bit to low bit", spare bits at the end zero.
 */
public class Bitfield(
    public val size: Int,
) {
    private val words = LongArray((size + WORD_BITS - 1) / WORD_BITS)

    private var count = 0

    /** How many pieces are set. Kept as a counter because the picker asks on every decision. */
    public val cardinality: Int get() = count

    public val isComplete: Boolean get() = count == size

    public operator fun get(index: Int): Boolean {
        require(index in 0 until size) { "piece $index is outside 0..${size - 1}" }
        return words[index / WORD_BITS] and (1L shl (index % WORD_BITS)) != 0L
    }

    public fun set(index: Int) {
        require(index in 0 until size) { "piece $index is outside 0..${size - 1}" }
        if (get(index)) return
        words[index / WORD_BITS] = words[index / WORD_BITS] or (1L shl (index % WORD_BITS))
        count++
    }

    /**
     * Empties the whole field.
     *
     * There is no `clear(index)`: nothing in this engine un-has a single piece. A re-check throws
     * away everything it believed and re-reads the disk, which is this, and a peer's field is
     * rebuilt rather than edited.
     */
    public fun clear() {
        words.fill(0L)
        count = 0
    }

    /** The bytes a `bitfield` message carries. */
    public fun toBytes(): ByteArray {
        val bytes = ByteArray((size + 7) / 8)
        (0 until size).forEach { index ->
            if (get(index)) {
                bytes[index / 8] = (bytes[index / 8].toInt() or (0x80 ushr (index % 8))).toByte()
            }
        }
        return bytes
    }

    public companion object {
        private const val WORD_BITS = 64

        /**
         * Reads a `bitfield` message for a torrent of [size] pieces.
         *
         * A wrong length or a set spare bit is refused: BEP 3 says the spare bits are zero, and a
         * peer that disagrees is either speaking a different protocol or lying about a piece that
         * does not exist. Accepting it would put an out-of-range index into the availability array.
         */
        public fun fromBytes(
            bytes: ByteArray,
            size: Int,
        ): Bitfield {
            val expected = (size + 7) / 8
            require(bytes.size == expected) {
                "a bitfield for $size pieces is $expected bytes, got ${bytes.size}"
            }
            val bitfield = Bitfield(size)
            (0 until size).forEach { index ->
                if (bytes[index / 8].toInt() and (0x80 ushr (index % 8)) != 0) bitfield.set(index)
            }
            val spare = expected * 8 - size
            if (spare > 0) {
                val mask = (1 shl spare) - 1
                require(bytes[expected - 1].toInt() and mask == 0) {
                    "the bitfield's $spare spare bits are not zero"
                }
            }
            return bitfield
        }
    }
}
