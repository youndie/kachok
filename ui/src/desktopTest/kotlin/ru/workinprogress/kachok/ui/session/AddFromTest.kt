package ru.workinprogress.kachok.ui.session

import ru.workinprogress.kachok.engine.InfoHash
import ru.workinprogress.kachok.engine.metainfo.MagnetLink
import ru.workinprogress.kachok.engine.metainfo.MagnetParser
import ru.workinprogress.kachok.ui.add.designMagnet
import ru.workinprogress.kachok.ui.add.designMagnetToAdd
import ru.workinprogress.kachok.ui.add.designMetainfo
import ru.workinprogress.kachok.ui.add.designTorrentToAdd
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criterion of [B-50](../../../../../../../../docs/backlog/B-50-add-torrent.md)
 * that is not a picture: the magnet case shows the hash where the name would be.
 *
 * Both halves come out of the same dialog and the same mapping; what differs is what the source
 * carried, which is the point the design makes in prose and this makes in assertions.
 */
class AddFromTest {
    @Test
    fun aTorrentSaysEverythingItCarries() {
        assertEquals("debian-13.1.0-amd64-DVD-1.iso.torrent", designTorrentToAdd.source)
        assertEquals("3.70 GiB · 1 772 pieces of 2.00 MiB · 9 files", designTorrentToAdd.summary)
        // 3.61 and not the header's 3.70: this line adds up the nine rows the dialog is showing,
        // which is what has to change when one of them is unticked. The design's own two numbers
        // disagree — its file list sums to 3.61 GiB under a header that says 3.70 — and the derived
        // one is the one this dialog can defend.
        assertEquals("9 of 9 wanted · 3.61 GiB", designTorrentToAdd.wantedSummary)
        assertEquals(designMetainfo.files.size, designTorrentToAdd.files.size)
        assertEquals("debian-13.1.0-amd64-DVD-1.iso", designTorrentToAdd.files.first().name)
        assertEquals("dists/stable/Release", designTorrentToAdd.files[FIVE].name)
        assertTrue(designTorrentToAdd.files.all { it.wanted }, "the engine downloads all of them")
    }

    /** Forty hex in five groups of eight, which is a rule rather than the mockup's line-wrap. */
    @Test
    fun theHashIsTheWholeHash() {
        assertEquals("2b3a91c4 e0f7d8a5 b6c391e2 f70d4a8b 5c6dc7f1", designTorrentToAdd.hash)
        assertEquals(HASH_HEX, designTorrentToAdd.hash.filterNot { it == ' ' }.length)
    }

    /**
     * A magnet names a torrent and carries none of it: no size, no piece count, no files. The
     * dialog says where the rest is coming from instead of leaving three blanks.
     */
    @Test
    fun aMagnetSaysWhatItCannotSayYet() {
        assertEquals(emptyList(), designMagnetToAdd.files)
        assertTrue(designMagnetToAdd.magnet)
        assertContains(designMagnetToAdd.summary, "fetched from the swarm first")
        assertTrue(
            designMagnetToAdd.summary.none { it.isDigit() },
            "no size, no count, no number of any kind: ${designMagnetToAdd.summary}",
        )
        assertEquals(designMagnet.displayName, designMagnetToAdd.source)
    }

    /** And with no `dn`, the hash is the only name it has — which is what the row shows too. */
    @Test
    fun aMagnetWithoutADisplayNameIsNamedByItsHash() {
        val anonymous =
            MagnetLink(
                infoHash = InfoHash(ByteArray(20) { (it * 17).toByte() }),
                displayName = null,
                trackers = emptyList(),
            )
        val state = addFrom(anonymous, saveTo = "~/Downloads", defaultDirectory = "~/Downloads")
        assertEquals("0011…3243", state.source)
        assertContains(state.hash, "00112233")
    }

    /** The parser's own output, not a hand-built link: a magnet the engine would actually accept. */
    @Test
    fun theDialogIsBuiltFromWhatTheParserReturns() {
        val link =
            MagnetParser.parse(
                "magnet:?xt=urn:btih:2b3a91c4e0f7d8a5b6c391e2f70d4a8b5c6dc7f1" +
                    "&dn=archlinux-2026.09.01-x86_64.iso",
            )
        val state = addFrom(link, saveTo = "~/Downloads/iso", defaultDirectory = "~/Downloads")
        assertEquals("archlinux-2026.09.01-x86_64.iso", state.source)
        assertEquals("2b3a91c4 e0f7d8a5 b6c391e2 f70d4a8b 5c6dc7f1", state.hash)
    }

    @Test
    fun bothCasesNameTheDefaultTheSettingsWouldHaveUsed() {
        listOf(designTorrentToAdd, designMagnetToAdd).forEach {
            assertEquals("Default: the folder from Settings — ~/Downloads", it.defaultNote)
            assertEquals("~/Downloads/iso", it.saveTo)
        }
    }

    private companion object {
        const val FIVE = 5
        const val HASH_HEX = 40
    }

    /** Unticking a file changes the line that counts them, and nothing else about the dialog. */
    @Test
    fun untickingAFileIsVisibleInTheSummary() {
        val without = designTorrentToAdd.withFile(0, wanted = false)
        assertEquals("8 of 9 wanted · 68.6 KiB", without.wantedSummary)
        assertEquals(false, without.files.first().wanted)
        assertEquals(designTorrentToAdd.files.size, without.files.size, "a row went missing")
        assertEquals(designTorrentToAdd.saveTo, without.saveTo)
    }

    /** And unticking everything is a torrent that would fetch nothing, said out loud. */
    @Test
    fun untickingEveryFileSaysZeroWanted() {
        val none =
            designTorrentToAdd.files.indices.fold(designTorrentToAdd) { state, at ->
                state.withFile(at, wanted = false)
            }
        assertEquals("0 of 9 wanted · 0 B", none.wantedSummary)
    }
}
