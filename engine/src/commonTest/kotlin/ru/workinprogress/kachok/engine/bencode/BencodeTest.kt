package ru.workinprogress.kachok.engine.bencode

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The acceptance criteria of B-03, and the BDD scenarios of feature-metainfo they close.
 *
 * Every example here is quoted from BEP 3 rather than invented.
 */
class BencodeTest {
    @Test
    fun bep3ExamplesRoundTrip() {
        val cases =
            listOf(
                "i-3e" to BInteger(-3),
                "4:spam" to BString("spam"),
                "l4:spami42ee" to BList(listOf(BString("spam"), BInteger(42))),
                "d3:cow3:moo4:spam4:eggse" to
                    BDictionary(mapOf(BString("cow") to BString("moo"), BString("spam") to BString("eggs"))),
            )
        cases.forEach { (text, expected) ->
            val source = text.encodeToByteArray()
            assertEquals(expected, Bencode.decode(source), "decoded value of $text")
            assertEquals(text, Bencode.encode(Bencode.decode(source)).decodeToString(), "re-encoding of $text")
        }
    }

    @Test
    fun emptyContainersRoundTrip() {
        listOf("le", "de", "i0e", "0:").forEach { text ->
            assertEquals(text, Bencode.encode(Bencode.decode(text.encodeToByteArray())).decodeToString())
        }
    }

    @Test
    fun malformedInputIsRefusedWithItsOffset() {
        // The three the backlog item names, plus the two neighbouring shapes that are easy to
        // accept by accident.
        val cases =
            mapOf(
                "i03e" to 0, // leading zero
                "i-0e" to 0, // negative zero
                "5:spam" to 2, // truncated string: four bytes available where five were promised
                "ie" to 0, // no digits
                "4:spamx" to 6, // trailing bytes after the top-level value
            )
        cases.forEach { (text, offset) ->
            val thrown =
                assertFailsWith<BencodeException>("$text should not decode") {
                    Bencode.decode(text.encodeToByteArray())
                }
            assertEquals(offset, thrown.offset, "offset reported for $text")
            assertContains(thrown.message ?: "", "byte $offset", message = "message of $text names its offset")
        }
    }

    @Test
    fun duplicateKeysAreRefused() {
        val thrown =
            assertFailsWith<BencodeException> {
                Bencode.decode("d3:cow3:moo3:cow3:mooe".encodeToByteArray())
            }
        // `d` `3:cow` `3:moo` then the repeat: the second `3:cow` starts at byte 11.
        assertEquals(11, thrown.offset)
    }

    @Test
    fun unsortedKeysDecodeAndKeepTheirSourceRange() {
        // `info` is written with its keys out of sorted order on purpose: re-encoding it produces
        // different bytes, so a hash taken over the re-encoding would be a different torrent.
        val info =
            "d4:name1:a6:lengthi3e12:piece lengthi2e6:pieces20:aaaaaaaaaaaaaaaaaaaae"
                .encodeToByteArray()
        val prefix = "d8:announce8:http://x4:info".encodeToByteArray()
        val suffix = "5:notesi1ee".encodeToByteArray()
        val source = prefix + info + suffix

        val root = Bencode.decode(source) as BDictionary
        val range = checkNotNull(root.rangeOf("info")) { "the decoder recorded no range for `info`" }

        assertEquals(prefix.size, range.first)
        assertEquals(prefix.size + info.size - 1, range.last)
        assertTrue(
            source.copyOfRange(range.first, range.last + 1).contentEquals(info),
            "the recorded range slices out exactly the source bytes of `info`",
        )

        // The point of the range, stated as an assertion: the canonical re-encoding differs.
        val reEncoded = Bencode.encode(root["info"] as BDictionary)
        assertFalse(
            reEncoded.contentEquals(info),
            "this fixture exists because a re-encoding reorders the keys; if it stopped doing so " +
                "the test no longer proves anything",
        )
    }

    @Test
    fun keysAreEncodedInSortedOrderRegardlessOfInsertionOrder() {
        val dictionary =
            BDictionary(
                mapOf(
                    BString("spam") to BInteger(1),
                    BString("cow") to BInteger(2),
                    BString("a") to BInteger(3),
                ),
            )
        assertEquals("d1:ai3e3:cowi2e4:spami1ee", Bencode.encode(dictionary).decodeToString())
    }

    @Test
    fun byteStringsSurviveNonUtf8Content() {
        val raw = byteArrayOf(0xFF.toByte(), 0x00, 0x7F, 0x80.toByte())
        val encoded = Bencode.encode(BString(raw))
        assertEquals("4:", encoded.copyOfRange(0, 2).decodeToString())
        assertTrue((Bencode.decode(encoded) as BString).bytes.contentEquals(raw))
    }

    @Test
    fun nestedStructuresRoundTrip() {
        val text = "d1:ald1:bi1eed1:ci2eee1:d4:spame"
        assertEquals(text, Bencode.encode(Bencode.decode(text.encodeToByteArray())).decodeToString())
    }
}
