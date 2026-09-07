package io.github.youndie.kachok.engine.resume

import io.github.youndie.kachok.engine.InfoHash
import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.bencode.BencodeException
import io.github.youndie.kachok.engine.picker.Bitfield

/** A resume record this client refuses, and why. */
public class ResumeException(
    message: String,
) : IllegalArgumentException(message)

/**
 * What survives a restart.
 *
 * **Verified pieces only.** Not "written" ones: `force()` runs on a timer (research D4), so a
 * crash can lose what the page cache still held, and a record that vouched for a written piece
 * would send the client back to a swarm with a piece it does not have. A record that vouches only
 * for hashed pieces can be wrong in one direction — it may under-claim after a clean shutdown, and
 * the cost of that is a re-hash.
 *
 * Bencoded because the codec is already here and a second format would be a second parser to get
 * wrong.
 */
public class ResumeRecord(
    public val infoHash: InfoHash,
    public val verified: Bitfield,
    public val uploaded: Long,
    public val downloaded: Long,
) {
    public fun encode(): ByteArray =
        Bencode.encode(
            BDictionary(
                mapOf(
                    BString(VERSION_KEY) to BInteger(VERSION.toLong()),
                    BString(INFO_HASH_KEY) to BString(infoHash.bytes),
                    BString(PIECES_KEY) to BInteger(verified.size.toLong()),
                    BString(VERIFIED_KEY) to BString(verified.toBytes()),
                    BString(UPLOADED_KEY) to BInteger(uploaded),
                    BString(DOWNLOADED_KEY) to BInteger(downloaded),
                ),
            ),
        )

    public companion object {
        public const val VERSION: Int = 1

        private const val VERSION_KEY = "version"
        private const val INFO_HASH_KEY = "info hash"
        private const val PIECES_KEY = "pieces"
        private const val VERIFIED_KEY = "verified"
        private const val UPLOADED_KEY = "uploaded"
        private const val DOWNLOADED_KEY = "downloaded"

        /**
         * Reads a record, or refuses it.
         *
         * [expected] and [pieceCount] come from the torrent, not from the file: a record is only
         * ever read *about* a torrent, and one that disagrees with it describes a different
         * download. Refusing costs a re-hash; accepting costs a client that believes it has pieces
         * of something else.
         */
        public fun decode(
            bytes: ByteArray,
            expected: InfoHash,
            pieceCount: Int,
        ): ResumeRecord {
            val root =
                try {
                    Bencode.decode(bytes) as? BDictionary
                        ?: throw ResumeException("the resume record is not a dictionary")
                } catch (malformed: BencodeException) {
                    throw ResumeException("the resume record is not bencode: ${malformed.message}")
                }

            val version = (root[VERSION_KEY] as? BInteger)?.value
            if (version != VERSION.toLong()) {
                throw ResumeException("resume record version $version; this client writes $VERSION")
            }
            val hash =
                (root[INFO_HASH_KEY] as? BString)?.bytes
                    ?: throw ResumeException("the resume record has no `$INFO_HASH_KEY`")
            if (!hash.contentEquals(expected.bytes)) {
                throw ResumeException("the resume record is for another torrent")
            }
            val pieces =
                (root[PIECES_KEY] as? BInteger)?.value?.toInt()
                    ?: throw ResumeException("the resume record has no `$PIECES_KEY`")
            if (pieces != pieceCount) {
                throw ResumeException("the resume record is for $pieces pieces, the torrent has $pieceCount")
            }
            val verified =
                (root[VERIFIED_KEY] as? BString)?.bytes
                    ?: throw ResumeException("the resume record has no `$VERIFIED_KEY`")

            return ResumeRecord(
                infoHash = expected,
                verified =
                    try {
                        Bitfield.fromBytes(verified, pieceCount)
                    } catch (wrong: IllegalArgumentException) {
                        throw ResumeException("the resume record's bitfield does not fit: ${wrong.message}")
                    },
                uploaded = (root[UPLOADED_KEY] as? BInteger)?.value ?: 0,
                downloaded = (root[DOWNLOADED_KEY] as? BInteger)?.value ?: 0,
            )
        }
    }
}

/**
 * Where a resume record lives.
 *
 * An interface because "atomically" means something different on every platform, and because a
 * session that cannot write one should still run — a client that refuses to download because it
 * cannot save its progress is worse than one that re-hashes on the next start.
 */
public interface ResumeStore {
    /** The record, or null when there is none or it does not belong to this torrent. */
    public suspend fun load(): ResumeRecord?

    public suspend fun save(record: ResumeRecord)
}
