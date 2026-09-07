package io.github.youndie.kachok.engine.bencode

/**
 * Malformed bencode. The [offset] is in the message as well as on the field: the message is what
 * ends up in a log, and "malformed torrent" without a position is a bug report nobody can act on.
 */
public class BencodeException(
    public val offset: Int,
    public val detail: String,
) : IllegalArgumentException("bencode: at byte $offset: $detail")
