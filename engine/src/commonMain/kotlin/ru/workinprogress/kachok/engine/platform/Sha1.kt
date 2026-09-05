package ru.workinprogress.kachok.engine.platform

/**
 * SHA-1 over a byte range, one shot.
 *
 * A platform primitive rather than an interface: there is exactly one right answer per platform,
 * which is what `expect`/`actual` is for (research D7). Bulk piece hashing is a different concern
 * with a different shape — a digest reused per thread on a bounded dispatcher (B-13) — and it does
 * not go through here.
 *
 * [toIndex] is exclusive.
 */
internal expect fun sha1(
    bytes: ByteArray,
    fromIndex: Int,
    toIndex: Int,
): ByteArray
