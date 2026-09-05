package ru.workinprogress.kachok.engine.bencode

/**
 * A bencoded value (BEP 3). Four types and nothing else: byte string, integer, list, dictionary.
 *
 * Byte strings are byte strings, not text: a `.torrent` may carry file names in any encoding, and
 * decoding them eagerly would lose bytes the info hash is computed over.
 */
public sealed interface BValue

/** `i<number>e`. */
public class BInteger(
    public val value: Long,
) : BValue {
    override fun equals(other: Any?): Boolean = other is BInteger && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "i${value}e"
}

/** `<length>:<bytes>`. */
public class BString(
    public val bytes: ByteArray,
) : BValue {
    public constructor(text: String) : this(text.encodeToByteArray())

    /** The bytes read as UTF-8. Lossy by construction; never use it to rebuild the source. */
    public fun asString(): String = bytes.decodeToString()

    override fun equals(other: Any?): Boolean = other is BString && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "\"${asString()}\""
}

/** `l<values>e`. */
public class BList(
    public val items: List<BValue>,
) : BValue {
    override fun equals(other: Any?): Boolean = other is BList && other.items == items

    override fun hashCode(): Int = items.hashCode()

    override fun toString(): String = items.toString()
}

/**
 * `d<key><value>…e`, in the order the source wrote them.
 *
 * [valueRanges] is why this class exists rather than a plain `Map`: it holds, for every key, the
 * **inclusive** range of the source bytes that value occupies. BEP 3 requires the info hash to be
 * taken over the original bytes of the `info` dictionary — "clients … must not perform a
 * decode-encode roundtrip" — so a hash computed from [Bencode.encode] is wrong by specification on
 * any file whose dictionaries are not in canonical order, and such files exist.
 *
 * The map is empty for a dictionary that was built rather than decoded.
 */
public class BDictionary(
    public val entries: Map<BString, BValue>,
    public val valueRanges: Map<BString, IntRange> = emptyMap(),
) : BValue {
    public operator fun get(key: String): BValue? = entries[BString(key)]

    public operator fun get(key: BString): BValue? = entries[key]

    /** The inclusive source range of `key`'s value, or null if this dictionary was not decoded. */
    public fun rangeOf(key: String): IntRange? = valueRanges[BString(key)]

    override fun equals(other: Any?): Boolean = other is BDictionary && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = entries.toString()
}
