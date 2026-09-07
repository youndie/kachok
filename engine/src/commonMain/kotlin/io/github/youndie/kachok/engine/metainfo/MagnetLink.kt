package io.github.youndie.kachok.engine.metainfo

import io.github.youndie.kachok.engine.InfoHash

/**
 * What a magnet link carries: an identity, optionally a name to show, optionally where to look for
 * peers. Everything a [Metainfo] would say about files and pieces is missing by construction and
 * arrives later from peers over BEP 9.
 */
public class MagnetLink(
    public val infoHash: InfoHash,
    public val displayName: String?,
    public val trackers: List<String>,
)

/**
 * `magnet:?xt=urn:btih:<hash>&dn=<name>&tr=<tracker>` (BEP 9's habitat).
 *
 * Both hash spellings in circulation are accepted: 40 hexadecimal characters and 32 base32
 * characters, either case. Everything else in the link is ignored rather than refused — a magnet
 * link is a URI other tools append their own parameters to, and refusing an unknown one would
 * break links that work everywhere else.
 */
public object MagnetParser {
    private const val SCHEME = "magnet:?"
    private const val URN_PREFIX = "urn:btih:"
    private const val HEX_LENGTH = 40
    private const val BASE32_LENGTH = 32
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    public fun parse(uri: String): MagnetLink {
        if (!uri.startsWith(SCHEME)) {
            throw MetainfoException("not a magnet link: it does not start with '$SCHEME'")
        }
        var infoHash: InfoHash? = null
        var displayName: String? = null
        val trackers = mutableListOf<String>()

        uri.removePrefix(SCHEME).split('&').forEach { parameter ->
            val separator = parameter.indexOf('=')
            if (separator <= 0) return@forEach
            val key = parameter.substring(0, separator)
            val value = percentDecode(parameter.substring(separator + 1))
            when (key) {
                "xt" -> {
                    if (infoHash == null && value.startsWith(URN_PREFIX)) {
                        infoHash = decodeInfoHash(value.removePrefix(URN_PREFIX))
                    }
                }

                "dn" -> {
                    if (displayName == null) displayName = value
                }

                "tr" -> {
                    if (value.isNotBlank()) trackers += value
                }

                // Unknown parameters are not an error: a magnet link is a URI that other tools
                // append their own keys to.
                else -> {}
            }
        }

        return MagnetLink(
            infoHash =
                infoHash ?: throw MetainfoException(
                    "the magnet link has no `xt` parameter of the form `${URN_PREFIX}<hash>`",
                ),
            displayName = displayName,
            trackers = trackers.distinct(),
        )
    }

    private fun decodeInfoHash(text: String): InfoHash =
        when (text.length) {
            HEX_LENGTH -> InfoHash(decodeHex(text))

            BASE32_LENGTH -> InfoHash(decodeBase32(text.uppercase()))

            else -> throw MetainfoException(
                "`xt` hash is ${text.length} characters; expected $HEX_LENGTH hexadecimal or " +
                    "$BASE32_LENGTH base32",
            )
        }

    private fun decodeHex(text: String): ByteArray =
        ByteArray(text.length / 2) { index ->
            val high = hexDigit(text[index * 2])
            val low = hexDigit(text[index * 2 + 1])
            ((high shl 4) or low).toByte()
        }

    private fun hexDigit(character: Char): Int {
        val value =
            when (character) {
                in '0'..'9' -> character - '0'
                in 'a'..'f' -> character - 'a' + 10
                in 'A'..'F' -> character - 'A' + 10
                else -> throw MetainfoException("`xt` hash has a non-hexadecimal character '$character'")
            }
        return value
    }

    /**
     * RFC 4648 base32, no padding: 32 characters are exactly 160 bits, which is exactly an info
     * hash, so the general case with padding cannot arise here.
     */
    private fun decodeBase32(text: String): ByteArray {
        val out = ByteArray(InfoHash.SIZE)
        var buffer = 0L
        var bits = 0
        var written = 0
        text.forEach { character ->
            val value = BASE32_ALPHABET.indexOf(character)
            if (value < 0) throw MetainfoException("`xt` hash has a non-base32 character '$character'")
            buffer = (buffer shl 5) or value.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[written++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out
    }

    /**
     * `%XX` only. `+` is left alone on purpose: the query-string convention that reads it as a
     * space belongs to HTML forms, and a `+` inside a tracker URL is a literal plus far more often
     * than it is a space.
     */
    private fun percentDecode(text: String): String {
        if (!text.contains('%')) return text
        val out = ByteArray(text.length)
        var written = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '%' && index + 2 < text.length) {
                out[written++] = ((hexDigit(text[index + 1]) shl 4) or hexDigit(text[index + 2])).toByte()
                index += 3
            } else {
                out[written++] = character.code.toByte()
                index++
            }
        }
        return out.decodeToString(0, written)
    }
}
