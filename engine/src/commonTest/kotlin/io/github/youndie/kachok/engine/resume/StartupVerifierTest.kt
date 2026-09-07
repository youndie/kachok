package io.github.youndie.kachok.engine.resume

import kotlinx.coroutines.test.runTest
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.metainfo.Metainfo
import io.github.youndie.kachok.engine.metainfo.MetainfoParser
import io.github.youndie.kachok.engine.peer.Block
import io.github.youndie.kachok.engine.picker.Bitfield
import io.github.youndie.kachok.engine.storage.PieceHasher
import io.github.youndie.kachok.engine.storage.Storage
import io.github.youndie.kachok.engine.wire.PeerWire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The acceptance criteria of B-24. */
class StartupVerifierTest {
    private fun torrent(pieces: Int): Metainfo {
        val info =
            BDictionary(
                mapOf(
                    BString("length") to BInteger(pieces.toLong() * PeerWire.BLOCK_SIZE),
                    BString("name") to BString("fixture"),
                    BString("piece length") to BInteger(PeerWire.BLOCK_SIZE.toLong()),
                    BString("pieces") to BString(ByteArray(pieces * Metainfo.HASH_SIZE) { it.toByte() }),
                ),
            )
        return MetainfoParser.parse(Bencode.encode(BDictionary(mapOf(BString("info") to info))))
    }

    private class FakeBlock(
        override val piece: PieceIndex,
        override val begin: Int,
        override val length: Int,
    ) : Block {
        var released = false

        override fun release() {
            released = true
        }
    }

    /** A disk holding exactly the pieces it was told to hold. */
    private class FakeDisk(
        val present: Set<Int>,
    ) : Storage {
        val readBlocks = mutableListOf<FakeBlock>()

        override suspend fun write(
            piece: PieceIndex,
            blocks: List<Block>,
        ) = Unit

        override suspend fun readPiece(piece: PieceIndex): List<Block>? =
            if (piece.value in present) {
                listOf(FakeBlock(piece, 0, PeerWire.BLOCK_SIZE).also { readBlocks += it })
            } else {
                null
            }

        override suspend fun flush() = Unit
    }

    /** Agrees with the torrent about whatever it is given. */
    private class AgreeableHasher(
        private val metainfo: Metainfo,
    ) : PieceHasher {
        var calls = 0

        override suspend fun hash(blocks: List<Block>): ByteArray {
            calls++
            return metainfo.pieceHash(blocks.first().piece)
        }
    }

    @Test
    fun aFinishedDownloadWithNoRecordIsRecognisedAsFinished() =
        runTest {
            // The record is an optimisation, never the source of truth: a directory somebody copied in
            // has no record and is still complete.
            val metainfo = torrent(pieces = 5)
            val hasher = AgreeableHasher(metainfo)
            val verified = StartupVerifier(metainfo, FakeDisk((0..4).toSet()), hasher).verify(record = null)

            assertTrue(verified.isComplete)
            assertEquals(5, hasher.calls, "every piece was hashed, because nothing vouched for them")
        }

    @Test
    fun aRecordMissingThreePiecesRehashesExactlyThree() =
        runTest {
            val metainfo = torrent(pieces = 10)
            val hasher = AgreeableHasher(metainfo)
            val record =
                ResumeRecord(
                    infoHash = metainfo.infoHash,
                    verified =
                        Bitfield(10).also { bits ->
                            (0 until 10).filter { it !in setOf(3, 5, 8) }.forEach(bits::set)
                        },
                    uploaded = 0,
                    downloaded = 0,
                )
            val verified = StartupVerifier(metainfo, FakeDisk((0..9).toSet()), hasher).verify(record)

            assertEquals(3, hasher.calls, "the record's word was taken for the other seven")
            assertTrue(verified.isComplete, "and the three that were checked turned out to be there")
        }

    @Test
    fun aPieceThatIsNotOnTheDiskIsNotClaimed() =
        runTest {
            val metainfo = torrent(pieces = 4)
            val hasher = AgreeableHasher(metainfo)
            val verified = StartupVerifier(metainfo, FakeDisk(setOf(0, 2)), hasher).verify(record = null)

            assertEquals(listOf(true, false, true, false), (0..3).map { verified[it] })
            assertEquals(2, hasher.calls, "only the pieces that could be read were hashed")
        }

    @Test
    fun everyBlockReadForCheckingIsReleased() =
        runTest {
            // A verification pass must cost the memory of a few blocks, not of a torrent.
            val metainfo = torrent(pieces = 6)
            val disk = FakeDisk((0..5).toSet())
            StartupVerifier(metainfo, disk, AgreeableHasher(metainfo)).verify(record = null)

            assertEquals(6, disk.readBlocks.size)
            assertTrue(disk.readBlocks.all { it.released }, "a block read for hashing was never released")
        }

    @Test
    fun theCheckReportsItsProgress() =
        runTest {
            // A full check of a large torrent takes minutes, and a client that looks frozen gets killed.
            val metainfo = torrent(pieces = 4)
            val seen = mutableListOf<Pair<Int, Int>>()
            StartupVerifier(metainfo, FakeDisk(emptySet()), AgreeableHasher(metainfo))
                .verify(record = null) { checked, total -> seen += checked to total }

            assertEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4), seen)
        }

    @Test
    fun aRecordThatOverclaimsIsStillTrustedAndThatIsTheTradeOff() =
        runTest {
            // The record is trusted by construction: not re-hashing is the whole point of it. It can
            // only over-claim if it was written for pieces that were hashed and then lost — which the
            // write ordering prevents (B-23) — so the trust is bounded by that ordering, not by a
            // check here. Stated as a test so the trade-off is visible rather than implied.
            val metainfo = torrent(pieces = 3)
            val hasher = AgreeableHasher(metainfo)
            val record =
                ResumeRecord(
                    infoHash = metainfo.infoHash,
                    verified = Bitfield(3).also { (0..2).forEach(it::set) },
                    uploaded = 0,
                    downloaded = 0,
                )
            val verified = StartupVerifier(metainfo, FakeDisk(emptySet()), hasher).verify(record)

            assertTrue(verified.isComplete, "the record was believed")
            assertEquals(0, hasher.calls, "and nothing was read to check it")
        }
}
