package io.github.youndie.kachok.engine.bencode

/**
 * The bencode codec (BEP 3).
 *
 * Strict on the way in — no leading zeros, no negative zero, no trailing bytes, no duplicate
 * dictionary keys — and canonical on the way out. There is no lenient mode: a torrent that does
 * not decode is a torrent this client refuses, with the byte offset of the defect.
 *
 * Keys are **not** required to be sorted on the way in, although BEP 3 says they should be. Files
 * with unsorted keys circulate, every other client reads them, and refusing them would buy
 * nothing; what protects the info hash from their existence is [BDictionary.valueRanges].
 */
public object Bencode {
    /** Decodes exactly one value; trailing bytes are an error. */
    public fun decode(bytes: ByteArray): BValue = Decoder(bytes).decodeDocument()

    /**
     * Decodes one value from the front and says where it ended.
     *
     * For the one protocol that puts bencode and raw bytes in the same message: BEP 9's
     * `ut_metadata` is a dictionary followed immediately by the block it describes, and there is no
     * length anywhere saying where one stops and the other starts — the decoder's own position is
     * the only answer. Everywhere else, trailing bytes are an error and [decode] is what to use.
     */
    public fun decodePrefix(bytes: ByteArray): Pair<BValue, Int> {
        val decoder = Decoder(bytes)
        val value = decoder.decodeOneValue()
        return value to decoder.position
    }

    /** Encodes canonically: dictionary keys sorted by raw byte order. */
    public fun encode(value: BValue): ByteArray {
        val sink = ByteSink()
        sink.writeValue(value)
        return sink.toByteArray()
    }
}

private const val ZERO = '0'.code.toByte()
private const val NINE = '9'.code.toByte()
private const val MINUS = '-'.code.toByte()
private const val COLON = ':'.code.toByte()
private const val END = 'e'.code.toByte()
private const val INTEGER = 'i'.code.toByte()
private const val LIST = 'l'.code.toByte()
private const val DICTIONARY = 'd'.code.toByte()

private class Decoder(
    private val bytes: ByteArray,
) {
    private var pos = 0

    val position: Int get() = pos

    fun decodeOneValue(): BValue = decodeValue()

    fun decodeDocument(): BValue {
        val value = decodeValue()
        if (pos != bytes.size) {
            fail(pos, "${bytes.size - pos} trailing bytes after the top-level value")
        }
        return value
    }

    private fun decodeValue(): BValue {
        if (pos >= bytes.size) fail(pos, "unexpected end of input, expected a value")
        return when (val marker = bytes[pos]) {
            INTEGER -> {
                decodeInteger()
            }

            LIST -> {
                decodeList()
            }

            DICTIONARY -> {
                decodeDictionary()
            }

            else -> {
                if (marker.isDigit()) {
                    decodeString()
                } else {
                    fail(pos, "expected a value, found '${marker.printable()}'")
                }
            }
        }
    }

    private fun decodeInteger(): BInteger {
        val start = pos
        pos++ // 'i'
        val digitsStart = if (pos < bytes.size && bytes[pos] == MINUS) pos + 1 else pos
        pos = digitsStart
        while (pos < bytes.size && bytes[pos].isDigit()) pos++
        if (pos == digitsStart) fail(start, "integer with no digits")
        val negative = digitsStart != start + 1
        val digits = bytes.decodeToString(digitsStart, pos)
        if (negative && digits == "0") fail(start, "negative zero is not a bencoded integer")
        if (digits.length > 1 && digits[0] == '0') fail(start, "integer with a leading zero: '$digits'")
        expect(END, "integer")
        val value = digits.toLongOrNull() ?: fail(start, "integer does not fit in 64 bits: '$digits'")
        return BInteger(if (negative) -value else value)
    }

    private fun decodeString(): BString {
        val start = pos
        while (pos < bytes.size && bytes[pos].isDigit()) pos++
        val digits = bytes.decodeToString(start, pos)
        if (digits.length > 1 && digits[0] == '0') {
            fail(start, "string length with a leading zero: '$digits'")
        }
        expect(COLON, "string length")
        val length = digits.toIntOrNull() ?: fail(start, "string length does not fit in 32 bits: '$digits'")
        if (pos + length > bytes.size) {
            fail(pos, "string of $length bytes runs past the end of the input (${bytes.size - pos} available)")
        }
        val value = bytes.copyOfRange(pos, pos + length)
        pos += length
        return BString(value)
    }

    private fun decodeList(): BList {
        val start = pos
        pos++ // 'l'
        val items = mutableListOf<BValue>()
        while (true) {
            if (pos >= bytes.size) fail(start, "unterminated list")
            if (bytes[pos] == END) {
                pos++
                return BList(items)
            }
            items += decodeValue()
        }
    }

    private fun decodeDictionary(): BDictionary {
        val start = pos
        pos++ // 'd'
        val entries = LinkedHashMap<BString, BValue>()
        val ranges = LinkedHashMap<BString, IntRange>()
        while (true) {
            if (pos >= bytes.size) fail(start, "unterminated dictionary")
            if (bytes[pos] == END) {
                pos++
                return BDictionary(entries, ranges)
            }
            val keyAt = pos
            if (!bytes[pos].isDigit()) {
                fail(pos, "dictionary key must be a byte string, found '${bytes[pos].printable()}'")
            }
            val key = decodeString()
            if (key in entries) fail(keyAt, "duplicate dictionary key $key")
            val valueStart = pos
            entries[key] = decodeValue()
            ranges[key] = valueStart..(pos - 1)
        }
    }

    private fun expect(
        byte: Byte,
        what: String,
    ) {
        if (pos >= bytes.size) fail(pos, "unexpected end of input, expected '${byte.printable()}' after a $what")
        if (bytes[pos] != byte) {
            fail(pos, "expected '${byte.printable()}' after a $what, found '${bytes[pos].printable()}'")
        }
        pos++
    }

    private fun fail(
        at: Int,
        detail: String,
    ): Nothing = throw BencodeException(at, detail)
}

private fun Byte.isDigit(): Boolean = this >= ZERO && this <= NINE

private fun Byte.printable(): String {
    val code = toInt() and 0xFF
    return if (code in 0x20..0x7E) code.toChar().toString() else "0x${code.toString(16).padStart(2, '0')}"
}

/** A growable byte buffer; the standard library has none in common code. */
private class ByteSink {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    fun writeValue(value: BValue) {
        when (value) {
            is BInteger -> {
                write(INTEGER)
                writeAscii(value.value.toString())
                write(END)
            }

            is BString -> {
                writeAscii(value.bytes.size.toString())
                write(COLON)
                write(value.bytes)
            }

            is BList -> {
                write(LIST)
                value.items.forEach { writeValue(it) }
                write(END)
            }

            is BDictionary -> {
                write(DICTIONARY)
                value.entries.keys.sortedWith(::compareKeys).forEach { key ->
                    writeValue(key)
                    writeValue(value.entries.getValue(key))
                }
                write(END)
            }
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun write(byte: Byte) {
        ensure(1)
        buffer[size++] = byte
    }

    private fun write(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    private fun writeAscii(text: String) {
        ensure(text.length)
        text.forEach { buffer[size++] = it.code.toByte() }
    }

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size * 2
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}

/** Unsigned byte order, which is what "sorted keys" means for byte strings. */
private fun compareKeys(
    left: BString,
    right: BString,
): Int {
    val a = left.bytes
    val b = right.bytes
    for (i in 0 until minOf(a.size, b.size)) {
        val difference = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
        if (difference != 0) return difference
    }
    return a.size - b.size
}
