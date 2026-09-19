package io.github.youndie.kachok.swarm

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.PeerId
import io.github.youndie.kachok.engine.PieceIndex
import io.github.youndie.kachok.engine.wire.Handshake
import io.github.youndie.kachok.engine.wire.Message
import io.github.youndie.kachok.engine.wire.PeerWire
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The stand's own two rules, checked against the stand.
 *
 * **A harness is not exempt from being tested, it is the thing least exempt.** Every suite above
 * this one goes green on whatever this seed does, so a seed that announced a subset and then served
 * the whole torrent anyway would turn every picker question into a question about nothing, and the
 * answer would look exactly like a pass
 * ([B-123](../../../../../../../docs/backlog/B-123-a-seed-that-holds-part-of-the-torrent.md)).
 *
 * The client here is a raw socket rather than the engine: what is under test is the seed, and a
 * test that reached it through this repository's own client could not tell a seed that refuses from
 * a client that never asks.
 */
class SeedingPeerTest {
    private val infoHash = InfoHash(ByteArray(HASH_SIZE) { it.toByte() })

    /** Eight pieces of one block, each filled with its own index, so a block names its piece. */
    private val content = ByteArray(PIECES * PeerWire.BLOCK_SIZE) { (it / PeerWire.BLOCK_SIZE).toByte() }

    @Test
    fun aSeedAnnouncesOnlyThePiecesItHolds() {
        SeedingPeer(infoHash, content, PeerWire.BLOCK_SIZE, holds = setOf(1, 5)).use { seed ->
            connect(seed).use { socket ->
                val bitfield = openingBitfield(socket)
                assertContentEquals(
                    listOf(1, 5),
                    (0 until PIECES).filter { bitfield[it / 8].toInt() and (0x80 ushr (it % 8)) != 0 },
                    "the seed announced pieces it does not hold, or hid ones it does",
                )
            }
        }
    }

    /** And the announcement is not a promise it would break if asked to. */
    @Test
    fun aSeedHangsUpOnARequestForAPieceItDoesNotHold() {
        SeedingPeer(infoHash, content, PeerWire.BLOCK_SIZE, holds = setOf(1)).use { seed ->
            connect(seed).use { socket ->
                openingBitfield(socket)
                write(socket, PeerWire.encode(Message.Request(PieceIndex(4), 0, PeerWire.BLOCK_SIZE)))

                assertTrue(readsUntilClosed(socket), "the seed served a piece it had announced it has not got")
                assertEquals(1, seed.refused.size, "the refusal was not recorded")
                assertEquals(
                    4,
                    seed.refused
                        .first()
                        .piece.value,
                )
                assertTrue(seed.served.isEmpty(), "nothing should have been served")
            }
        }
    }

    /** A piece it does hold is served as before, or the refusal above would prove nothing. */
    @Test
    fun aSeedStillServesWhatItHolds() {
        SeedingPeer(infoHash, content, PeerWire.BLOCK_SIZE, holds = setOf(1)).use { seed ->
            connect(seed).use { socket ->
                openingBitfield(socket)
                write(socket, PeerWire.encode(Message.Request(PieceIndex(1), 0, PeerWire.BLOCK_SIZE)))

                val piece = readMessage(socket)
                assertTrue(piece is Message.Piece, "the seed answered ${piece::class.simpleName}")
                assertEquals(1, piece.piece.value)
                assertTrue(seed.refused.isEmpty())
            }
        }
    }

    /**
     * A rate is a floor on the time, never a ceiling, and that is what makes it assertable.
     *
     * The seed sleeps for the time a block should have taken *after* sending it, so nothing here
     * can make a machine faster than it is: the assertion is "took at least as long as the rate
     * allows" and there is no upper bound to go flaky on a loaded runner
     * ([B-125](../../../../../../../docs/backlog/B-125-a-measurement-that-is-a-pair.md) is where
     * the numbers that need one live, and they do not live in `build`).
     */
    @Test
    fun aRateLimitedSeedCannotBeatItsOwnRate() {
        val blocks = 4
        val rate = PeerWire.BLOCK_SIZE.toLong() * 8 // eight blocks a second: half a second for four
        SeedingPeer(infoHash, content, PeerWire.BLOCK_SIZE, bytesPerSecond = rate).use { seed ->
            connect(seed).use { socket ->
                openingBitfield(socket)
                val started = TimeSource.Monotonic.markNow()
                (0 until blocks).forEach { index ->
                    write(socket, PeerWire.encode(Message.Request(PieceIndex(index), 0, PeerWire.BLOCK_SIZE)))
                    readMessage(socket)
                }
                val took = started.elapsedNow().inWholeMilliseconds

                // `blocks - 1` gaps and not `blocks`: the seed pays for a block after it has sent
                // it, so the last sleep happens behind the reader rather than in front of it. A
                // floor that counted it would be asserting a delay nobody waits for.
                val floor = (blocks - 1).toLong() * PeerWire.BLOCK_SIZE * MILLIS_PER_SECOND / rate
                assertTrue(
                    took >= floor,
                    "$blocks blocks at $rate bytes a second cannot take ${took}ms; the rate allows no less than ${floor}ms",
                )
            }
        }
    }

    /** Unlimited is still the default: every other suite depends on a seed that does not wait. */
    @Test
    fun aSeedWithoutARateHasNoRate() {
        SeedingPeer(infoHash, content, PeerWire.BLOCK_SIZE).use { seed ->
            connect(seed).use { socket ->
                openingBitfield(socket)
                val started = TimeSource.Monotonic.markNow()
                repeat(PIECES) { index ->
                    write(socket, PeerWire.encode(Message.Request(PieceIndex(index), 0, PeerWire.BLOCK_SIZE)))
                    readMessage(socket)
                }
                assertTrue(
                    started.elapsedNow().inWholeSeconds < UNPACED_SECONDS,
                    "an unpaced seed took seconds to serve eight blocks over loopback",
                )
                assertEquals(PIECES, seed.served.size)
            }
        }
    }

    /**
     * Reads the seed's opening — `bitfield` then `unchoke` — and returns the bits.
     *
     * Both, because a reader that took only the first one would find the `unchoke` sitting where
     * the answer to its first request should be, and read the wrong frame for the rest of the test.
     * Three of the five tests here failed that way before this consumed the pair.
     */
    private fun openingBitfield(socket: SocketChannel): ByteArray {
        val bitfield = readMessage(socket)
        assertTrue(bitfield is Message.Bitfield, "the seed opened with ${bitfield::class.simpleName}")
        val unchoke = readMessage(socket)
        assertTrue(unchoke is Message.Unchoke, "the seed's second word was ${unchoke::class.simpleName}")
        return bitfield.bits
    }

    private fun connect(seed: SeedingPeer): SocketChannel {
        val socket = SocketChannel.open(InetSocketAddress("127.0.0.1", seed.port))
        write(socket, Handshake(infoHash, PeerId("-TEST01-000000000000".encodeToByteArray())).encode())
        val theirs = ByteBuffer.allocate(Handshake.SIZE)
        while (theirs.hasRemaining()) check(socket.read(theirs) >= 0) { "the seed hung up during the handshake" }
        return socket
    }

    private fun readMessage(socket: SocketChannel): Message {
        val length = ByteBuffer.allocate(PeerWire.LENGTH_PREFIX_SIZE)
        while (length.hasRemaining()) check(socket.read(length) >= 0) { "the seed hung up before a message" }
        val size = length.flip().int
        if (size == 0) return readMessage(socket)
        val frame = ByteBuffer.allocate(size)
        while (frame.hasRemaining()) check(socket.read(frame) >= 0) { "the seed hung up inside a message" }
        return PeerWire.decode(frame.array())
    }

    /** True when the connection ends before anything arrives, which is the refusal. */
    private fun readsUntilClosed(socket: SocketChannel): Boolean {
        val buffer = ByteBuffer.allocate(PeerWire.LENGTH_PREFIX_SIZE)
        while (buffer.hasRemaining()) {
            if (socket.read(buffer) < 0) return true
        }
        return false
    }

    private fun write(
        socket: SocketChannel,
        bytes: ByteArray,
    ) {
        val out = ByteBuffer.wrap(bytes)
        while (out.hasRemaining()) socket.write(out)
    }

    private companion object {
        const val PIECES = 8
        const val HASH_SIZE = 20
        const val MILLIS_PER_SECOND = 1_000L
        const val UNPACED_SECONDS = 2L
    }
}
