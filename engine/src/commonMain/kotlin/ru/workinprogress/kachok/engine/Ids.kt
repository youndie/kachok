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
