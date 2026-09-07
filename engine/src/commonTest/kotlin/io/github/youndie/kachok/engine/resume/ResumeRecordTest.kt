package io.github.youndie.kachok.engine.resume

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.picker.Bitfield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The acceptance criteria of B-23's record half. */
class ResumeRecordTest {
    private val infoHash = InfoHash(ByteArray(20) { it.toByte() })
    private val otherHash = InfoHash(ByteArray(20) { (it + 1).toByte() })

    private fun record(
        pieces: Int = 20,
        have: List<Int> = listOf(0, 3, 19),
    ): ResumeRecord {
        val verified = Bitfield(pieces)
        have.forEach { verified.set(it) }
        return ResumeRecord(infoHash, verified, uploaded = 4096, downloaded = 8192)
    }

    @Test
    fun aRecordRoundTripsThroughTheCodec() {
        val original = record()
        val read = ResumeRecord.decode(original.encode(), infoHash, pieceCount = 20)

        assertTrue(read.infoHash.bytes.contentEquals(infoHash.bytes))
        assertEquals(3, read.verified.cardinality)
        listOf(0, 3, 19).forEach { assertTrue(read.verified[it], "piece $it") }
        assertTrue(!read.verified[1])
        assertEquals(4096, read.uploaded)
        assertEquals(8192, read.downloaded)
    }

    @Test
    fun aRecordForAnotherTorrentIsRefused() {
        val thrown =
            assertFailsWith<ResumeException> {
                ResumeRecord.decode(record().encode(), otherHash, pieceCount = 20)
            }
        assertContains(thrown.message ?: "", "another torrent")
    }

    @Test
    fun aRecordForADifferentPieceCountIsRefused() {
        // The same info hash cannot have two piece counts, so this means a corrupt file rather
        // than a different torrent — either way it describes a download this is not.
        val thrown =
            assertFailsWith<ResumeException> {
                ResumeRecord.decode(record().encode(), infoHash, pieceCount = 21)
            }
        assertContains(thrown.message ?: "", "21")
    }

    @Test
    fun somethingThatIsNotARecordIsRefusedWithoutACrash() {
        listOf("", "not bencode at all", "d3:onei1ee").forEach { junk ->
            assertFailsWith<ResumeException>("'$junk' should be refused") {
                ResumeRecord.decode(junk.encodeToByteArray(), infoHash, pieceCount = 20)
            }
        }
    }

    @Test
    fun aRecordFromAFutureVersionIsRefusedRatherThanGuessedAt() {
        // Built with the encoder, not by patching bytes: a record carries an info hash and a
        // bitfield, and a text substitution over binary corrupts those instead of the version.
        val forward =
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("version") to BInteger(9),
                        BString("info hash") to BString(infoHash.bytes),
                        BString("pieces") to BInteger(20),
                        BString("verified") to BString(Bitfield(20).toBytes()),
                    ),
                ),
            )
        val thrown =
            assertFailsWith<ResumeException> {
                ResumeRecord.decode(forward, infoHash, pieceCount = 20)
            }
        assertContains(thrown.message ?: "", "version")
    }

    @Test
    fun anEmptyBitfieldIsAValidRecord() {
        val read = ResumeRecord.decode(record(have = emptyList()).encode(), infoHash, 20)
        assertEquals(0, read.verified.cardinality, "a torrent with nothing yet still has progress to record")
    }
}
