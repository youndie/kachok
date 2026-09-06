package ru.workinprogress.kachok.engine

import kotlin.jvm.JvmInline

/**
 * The 20-byte SHA-1 of the bencoded `info` dictionary (BEP 3). Identifies a torrent everywhere:
 * the tracker request, the handshake, the resume file name.
 */
@JvmInline
public value class InfoHash(
    public val bytes: ByteArray,
) {
    init {
        require(bytes.size == SIZE) { "info hash must be $SIZE bytes, got ${bytes.size}" }
    }

    public companion object {
        public const val SIZE: Int = 20
    }
}

/**
 * The forty lowercase hex characters a person reads and a file name uses.
 *
 * Here rather than three private copies of the same loop: the resume record is named by it, the
 * client's remembered list of torrents is keyed by it, and the set looks a torrent up by it. Three
 * spellings of one identity is three chances for one of them to pad differently.
 */
public fun InfoHash.hex(): String =
    bytes.joinToString("") {
        (it.toInt() and 0xFF).toString(HEX_RADIX).padStart(2, '0')
    }

private const val HEX_RADIX = 16

/** A 20-byte peer id (BEP 3, conventions in BEP 20). */
@JvmInline
public value class PeerId(
    public val bytes: ByteArray,
) {
    init {
        require(bytes.size == SIZE) { "peer id must be $SIZE bytes, got ${bytes.size}" }
    }

    public companion object {
        public const val SIZE: Int = 20
    }
}

/** Zero-based index of a piece within a torrent. */
@JvmInline
public value class PieceIndex(
    public val value: Int,
)
