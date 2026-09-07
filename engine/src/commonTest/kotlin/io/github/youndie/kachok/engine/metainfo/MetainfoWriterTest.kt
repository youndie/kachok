package io.github.youndie.kachok.engine.metainfo

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A `.torrent` written back has to be the same torrent, and "the same" means the info hash.
 *
 * The client keeps its own copy of every torrent so that closing the window does not lose it
 * ([B-81](../../../../../../../../docs/backlog/B-81-the-torrent-list-survives-a-restart.md)). A
 * copy that parses back under a different hash is worse than no copy: it looks like a torrent, it
 * has a resume record beside the data that it can no longer claim, and it asks a swarm for
 * something nobody there has.
 *
 * **The fixtures are generated, not typed.** Same rule and same reason as `MetainfoParserTest`:
 * hand-written length prefixes were wrong three times. These came from a throwaway Python bencoder
 * and `hashlib.sha1`, and the single-tracker one it produced is byte-for-byte the fixture that test
 * already had — which is the cheapest possible check that the generator agrees with the one before
 * it.
 */
class MetainfoWriterTest {
    /** `name` before `length`: **not** sorted order, so canonical re-encoding changes the bytes. */
    private val info =
        "d4:name10:readme.txt6:lengthi1200e12:piece lengthi512e6:pieces60:" +
            "AAAAAAAAAAAAAAAAAAAABBBBBBBBBBBBBBBBBBBBCCCCCCCCCCCCCCCCCCCCe"

    /** From Python's `hashlib.sha1` over the bytes above, not from this code. */
    private val infoHash = "b9f1cc77410aac4f6169c7c129113c198d440625"

    private val oneTracker = "d8:announce27:http://tracker.example/annc4:info${info}e"

    private val twoTrackers =
        "d8:announce27:http://tracker.example/annc13:announce-listll27:http://tracker.example/annc" +
            "24:udp://other.example/anncee4:info${info}e"

    private val noTracker = "d4:info${info}e"

    private fun roundTrip(source: String): Metainfo =
        MetainfoParser.parse(MetainfoWriter.toTorrentFile(MetainfoParser.parse(source.encodeToByteArray())))

    @Test
    fun aTorrentWrittenBackParsesAsTheSameTorrent() {
        val again = roundTrip(oneTracker)
        assertEquals(infoHash, again.infoHash.bytes.toHex())
        assertEquals("readme.txt", again.name)
        assertEquals(512, again.pieceLength)
        assertEquals(1200L, again.totalLength)
        assertEquals(listOf("http://tracker.example/annc"), again.trackers)
    }

    /**
     * The one that would be silent.
     *
     * A canonical re-encoding of this info dictionary is different bytes and therefore a different
     * hash, and nothing about the resulting file looks wrong — it parses, it names the same files,
     * it has the same size. It is simply a different torrent from the one whose resume record sits
     * beside the data.
     */
    @Test
    fun theInfoDictionaryComesOutByteForByteEvenWhenItsKeysAreUnsorted() {
        val original = MetainfoParser.parse(oneTracker.encodeToByteArray())
        assertEquals(info, original.infoBytes.decodeToString(), "the fixture stopped being unsorted")
        val written = MetainfoWriter.toTorrentFile(original)
        assertTrue(
            written.asSequence().windowed(original.infoBytes.size).any {
                it.toByteArray().contentEquals(original.infoBytes)
            },
            "the info dictionary was re-encoded rather than spliced",
        )
        assertContentEquals(original.infoBytes, MetainfoParser.parse(written).infoBytes)
    }

    @Test
    fun everyTrackerSurvivesTheRoundTrip() {
        assertEquals(
            MetainfoParser.parse(twoTrackers.encodeToByteArray()).trackers,
            roundTrip(twoTrackers).trackers,
        )
    }

    /**
     * A torrent with no tracker at all, which a magnet fetched over the DHT is.
     *
     * Writing an empty `announce` would be a tracker URL of `""`, and every reader of the file
     * would then try to announce to it.
     */
    @Test
    fun aTorrentWithNoTrackersDoesNotGrowAnEmptyOne() {
        val again = roundTrip(noTracker)
        assertEquals(emptyList(), again.trackers)
        assertEquals(infoHash, again.infoHash.bytes.toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
