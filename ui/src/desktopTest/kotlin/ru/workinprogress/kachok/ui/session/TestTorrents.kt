package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.bencode.BDictionary
import ru.workinprogress.kachok.engine.bencode.BInteger
import ru.workinprogress.kachok.engine.bencode.BString
import ru.workinprogress.kachok.engine.bencode.Bencode

/**
 * Small, valid `.torrent` bytes for tests that need a torrent and not a swarm.
 *
 * **Built with the engine's own encoder, never typed.** This repository has had three hand-written
 * bencode fixtures with wrong length prefixes, and a wrong prefix fails the parse before any
 * assertion in the test gets to run.
 *
 * The piece hashes are arbitrary: nothing here downloads, and a test that wants bytes off a wire
 * uses `LocalSwarm` instead.
 */
internal object TestTorrents {
    fun bytes(
        name: String,
        length: Int = LENGTH,
    ): ByteArray =
        Bencode.encode(
            BDictionary(
                mapOf(
                    BString("announce") to BString("http://tracker.invalid/annc"),
                    BString("info") to
                        BDictionary(
                            mapOf(
                                BString("name") to BString(name),
                                BString("length") to BInteger(length.toLong()),
                                BString("piece length") to BInteger(PIECE.toLong()),
                                // One 20-byte hash per piece, and the name is mixed in so that two
                                // torrents built here are two torrents rather than one.
                                BString("pieces") to
                                    BString(
                                        ByteArray(HASH * ((length + PIECE - 1) / PIECE)) {
                                            (name[it % name.length].code + it).toByte()
                                        },
                                    ),
                            ),
                        ),
                ),
            ),
        )

    private const val LENGTH = 1200
    private const val PIECE = 512
    private const val HASH = 20
}
