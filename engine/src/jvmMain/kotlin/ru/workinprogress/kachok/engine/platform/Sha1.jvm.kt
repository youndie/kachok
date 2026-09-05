package ru.workinprogress.kachok.engine.platform

import java.security.MessageDigest

/**
 * `MessageDigest` rather than a hand-written SHA-1: on this hardware the JDK's implementation is
 * intrinsified — `UseSHA1Intrinsics = true`, verified in research §1.1 — and no Kotlin loop will
 * come close.
 *
 * A fresh instance per call is deliberate here. This path runs once per torrent loaded, not once
 * per piece, so reuse would buy nothing and cost thread-confinement rules.
 */
internal actual fun sha1(
    bytes: ByteArray,
    fromIndex: Int,
    toIndex: Int,
): ByteArray {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(bytes, fromIndex, toIndex - fromIndex)
    return digest.digest()
}
