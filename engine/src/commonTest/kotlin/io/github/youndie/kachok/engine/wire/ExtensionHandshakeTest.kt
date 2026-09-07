package io.github.youndie.kachok.engine.wire

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BList
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The acceptance criteria of B-10 that need no peer: the dictionary, and what it forgives. */
class ExtensionHandshakeTest {
    @Test
    fun aHandshakeRoundTripsThroughTheBencodeCodec() {
        val original =
            ExtensionHandshake(
                extensions = mapOf(ExtensionHandshake.UT_PEX to 1, ExtensionHandshake.UT_METADATA to 2),
                clientVersion = "kachok 0.1",
                listenPort = 6881,
                requestQueueLength = 16,
                metadataSize = 40_000,
            )

        val read = ExtensionHandshake.decode(original.encode())

        assertEquals(mapOf("ut_pex" to 1, "ut_metadata" to 2), read.extensions)
        assertEquals("kachok 0.1", read.clientVersion)
        assertEquals(6881, read.listenPort)
        assertEquals(16, read.requestQueueLength)
        assertEquals(40_000, read.metadataSize)
    }

    @Test
    fun anExtensionIsSentUnderTheIdItsOwnerChose() {
        // The ids are the *peer's*, not this client's, and they are not symmetric: what makes a
        // client interoperable is asking the handshake rather than remembering a number.
        val theirs = ExtensionHandshake.decode(ExtensionHandshake(mapOf("ut_pex" to 7)).encode())

        assertEquals(7, theirs.id(ExtensionHandshake.UT_PEX))
        assertTrue(theirs.supports(ExtensionHandshake.UT_PEX))
        assertNull(theirs.id(ExtensionHandshake.UT_METADATA))
    }

    @Test
    fun anExtensionMappedToZeroIsDisabledAndNotMessageZero() {
        // BEP 10 lets a peer turn an extension off by giving it id 0 in a later handshake. Reading
        // that as an id would send every one of its messages as another handshake.
        val theirs = ExtensionHandshake.decode(ExtensionHandshake(mapOf("ut_pex" to 0)).encode())

        assertNull(theirs.id(ExtensionHandshake.UT_PEX))
        assertTrue(!theirs.supports(ExtensionHandshake.UT_PEX))
        assertEquals(mapOf("ut_pex" to 0), theirs.extensions, "what the peer said is still readable")
    }

    @Test
    fun anExtensionThisClientHasNeverHeardOfIsIgnoredRatherThanRefused() {
        val payload =
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("m") to
                            BDictionary(
                                mapOf(
                                    BString("lt_donthave") to BInteger(7),
                                    BString("ut_holepunch") to BInteger(4),
                                    BString("ut_pex") to BInteger(1),
                                ),
                            ),
                        BString("yourip") to BString(byteArrayOf(1, 2, 3, 4)),
                        BString("something_from_2029") to BList(listOf(BInteger(1))),
                    ),
                ),
            )

        val read = ExtensionHandshake.decode(payload)

        assertEquals(1, read.id("ut_pex"), "the one name we know still resolves")
        assertEquals(7, read.id("lt_donthave"), "and the ones we do not are kept, not dropped")
        assertNull(read.clientVersion, "an absent field is absent, not a failure")
    }

    @Test
    fun aFieldOfTheWrongTypeIsIgnoredAndTheRestOfTheHandshakeSurvives() {
        // Closing a connection over this would refuse to talk to a good part of the swarm.
        val payload =
            Bencode.encode(
                BDictionary(
                    mapOf(
                        BString("m") to
                            BDictionary(
                                mapOf(
                                    BString("ut_pex") to BInteger(1),
                                    BString("ut_metadata") to BString("two"),
                                ),
                            ),
                        BString("reqq") to BString("plenty"),
                        BString("v") to BString("SomeClient 1.0"),
                    ),
                ),
            )

        val read = ExtensionHandshake.decode(payload)

        assertEquals(1, read.id("ut_pex"))
        assertNull(read.id("ut_metadata"), "an id that is not a number is not an id")
        assertNull(read.requestQueueLength)
        assertEquals("SomeClient 1.0", read.clientVersion)
    }

    @Test
    fun aHandshakeWithNothingInItIsStillAHandshake() {
        // This client sends exactly this in phase 1, and a peer must not treat it as an error.
        val read = ExtensionHandshake.decode(Bencode.encode(BDictionary(emptyMap())))

        assertEquals(emptyMap(), read.extensions)
        assertNull(read.listenPort)
    }

    @Test
    fun somethingThatIsNotAHandshakeAtAllIsRefused() {
        listOf(
            "not bencode".encodeToByteArray(),
            ByteArray(0),
            Bencode.encode(BInteger(3)),
            Bencode.encode(BList(listOf(BString("m")))),
        ).forEach { payload ->
            val thrown =
                assertFailsWith<WireException>("${payload.size} bytes should be refused") {
                    ExtensionHandshake.decode(payload)
                }
            assertContains(thrown.message ?: "", "extension handshake")
        }
    }

    @Test
    fun theWireCarriesTheHandshakeAsAnExtendedMessageWithIdZero() {
        val handshake = ExtensionHandshake(mapOf("ut_pex" to 1), clientVersion = "kachok 0.1")
        val frame = PeerWire.encode(Message.Extended(ExtensionHandshake.HANDSHAKE_ID, handshake.encode()))

        val decoded = PeerWire.decode(frame, from = PeerWire.LENGTH_PREFIX_SIZE) as Message.Extended

        assertEquals(PeerWire.EXTENDED, frame[PeerWire.LENGTH_PREFIX_SIZE].toInt(), "message id 20")
        assertEquals(ExtensionHandshake.HANDSHAKE_ID, decoded.extensionId)
        assertEquals(1, ExtensionHandshake.decode(decoded.payload).id("ut_pex"))
    }
}
