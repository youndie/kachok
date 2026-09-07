package io.github.youndie.kachok.engine.wire

import io.github.youndie.kachok.engine.bencode.BDictionary
import io.github.youndie.kachok.engine.bencode.BInteger
import io.github.youndie.kachok.engine.bencode.BString
import io.github.youndie.kachok.engine.bencode.Bencode
import io.github.youndie.kachok.engine.bencode.BencodeException

/**
 * BEP 10's handshake: the bencoded dictionary a peer sends as extended message `0`.
 *
 * The one field that matters is `m`, which maps an extension's *name* to the message id **this
 * peer** wants it sent under. The ids are not global and not symmetric: `ut_pex` may be 1 here and
 * 3 there, and each side sends the other's number. A client that hard-codes an id talks to
 * whichever peers happen to agree with it.
 *
 * Everything else is advisory, and this class reads it as such — an absent field is not an error,
 * a field of the wrong type is not an error, and a name in `m` that this client has never heard of
 * is not an error either. BEP 10 is explicit that unknown extensions are ignored, and it has to
 * be: the whole point of the mechanism is that peers built years apart can talk.
 */
public class ExtensionHandshake(
    /** Name → the id this peer wants that extension's messages sent under. */
    public val extensions: Map<String, Int> = emptyMap(),
    /** `v`: the peer's client and version, for humans. */
    public val clientVersion: String? = null,
    /** `p`: the port the peer listens on, which may not be the port it dialled from. */
    public val listenPort: Int? = null,
    /** `reqq`: how many outstanding requests the peer will accept. */
    public val requestQueueLength: Int? = null,
    /** `metadata_size`: the size of the info dictionary, for BEP 9. */
    public val metadataSize: Int? = null,
) {
    /**
     * The id to send [name] under, or null if this peer does not speak it.
     *
     * BEP 10: an id of `0` in `m` means the extension is **disabled**, which is how a peer turns
     * one off in a later handshake without renumbering the rest. Reading it as an id would send
     * every message of that extension as another handshake.
     */
    public fun id(name: String): Int? = extensions[name]?.takeIf { it != HANDSHAKE_ID }

    public fun supports(name: String): Boolean = id(name) != null

    public fun encode(): ByteArray {
        val fields = LinkedHashMap<BString, io.github.youndie.kachok.engine.bencode.BValue>()
        fields[BString("m")] =
            BDictionary(extensions.entries.associate { BString(it.key) to BInteger(it.value.toLong()) })
        clientVersion?.let { fields[BString("v")] = BString(it) }
        listenPort?.let { fields[BString("p")] = BInteger(it.toLong()) }
        requestQueueLength?.let { fields[BString("reqq")] = BInteger(it.toLong()) }
        metadataSize?.let { fields[BString("metadata_size")] = BInteger(it.toLong()) }
        return Bencode.encode(BDictionary(fields))
    }

    override fun toString(): String =
        "ExtensionHandshake(${clientVersion ?: "?"}, ${extensions.keys.sorted().joinToString(",")})"

    public companion object {
        /** BEP 10: extended message `0` is the handshake itself, on both sides, always. */
        public const val HANDSHAKE_ID: Int = 0

        /** BEP 11's name for peer exchange, and BEP 9's for metadata. Here so nobody retypes them. */
        public const val UT_PEX: String = "ut_pex"
        public const val UT_METADATA: String = "ut_metadata"

        /**
         * The ids **this client** asks peers to use, which is a choice and not a protocol constant.
         *
         * In one place because two halves of this codebase publish them and both must publish the
         * same numbers: the session, and the metadata fetch that runs before a session exists. A
         * peer's ids are its own and are read from its handshake — see [id].
         */
        public const val ID_UT_PEX: Int = 1
        public const val ID_UT_METADATA: Int = 2

        /**
         * Reads a handshake payload, refusing only what cannot be a handshake at all.
         *
         * The line between "refuse" and "ignore" is the interesting part. Payload that is not a
         * bencoded dictionary is a peer that is not speaking BEP 10, and that is worth saying. A
         * dictionary whose `reqq` is a string, or whose `m` names an extension from 2019, is a peer
         * speaking BEP 10 slightly differently — and a client that closed the connection over it
         * would be refusing to talk to most of the swarm.
         */
        public fun decode(payload: ByteArray): ExtensionHandshake {
            val root =
                try {
                    Bencode.decode(payload) as? BDictionary
                        ?: throw WireException("an extension handshake is a dictionary")
                } catch (malformed: BencodeException) {
                    throw WireException("an extension handshake is not bencode: ${malformed.message}")
                }
            val names = mutableMapOf<String, Int>()
            (root["m"] as? BDictionary)?.entries?.forEach { (key, value) ->
                val id = (value as? BInteger)?.value ?: return@forEach
                if (id in Int.MIN_VALUE..Int.MAX_VALUE) names[key.asString()] = id.toInt()
            }
            return ExtensionHandshake(
                extensions = names,
                clientVersion = (root["v"] as? BString)?.asString(),
                listenPort = (root["p"] as? BInteger)?.value?.toInt(),
                requestQueueLength = (root["reqq"] as? BInteger)?.value?.toInt(),
                metadataSize = (root["metadata_size"] as? BInteger)?.value?.toInt(),
            )
        }
    }
}
