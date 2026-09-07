package io.github.youndie.kachok.engine.peer

import io.github.youndie.kachok.engine.PeerId

/**
 * What is on the other end, as far as its peer id admits.
 *
 * BEP 20 describes the convention every current client follows: `-AZ2060-` is two letters of
 * client and four digits of version, then twelve bytes of randomness. It is a *convention* — a peer
 * id is twenty arbitrary bytes and nothing enforces this — so every step of the parse can fail and
 * the answer is then whatever of the id is printable.
 *
 * **This never returns an empty string and never returns raw bytes.** It is drawn in a table beside
 * an address, and a peer that identified itself as twenty zeroes must occupy the same one line as
 * one that said `-qB5010-`. A client is also free to lie here, which is why the value is only ever
 * shown and never branched on.
 */
public fun clientOf(peerId: PeerId): String {
    val bytes = peerId.bytes
    azureusStyle(bytes)?.let { return it }
    shadowStyle(bytes)?.let { return it }
    return printable(bytes).ifBlank { UNKNOWN }
}

/** `-AZ2060-…` — the one nearly everything uses. */
private fun azureusStyle(bytes: ByteArray): String? {
    if (bytes.size < AZUREUS_PREFIX) return null
    if (bytes[0].toInt().toChar() != '-' || bytes[AZUREUS_PREFIX - 1].toInt().toChar() != '-') return null
    val code = "${bytes[1].toInt().toChar()}${bytes[2].toInt().toChar()}"
    if (!code.all { it.isLetterOrDigit() }) return null
    val digits = (3 until 7).map { bytes[it].toInt().toChar() }
    if (!digits.all { it.isDigit() || it.isLetter() }) return null
    val name = AZUREUS_CLIENTS[code] ?: code
    return "$name ${version(digits)}"
}

/**
 * `S5---…` and its relatives — Shadow's, from before BEP 20 settled.
 *
 * One letter of client and three characters of version in base 62, then `-` padding. Kept because
 * one of the two clients that still use it is a seedbox this project's own test swarm runs.
 */
private fun shadowStyle(bytes: ByteArray): String? {
    if (bytes.size < SHADOW_PREFIX) return null
    val letter = bytes[0].toInt().toChar()
    if (!letter.isLetter()) return null
    val digits = (1 until 4).map { bytes[it].toInt().toChar() }
    if (!digits.all { it.isLetterOrDigit() }) return null
    if (bytes[4].toInt().toChar() !in listOf('-', '0')) return null
    val name = SHADOW_CLIENTS[letter] ?: return null
    return "$name ${digits.joinToString(".")}"
}

/**
 * `2060` is 2.0.6.0, and trailing zeroes are dropped down to two components.
 *
 * BEP 20 gives four digits and most clients use two or three of them; `qBittorrent 5.1.0.0` is a
 * column of noise where `qBittorrent 5.1` is a version. Two is the floor because `libtorrent 2` is
 * a different-looking claim from `libtorrent 2.0`, and the design's own reference writes the
 * latter.
 *
 * A letter is what that client meant by a version and is printed as it stands.
 */
private fun version(digits: List<Char>): String {
    val parts = digits.map { it.toString() }.toMutableList()
    while (parts.size > MINIMUM_VERSION_PARTS && parts.last() == "0") parts.removeLast()
    return parts.joinToString(".")
}

private const val MINIMUM_VERSION_PARTS = 2

private fun printable(bytes: ByteArray): String =
    bytes
        .map { (it.toInt() and BYTE).toChar() }
        .filter { it.code in PRINTABLE }
        .joinToString("")
        .trim()
        .take(PRINTABLE_LIMIT)

private const val AZUREUS_PREFIX = 8

private const val SHADOW_PREFIX = 5

private const val BYTE = 0xFF

private val PRINTABLE = 0x20..0x7E

private const val PRINTABLE_LIMIT = 20

private const val UNKNOWN = "unknown"

/**
 * The two-letter codes worth naming.
 *
 * Not the whole of BEP 20's list: an unknown code is printed as itself, which is two characters a
 * person can search for, and a table of ninety clients that has to be maintained is worse than one
 * of a dozen that does not.
 */
private val AZUREUS_CLIENTS =
    mapOf(
        "AZ" to "Azureus",
        "BT" to "BitTorrent",
        "DE" to "Deluge",
        "KA" to "kachok",
        "LT" to "libtorrent",
        "lt" to "libTorrent",
        "TR" to "Transmission",
        "UT" to "µTorrent",
        "UM" to "µTorrent Mac",
        "qB" to "qBittorrent",
        "TX" to "Tixati",
        "WW" to "WebTorrent",
    )

private val SHADOW_CLIENTS = mapOf('S' to "Shadow", 'T' to "BitTornado", 'A' to "ABC", 'U' to "UPnP")
